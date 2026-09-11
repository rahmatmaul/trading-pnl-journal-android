package com.tradingpnl.journal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class StagedPackage(
    val backup: BackupData,
    val attachments: List<AttachmentEntity>,
    val directory: File
)

object BackupPackageCodec {
    private const val JOURNAL_ENTRY = "journal.json"
    private const val MAX_PACKAGE_BYTES = 250_000_000L

    fun write(
        output: OutputStream,
        journalJson: String,
        attachments: List<AttachmentEntity>,
        attachmentStore: AttachmentStore
    ) {
        val root = JSONObject(journalJson)
        val array = JSONArray()
        attachments.forEach { array.put(BackupCodec.attachmentToJson(it)) }
        root.put("attachments", array)
        root.put("packageVersion", 1)

        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JOURNAL_ENTRY))
            zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            attachments.forEach { attachment ->
                val checked = AttachmentValidator.validate(attachment)
                val file = attachmentStore.fileFor(checked.relativePath)
                require(file.isFile && file.length() == checked.sizeBytes) { "Attachment file is missing or changed: ${checked.fileName}" }
                zip.putNextEntry(ZipEntry("images/${checked.relativePath}"))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, cacheRoot: File): StagedPackage {
        val directory = File(cacheRoot, "journal-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var journal: String? = null
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith('/') && !name.contains("../")) { "Unsafe file path in backup package" }
                    if (entry.isDirectory) continue
                    if (name == JOURNAL_ENTRY) {
                        val bytes = zip.readLimited(25_000_000)
                        total += bytes.size
                        journal = bytes.toString(Charsets.UTF_8)
                    } else if (name.startsWith("images/")) {
                        val relative = name.removePrefix("images/")
                        require(relative.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9-]+\\.(?:jpg|png|webp)"))) { "Unsafe image path in backup package" }
                        val destination = safeFile(directory, relative)
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { output ->
                            val copied = zip.copyLimited(output, AttachmentStore.MAX_IMAGE_BYTES)
                            total += copied
                        }
                    }
                    require(total <= MAX_PACKAGE_BYTES) { "Backup package is larger than 250 MB" }
                    zip.closeEntry()
                }
            }

            val raw = journal ?: error("Backup package does not contain journal.json")
            val json = JSONObject(raw)
            val backup = BackupCodec.parseJson(raw)
            val metadata = json.optJSONArray("attachments") ?: JSONArray()
            val attachments = ArrayList<AttachmentEntity>(metadata.length())
            for (index in 0 until metadata.length()) {
                val item = try {
                    BackupCodec.attachmentFromJson(metadata.getJSONObject(index))
                } catch (error: Exception) {
                    throw IllegalArgumentException("Invalid attachment at item ${index + 1}: ${error.message}")
                }
                val file = safeFile(directory, item.relativePath)
                require(file.isFile) { "Package is missing image: ${item.fileName}" }
                require(file.length() == item.sizeBytes) { "Image size does not match backup metadata: ${item.fileName}" }
                require(file.sha256() == item.sha256) { "Image checksum failed: ${item.fileName}" }
                attachments += item
            }
            return StagedPackage(backup, attachments, directory)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun installImages(
        staged: StagedPackage,
        acceptedTradeIds: Set<String>,
        attachmentStore: AttachmentStore
    ): List<AttachmentEntity> {
        val installed = ArrayList<AttachmentEntity>()
        try {
            staged.attachments.filter { it.tradeId in acceptedTradeIds }.forEach { source ->
                val id = UUID.randomUUID().toString()
                val extension = source.relativePath.substringAfterLast('.')
                val relative = "${source.tradeId}/$id.$extension"
                val from = safeFile(staged.directory, source.relativePath)
                val to = attachmentStore.fileFor(relative)
                to.parentFile?.mkdirs()
                from.copyTo(to, overwrite = false)
                installed += source.copy(id = id, relativePath = relative)
            }
            return installed
        } catch (error: Exception) {
            installed.forEach { runCatching { attachmentStore.fileFor(it.relativePath).delete() } }
            throw error
        }
    }

    private fun safeFile(root: File, relativePath: String): File {
        val candidate = File(root, relativePath).canonicalFile
        val safeRoot = root.canonicalFile
        require(candidate.path.startsWith(safeRoot.path + File.separator)) { "Unsafe package path" }
        return candidate
    }

    private fun InputStream.readLimited(maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyLimited(output, maxBytes)
        return output.toByteArray()
    }

    private fun InputStream.copyLimited(output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "A file in the backup package is too large" }
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
