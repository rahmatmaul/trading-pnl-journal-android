package com.tradingpnl.desktop

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

data class RemoteFile(val id: String, val name: String)
data class DesktopSyncResult(val uploaded: Int, val downloaded: Int, val changed: Int)
class DesktopDriveException(val code: Int, message: String) : Exception(message)

class DesktopGoogleAuth(private val dataDir: File) {
    private val scopes = listOf(
        "https://www.googleapis.com/auth/drive.appdata",
        "https://www.googleapis.com/auth/drive.file"
    )

    fun authorize(): Credential {
        val source = locateCredentialFile()
        val local = File(dataDir, "client_secret.json")
        if (source.canonicalFile != local.canonicalFile) source.copyTo(local, overwrite = true)
        val secrets = FileReader(local).use { GoogleClientSecrets.load(GsonFactory.getDefaultInstance(), it) }
        val flow = GoogleAuthorizationCodeFlow.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), secrets, scopes)
            .setDataStoreFactory(FileDataStoreFactory(File(dataDir, "oauth_tokens")))
            .setAccessType("offline")
            .build()
        return AuthorizationCodeInstalledApp(flow, LocalServerReceiver.Builder().setPort(0).build()).authorize("user")
    }

    private fun locateCredentialFile(): File {
        val candidates = ArrayList<File>()
        System.getProperty("tradingJournal.oauthJson")?.let { candidates += File(it) }
        System.getenv("TRADING_JOURNAL_OAUTH_JSON")?.let { candidates += File(it) }
        candidates += File(dataDir, "client_secret.json")
        candidates += File(System.getProperty("user.dir"), "desktop/.secrets/client_secret.json")
        File(System.getProperty("user.home"), "Downloads").listFiles()
            ?.filter { it.isFile && it.name.startsWith("client_secret_") && it.extension.equals("json", true) }
            ?.sortedByDescending(File::lastModified)?.let(candidates::addAll)
        return candidates.firstOrNull(File::isFile)
            ?: error("Desktop OAuth JSON not found. Download it from Google Cloud, then place it in Downloads or %APPDATA%\\TradingJournal.")
    }
}

class DesktopDriveRest(private val token: String) {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    fun list(prefix: String): List<RemoteFile> {
        val output = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val params = linkedMapOf(
                "spaces" to "appDataFolder",
                "q" to "name contains '$prefix' and trashed = false",
                "pageSize" to "1000",
                "fields" to "nextPageToken,files(id,name)"
            )
            page?.let { params["pageToken"] = it }
            val url = "https://www.googleapis.com/drive/v3/files?" + params.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}"
            }
            val json = JSONObject(text("GET", url))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) files.getJSONObject(i).let { output += RemoteFile(it.getString("id"), it.getString("name")) }
            page = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (page != null)
        return output
    }

    fun download(id: String): ByteArray = bytes("GET", "https://www.googleapis.com/drive/v3/files/${encode(id)}?alt=media")

    fun uploadEvent(event: DesktopEvent) {
        multipart("tpj-event-${event.eventId}.json", "application/json", event.json().toString().toByteArray(), "trading-journal-sync-v1")
    }

    fun uploadBlob(item: JSONObject, data: ByteArray): RemoteFile = multipart(
        "tpj-blob-${item.getString("sha256")}", item.getString("mimeType"), data, "trading-journal-blob-v1"
    )

    fun backupJournal(data: ByteArray, fileName: String): RemoteFile {
        val query = "name = 'Trading Journal Backups' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?spaces=drive&q=${URLEncoder.encode(query, StandardCharsets.UTF_8)}&fields=files(id,name)&pageSize=10"
        val files = JSONObject(text("GET", url)).optJSONArray("files") ?: JSONArray()
        val folderId = if (files.length() > 0) files.getJSONObject(0).getString("id") else {
            val metadata = JSONObject().put("name", "Trading Journal Backups").put("mimeType", "application/vnd.google-apps.folder")
            JSONObject(text("POST", "https://www.googleapis.com/drive/v3/files?fields=id", "application/json", metadata.toString().toByteArray())).getString("id")
        }
        return multipart(fileName, "application/octet-stream", data, "trading-journal-backup-v1", folderId)
    }

    private fun multipart(name: String, mime: String, content: ByteArray, protocol: String, parent: String = "appDataFolder"): RemoteFile {
        val boundary = "tpj-${System.nanoTime()}"
        val metadata = JSONObject().put("name", name).put("parents", JSONArray().put(parent))
            .put("mimeType", mime).put("appProperties", JSONObject().put("protocol", protocol))
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(metadata.toString().toByteArray())
            write("\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            write(content)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val json = JSONObject(text("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name", "multipart/related; boundary=$boundary", body))
        return RemoteFile(json.getString("id"), json.optString("name", name))
    }

    private fun text(method: String, url: String, contentType: String? = null, body: ByteArray? = null) = bytes(method, url, contentType, body).toString(StandardCharsets.UTF_8)
    private fun bytes(method: String, url: String, contentType: String? = null, body: ByteArray? = null): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer $token").header("Accept", "application/json")
        if (contentType != null) builder.header("Content-Type", contentType)
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            val raw = response.body().toString(StandardCharsets.UTF_8)
            val message = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault("Google Drive request failed")
            throw DesktopDriveException(response.statusCode(), message)
        }
        return response.body()
    }
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

class DesktopSync(private val store: DesktopStore, private val auth: DesktopGoogleAuth) {
    fun run(): DesktopSyncResult {
        val credential = auth.authorize()
        if (credential.accessToken.isNullOrBlank() || credential.expiresInSeconds?.let { it < 60 } == true) credential.refreshToken()
        val api = DesktopDriveRest(credential.accessToken ?: error("Google authorization did not return an access token"))
        store.bootstrap()
        var downloaded = 0
        var changed = 0
        val events = api.list("tpj-event-").mapNotNull { file ->
            val eventId = file.name.removePrefix("tpj-event-").removeSuffix(".json")
            if (store.hasReceipt(eventId)) null else {
                downloaded++
                DesktopEvent.parse(api.download(file.id).toString(StandardCharsets.UTF_8))
            }
        }.sortedWith(compareBy<DesktopEvent> { if (it.entityType == "attachment") 1 else 0 }.thenBy { it.createdAt }.thenBy { it.eventId })
        val blobs = api.list("tpj-blob-").associateByTo(LinkedHashMap()) { it.name }
        events.forEach { event ->
            if (store.shouldApply(event) && event.entityType == "attachment" && event.operation == "upsert") {
                val blob = blobs["tpj-blob-${event.payload.getString("sha256")}"] ?: error("A synced chart image is missing from Google Drive")
                store.installSyncedImage(event.payload, api.download(blob.id))
            }
            if (store.applyRemote(event)) changed++
        }
        store.cleanupOrphanImages()
        var uploaded = 0
        store.pending().forEach { event ->
            if (event.entityType == "attachment" && event.operation == "upsert") {
                val name = "tpj-blob-${event.payload.getString("sha256")}"
                if (name !in blobs) {
                    val data = store.imageFile(event.payload.getString("relativePath")).readBytes()
                    blobs[name] = api.uploadBlob(event.payload, data)
                }
            }
            api.uploadEvent(event)
            store.markUploaded(event.eventId)
            uploaded++
        }
        store.updateState(connected = true, lastSyncAt = System.currentTimeMillis(), lastError = null, pendingAction = false)
        if (uploaded > 0 || changed > 0) store.notify("sync", "Journal synced", "$uploaded uploaded, $changed changes received.")
        return DesktopSyncResult(uploaded, downloaded, changed)
    }
}
