package com.tradingpnl.journal

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale

class BackupManager(
    private val context: Context,
    private val repository: JournalRepository,
    private val attachmentStore: AttachmentStore,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val onResult: (Boolean, String) -> Unit
) {
    private var pendingJob: Job? = null

    fun scheduleAutoBackup() {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(800)
            val result = runCatching { writeRecoveryBackup() }
            result.exceptionOrNull()?.let { onResult(false, "Database saved, but recovery backup failed: ${it.message}") }
        }
    }

    fun backupNow() {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            runCatching { writeRecoveryBackup(requireFolder = true) }
                .onSuccess { onResult(true, "Recovery backup updated") }
                .onFailure { onResult(false, it.message ?: "Backup failed") }
        }
    }

    suspend fun createJson(): String = withContext(Dispatchers.IO) {
        BackupCodec.exportJson(repository.allTrades(), repository.getSettings(), appVersion)
    }

    suspend fun createCsv(): String = withContext(Dispatchers.IO) {
        BackupCodec.exportCsv(repository.allTrades())
    }

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(text)
            it.flush()
        } ?: error("Unable to open the selected file")
    }

    suspend fun writePackage(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            BackupPackageCodec.write(
                output,
                BackupCodec.exportJson(repository.allTrades(), repository.getSettings(), appVersion),
                repository.allAttachments(),
                attachmentStore
            )
        } ?: error("Unable to open the selected package file")
    }

    suspend fun createPackageBytes(): ByteArray = withContext(Dispatchers.IO) {
        ByteArrayOutputStream().use { output ->
            BackupPackageCodec.write(
                output,
                BackupCodec.exportJson(repository.allTrades(), repository.getSettings(), appVersion),
                repository.allAttachments(),
                attachmentStore
            )
            output.toByteArray()
        }
    }

    fun folderName(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uriString))?.name ?: "Selected folder"
    }

    private suspend fun writeRecoveryBackup(requireFolder: Boolean = false) = withContext(Dispatchers.IO) {
        val settings = repository.getSettings()
        val folderUri = settings.backupFolderUri
        if (folderUri.isNullOrBlank()) {
            if (requireFolder) error("Choose a backup folder first") else return@withContext
        }
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            ?: error("Backup folder is no longer available")
        check(folder.exists() && folder.canWrite()) { "Backup folder is not writable; choose it again" }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        writeNewPackage(folder, "TradingJournal-$stamp.tpjbackup")

        val tempName = "TradingJournal-latest.tmp"
        folder.findFile(tempName)?.delete()
        val temp = folder.createFile("application/octet-stream", tempName) ?: error("Unable to create backup file")
        context.contentResolver.openOutputStream(temp.uri, "wt")?.use { output ->
            BackupPackageCodec.write(
                output,
                BackupCodec.exportJson(repository.allTrades(), settings, appVersion),
                repository.allAttachments(),
                attachmentStore
            )
        } ?: error("Unable to write backup file")
        folder.findFile("TradingJournal-latest.tpjbackup")?.delete()
        check(temp.renameTo("TradingJournal-latest.tpjbackup")) { "Unable to finalize latest backup" }

        folder.listFiles()
            .filter { it.name?.matches(Regex("TradingJournal-\\d{8}-\\d{6}(?: \\(\\d+\\))?\\.tpjbackup")) == true }
            .sortedByDescending { it.lastModified() }
            .drop(5)
            .forEach { it.delete() }
    }

    private suspend fun writeNewPackage(folder: DocumentFile, name: String) {
        val tempName = "$name.tmp"
        folder.findFile(tempName)?.delete()
        val temp = folder.createFile("application/octet-stream", tempName) ?: error("Unable to create dated backup")
        try {
            context.contentResolver.openOutputStream(temp.uri, "wt")?.use { output ->
                BackupPackageCodec.write(
                    output,
                    BackupCodec.exportJson(repository.allTrades(), repository.getSettings(), appVersion),
                    repository.allAttachments(),
                    attachmentStore
                )
            } ?: error("Unable to write dated backup")
            check(temp.renameTo(name)) { "Unable to finalize dated backup" }
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }
}
