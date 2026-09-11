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
import java.util.Date
import java.util.Locale

class BackupManager(
    private val context: Context,
    private val repository: JournalRepository,
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

        val json = BackupCodec.exportJson(repository.allTrades(), settings, appVersion)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        writeNewFile(folder, "TradingJournal-$stamp.json", json)

        val tempName = "TradingJournal-latest.tmp"
        folder.findFile(tempName)?.delete()
        val temp = folder.createFile("application/json", tempName) ?: error("Unable to create backup file")
        context.contentResolver.openOutputStream(temp.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(json)
            it.flush()
        } ?: error("Unable to write backup file")
        folder.findFile("TradingJournal-latest.json")?.delete()
        check(temp.renameTo("TradingJournal-latest.json")) { "Unable to finalize latest backup" }

        folder.listFiles()
            .filter { it.name?.matches(Regex("TradingJournal-\\d{8}-\\d{6}(?: \\(\\d+\\))?\\.json")) == true }
            .sortedByDescending { it.lastModified() }
            .drop(5)
            .forEach { it.delete() }
    }

    private fun writeNewFile(folder: DocumentFile, name: String, text: String) {
        val file = folder.createFile("application/json", name) ?: error("Unable to create dated backup")
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(text)
            it.flush()
        } ?: error("Unable to write dated backup")
    }
}
