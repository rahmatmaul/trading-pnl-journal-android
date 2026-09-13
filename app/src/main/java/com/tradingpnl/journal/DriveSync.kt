package com.tradingpnl.journal

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.TimeUnit

object GoogleDriveAuthorization {
    const val APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    const val FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    val scopes = listOf(Scope(APPDATA_SCOPE), Scope(FILE_SCOPE))

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(scopes)
        .build()
}

data class DriveFileRef(val id: String, val name: String)
data class SyncRunResult(val uploaded: Int, val downloaded: Int, val changed: Int)

class DriveApiException(val statusCode: Int, message: String) : Exception(message)

class GoogleDriveApi(private val accessToken: String) {
    suspend fun backupJournal(bytes: ByteArray, fileName: String): DriveFileRef = withContext(Dispatchers.IO) {
        val query = "name = 'Trading Journal Backups' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val listUrl = "https://www.googleapis.com/drive/v3/files?spaces=drive&q=${URLEncoder.encode(query, Charsets.UTF_8.name())}&fields=files(id,name)&pageSize=10"
        val listed = JSONObject(request("GET", listUrl)).optJSONArray("files") ?: JSONArray()
        val folderId = if (listed.length() > 0) listed.getJSONObject(0).getString("id") else {
            val metadata = JSONObject().put("name", "Trading Journal Backups")
                .put("mimeType", "application/vnd.google-apps.folder")
            JSONObject(request(
                "POST",
                "https://www.googleapis.com/drive/v3/files?fields=id",
                "application/json",
                metadata.toString().toByteArray(Charsets.UTF_8)
            )).getString("id")
        }
        val boundary = "tpj-backup-${System.nanoTime()}"
        val metadata = JSONObject().put("name", fileName).put("parents", JSONArray().put(folderId))
            .put("mimeType", "application/octet-stream")
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(metadata.toString().toByteArray(Charsets.UTF_8))
            write("\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val uploaded = JSONObject(request(
            "POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name",
            "multipart/related; boundary=$boundary",
            body
        ))
        DriveFileRef(uploaded.getString("id"), uploaded.optString("name", fileName))
    }

    suspend fun listEventFiles(): List<DriveFileRef> = withContext(Dispatchers.IO) {
        val all = ArrayList<DriveFileRef>()
        var pageToken: String? = null
        do {
            val query = "name contains 'tpj-event-' and trashed = false"
            val params = linkedMapOf(
                "spaces" to "appDataFolder",
                "q" to query,
                "pageSize" to "1000",
                "fields" to "nextPageToken,files(id,name)"
            )
            if (pageToken != null) params["pageToken"] = pageToken
            val url = "https://www.googleapis.com/drive/v3/files?" + params.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8.name())}"
            }
            val json = JSONObject(request("GET", url))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                all += DriveFileRef(item.getString("id"), item.getString("name"))
            }
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        all
    }

    suspend fun download(fileId: String): String = withContext(Dispatchers.IO) {
        request("GET", "https://www.googleapis.com/drive/v3/files/${encodePath(fileId)}?alt=media")
    }

    suspend fun downloadBytes(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        requestBytes("GET", "https://www.googleapis.com/drive/v3/files/${encodePath(fileId)}?alt=media")
    }

    suspend fun listBlobFiles(): List<DriveFileRef> = withContext(Dispatchers.IO) {
        val all = ArrayList<DriveFileRef>()
        var pageToken: String? = null
        do {
            val params = linkedMapOf(
                "spaces" to "appDataFolder",
                "q" to "name contains 'tpj-blob-' and trashed = false",
                "pageSize" to "1000",
                "fields" to "nextPageToken,files(id,name)"
            )
            if (pageToken != null) params["pageToken"] = pageToken
            val url = "https://www.googleapis.com/drive/v3/files?" + params.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8.name())}"
            }
            val json = JSONObject(request("GET", url))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                all += DriveFileRef(item.getString("id"), item.getString("name"))
            }
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        all
    }

    suspend fun uploadEvent(envelope: SyncEnvelope) = withContext(Dispatchers.IO) {
        val boundary = "tpj-${envelope.eventId}"
        val metadata = JSONObject()
            .put("name", "tpj-event-${envelope.eventId}.json")
            .put("parents", JSONArray().put("appDataFolder"))
            .put("mimeType", "application/json")
            .put("appProperties", JSONObject().put("protocol", "trading-journal-sync-v1"))
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(metadata.toString().toByteArray(Charsets.UTF_8))
            write("\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n".toByteArray())
            write(envelope.toJson().toString().toByteArray(Charsets.UTF_8))
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        request(
            method = "POST",
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            contentType = "multipart/related; boundary=$boundary",
            body = body
        )
    }

    suspend fun uploadBlob(attachment: AttachmentEntity, bytes: ByteArray): DriveFileRef = withContext(Dispatchers.IO) {
        require(bytes.size.toLong() == attachment.sizeBytes) { "Image size changed before sync" }
        val boundary = "tpj-blob-${attachment.id}"
        val name = "tpj-blob-${attachment.sha256}"
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put("appDataFolder"))
            .put("mimeType", attachment.mimeType)
            .put("appProperties", JSONObject().put("protocol", "trading-journal-blob-v1"))
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(metadata.toString().toByteArray(Charsets.UTF_8))
            write("\r\n--$boundary\r\nContent-Type: ${attachment.mimeType}\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val result = JSONObject(request(
            method = "POST",
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name",
            contentType = "multipart/related; boundary=$boundary",
            body = body
        ))
        DriveFileRef(result.getString("id"), result.optString("name", name))
    }

    private fun request(
        method: String,
        url: String,
        contentType: String? = null,
        body: ByteArray? = null
    ): String = requestBytes(method, url, contentType, body).toString(Charsets.UTF_8)

    private fun requestBytes(
        method: String,
        url: String,
        contentType: String? = null,
        body: ByteArray? = null
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) {
                val text = bytes.toString(Charsets.UTF_8)
                val reason = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "Google Drive request failed"
                throw DriveApiException(status, reason)
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

class DriveSyncCoordinator(
    private val repository: JournalRepository,
    private val attachmentStore: AttachmentStore
) {
    suspend fun sync(accessToken: String): SyncRunResult {
        repository.ensureBootstrapEvents()
        val api = GoogleDriveApi(accessToken)
        var downloaded = 0
        var changed = 0
        val remoteEvents = api.listEventFiles().mapNotNull { file ->
            val eventId = file.name.removePrefix("tpj-event-").removeSuffix(".json")
            if (repository.hasSyncReceipt(eventId)) return@mapNotNull null
            downloaded++
            SyncEnvelope.parse(api.download(file.id))
        }.sortedWith(compareBy<SyncEnvelope> { if (it.entityType == "attachment") 1 else 0 }
            .thenBy { it.createdAt }
            .thenBy { it.eventId })
        val blobs = api.listBlobFiles().associateByTo(LinkedHashMap()) { it.name }
        remoteEvents.forEach { envelope ->
            val shouldApply = repository.shouldApplyRemote(envelope)
            if (shouldApply && envelope.entityType == "attachment" && envelope.operation == "upsert") {
                val attachment = BackupCodec.attachmentFromJson(envelope.payload).copy(id = envelope.entityId)
                val blob = blobs["tpj-blob-${attachment.sha256}"] ?: error("A synced chart image is missing from Google Drive")
                attachmentStore.installSynced(attachment, api.downloadBytes(blob.id))
            }
            if (repository.applyRemote(envelope)) changed++
        }
        attachmentStore.cleanupOrphans()

        var uploaded = 0
        repository.pendingSync(500).forEach { pending ->
            try {
                if (pending.entityType == "attachment" && pending.operation == "upsert") {
                    val attachment = BackupCodec.attachmentFromJson(JSONObject(pending.payload))
                    val blobName = "tpj-blob-${attachment.sha256}"
                    if (blobName !in blobs) {
                        val bytes = withContext(Dispatchers.IO) {
                            attachmentStore.fileFor(attachment.relativePath).readBytes()
                        }
                        blobs[blobName] = api.uploadBlob(attachment, bytes)
                    }
                }
                val deviceId = repository.getSyncState().deviceId
                api.uploadEvent(SyncEnvelope.fromOutbox(deviceId, pending))
                repository.markUploaded(pending.eventId)
                uploaded++
            } catch (error: Exception) {
                repository.recordSyncFailure(pending.eventId, error.message ?: "Upload failed")
                throw error
            }
        }
        return SyncRunResult(uploaded, downloaded, changed)
    }
}

object DriveSyncScheduler {
    private const val NOW = "trading-journal-drive-sync-now"
    private const val PERIODIC = "trading-journal-drive-sync-periodic"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DriveSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
        )
        enqueueNow(context)
    }

    fun enqueueNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DriveSyncWorker>().setConstraints(network).build()
        )
    }
}

class DriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = JournalRepository(JournalDatabase.get(applicationContext))
        if (!repository.getSyncState().connected) return Result.success()
        return try {
            val result: AuthorizationResult = Identity.getAuthorizationClient(applicationContext)
                .authorize(GoogleDriveAuthorization.request())
                .await()
            if (result.hasResolution() || result.accessToken.isNullOrBlank()) {
                repository.saveSyncState { it.copy(pendingUserAction = true, lastError = "Open the app to reconnect Google Drive") }
                Result.success()
            } else {
                val previousError = repository.getSyncState().lastError
                val attachmentStore = AttachmentStore(applicationContext, repository)
                val run = DriveSyncCoordinator(repository, attachmentStore).sync(result.accessToken!!)
                repository.saveSyncState {
                    it.copy(lastSyncAt = System.currentTimeMillis(), lastError = null, pendingUserAction = false)
                }
                if (previousError != null) repository.notify("success", "Drive connected again", "Pending journal changes were synced.")
                if (run.uploaded > 0 || run.changed > 0) {
                    repository.notify("sync", "Journal synced", "${run.uploaded} uploaded, ${run.changed} changes received.")
                }
                Result.success()
            }
        } catch (error: Exception) {
            val message = error.message ?: "Google Drive sync failed"
            repository.saveSyncState { it.copy(lastError = message) }
            val quota = error is DriveApiException && error.statusCode == 403 && message.contains("storage", ignoreCase = true)
            if (quota) repository.notify("error", "Google Drive storage is full", "Your journal is safe on this device. Sync will resume automatically after space is available.", true)
            if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}
