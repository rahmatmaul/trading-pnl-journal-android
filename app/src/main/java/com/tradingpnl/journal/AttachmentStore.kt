package com.tradingpnl.journal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class AttachmentStore(
    private val context: Context,
    private val repository: JournalRepository
) {
    val rootDir: File = File(context.filesDir, "trade_images").apply { mkdirs() }

    suspend fun importUris(tradeId: String, kind: String, uris: List<Uri>): List<AttachmentEntity> = withContext(Dispatchers.IO) {
        require(kind in setOf("before", "after", "other")) { "Invalid image category" }
        require(uris.size <= 6) { "Select no more than 6 images at once" }
        val startPosition = repository.attachmentsForTrade(tradeId).size
        val imported = ArrayList<AttachmentEntity>()
        try {
            uris.forEachIndexed { index, uri -> imported += importOne(tradeId, kind, uri, startPosition + index) }
            imported
        } catch (error: Exception) {
            imported.forEach { runCatching { repository.deleteAttachment(it.id) }; runCatching { fileFor(it.relativePath).delete() } }
            throw error
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val item = repository.getAttachment(id) ?: error("Attachment not found")
        repository.deleteAttachment(id)
        fileFor(item.relativePath).delete()
        fileFor(item.relativePath).parentFile?.takeIf { it.listFiles()?.isEmpty() == true }?.delete()
    }

    suspend fun deleteFilesForTrade(tradeId: String) = withContext(Dispatchers.IO) {
        val items = repository.attachmentsForTrade(tradeId)
        deleteFiles(items)
        File(rootDir, tradeId).takeIf { it.exists() }?.deleteRecursively()
    }

    fun deleteFiles(items: List<AttachmentEntity>) {
        items.forEach { runCatching { fileFor(it.relativePath).delete() } }
        items.map { it.tradeId }.distinct().forEach { tradeId ->
            File(rootDir, tradeId).takeIf { it.exists() && it.listFiles()?.isEmpty() == true }?.delete()
        }
    }

    suspend fun installSynced(item: AttachmentEntity, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val checked = AttachmentValidator.validate(item)
        require(bytes.size.toLong() == checked.sizeBytes) { "Synced image size does not match" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(digest == checked.sha256) { "Synced image checksum does not match" }
        val destination = fileFor(checked.relativePath)
        if (destination.exists() && destination.length() == checked.sizeBytes) return@withContext
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${checked.id}.sync")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Unable to install synced image" }
        } finally {
            temporary.delete()
        }
    }

    suspend fun cleanupOrphans() = withContext(Dispatchers.IO) {
        val keep = repository.allAttachments().mapTo(HashSet()) { fileFor(it.relativePath).canonicalPath }
        rootDir.walkTopDown().filter(File::isFile).forEach { file ->
            if (file.canonicalPath !in keep) file.delete()
        }
        rootDir.walkBottomUp().filter { it.isDirectory && it != rootDir && it.listFiles()?.isEmpty() == true }
            .forEach(File::delete)
    }

    suspend fun deleteAllFiles() = withContext(Dispatchers.IO) {
        rootDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    suspend fun duplicate(sourceTradeId: String, targetTradeId: String) = withContext(Dispatchers.IO) {
        val sourceItems = repository.attachmentsForTrade(sourceTradeId)
        val created = ArrayList<AttachmentEntity>()
        try {
            sourceItems.forEachIndexed { index, source ->
                val id = UUID.randomUUID().toString()
                val extension = source.relativePath.substringAfterLast('.', "jpg")
                val relative = "$targetTradeId/$id.$extension"
                val destination = fileFor(relative)
                destination.parentFile?.mkdirs()
                fileFor(source.relativePath).copyTo(destination, overwrite = false)
                val copy = source.copy(
                    id = id,
                    tradeId = targetTradeId,
                    relativePath = relative,
                    position = index,
                    createdAt = System.currentTimeMillis()
                )
                repository.addAttachment(copy)
                created += copy
            }
        } catch (error: Exception) {
            created.forEach { runCatching { repository.deleteAttachment(it.id) }; runCatching { fileFor(it.relativePath).delete() } }
            throw error
        }
    }

    fun fileFor(relativePath: String): File {
        val candidate = File(rootDir, relativePath).canonicalFile
        val root = rootDir.canonicalFile
        require(candidate.path.startsWith(root.path + File.separator)) { "Unsafe attachment path" }
        return candidate
    }

    private suspend fun importOne(tradeId: String, kind: String, uri: Uri, position: Int): AttachmentEntity {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.lowercase().orEmpty()
        val normalizedMime = when (mime) {
            "image/jpeg", "image/jpg" -> "image/jpeg"
            "image/png" -> "image/png"
            "image/webp" -> "image/webp"
            else -> error("Only JPEG, PNG, and WebP images are supported")
        }
        val extension = when (normalizedMime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val id = UUID.randomUUID().toString()
        val relative = "$tradeId/$id.$extension"
        val destination = fileFor(relative)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".$id.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_IMAGE_BYTES) { "Each image must be 20 MB or smaller" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("Unable to read the selected image")
            require(size > 0) { "The selected image is empty" }
            check(temporary.renameTo(destination)) { "Unable to store the selected image" }
            val item = AttachmentValidator.validate(
                AttachmentEntity(
                    id = id,
                    tradeId = tradeId,
                    kind = kind,
                    fileName = displayName(uri) ?: "chart.$extension",
                    mimeType = normalizedMime,
                    relativePath = relative,
                    position = position,
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    sizeBytes = size,
                    createdAt = System.currentTimeMillis()
                )
            )
            try {
                repository.addAttachment(item)
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
            return item
        } finally {
            temporary.delete()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.take(160) else null
        }
    }.getOrNull()

    companion object { const val MAX_IMAGE_BYTES = 20_000_000L }
}
