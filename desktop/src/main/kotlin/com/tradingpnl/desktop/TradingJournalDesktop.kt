package com.tradingpnl.desktop

import javafx.application.Application
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.stage.FileChooser
import javafx.stage.Stage
import netscape.javascript.JSObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class TradingJournalDesktop : Application() {
    private lateinit var store: DesktopStore
    private lateinit var engine: WebEngine
    private lateinit var stage: Stage
    private lateinit var bridge: DesktopBridge
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "journal-sync").apply { isDaemon = true } }

    override fun start(primaryStage: Stage) {
        stage = primaryStage
        val root = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".trading-journal")
        val dataDir = File(root, "TradingJournal")
        store = DesktopStore(dataDir)
        val view = WebView()
        engine = view.engine
        engine.userAgent = "TradingJournal/4.0 Windows JavaFX"
        engine.loadWorker.stateProperty().addListener { _, _, state ->
            if (state == Worker.State.SUCCEEDED) {
                bridge = DesktopBridge(store, engine, stage, executor, dataDir)
                (engine.executeScript("window") as JSObject).setMember("AndroidJournal", bridge)
                engine.executeScript("window.App && App.init && App.init()")
                bridge.startAutomaticSync()
            }
        }
        engine.load(javaClass.getResource("/index.html")!!.toExternalForm())
        primaryStage.title = "Trading Journal"
        primaryStage.scene = Scene(view, 1180.0, 820.0)
        runCatching { primaryStage.icons += Image(javaClass.getResourceAsStream("/app-icon-v25-master.png")) }
        primaryStage.show()
    }

    override fun stop() {
        executor.shutdownNow()
        store.closeQuietly()
    }
}

class DesktopBridge(
    private val store: DesktopStore,
    private val engine: WebEngine,
    private val stage: Stage,
    private val executor: ScheduledExecutorService,
    private val dataDir: File
) {
    private val pollingStarted = AtomicBoolean(false)

    fun startAutomaticSync() {
        if (store.state().connected) sync(interactive = false)
        if (pollingStarted.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(
                { if (store.state().connected) sync(interactive = false) },
                60,
                60,
                TimeUnit.SECONDS
            )
        }
    }
    fun initialize(): String = response { store.initialState().apply { remove("success") } }
    fun addTrade(raw: String): String = responseRaw { store.upsertTrade(JSONObject(raw), true) }.also { autoSync() }
    fun updateTrade(raw: String): String = responseRaw { store.upsertTrade(JSONObject(raw), false) }.also { autoSync() }
    fun deleteTrade(id: String): String = responseRaw { store.deleteTrade(id) }.also { autoSync() }
    fun duplicateTrade(id: String): String = responseRaw { store.duplicateTrade(id) }.also { autoSync() }
    fun saveSettings(raw: String): String = responseRaw { store.saveSettings(JSONObject(raw)) }.also { autoSync() }
    fun clearAllData(): String = responseRaw { store.clear() }.also { autoSync() }
    fun deleteAttachment(id: String): String = responseRaw { store.deleteAttachment(id) }.also { autoSync() }
    fun markNotificationsRead(): String = response { store.markNotificationsRead(); JSONObject() }

    fun connectGoogleDrive() = sync(interactive = true)
    fun syncNow() = sync(interactive = true)

    fun addAttachments(tradeId: String, kind: String) {
        val chooser = FileChooser().apply {
            title = "Add chart images"
            extensionFilters += FileChooser.ExtensionFilter("Chart images", "*.png", "*.jpg", "*.jpeg", "*.webp")
        }
        val selected = chooser.showOpenMultipleDialog(stage)?.take(6).orEmpty()
        runCatching {
            selected.forEachIndexed { position, source ->
                val bytes = source.readBytes()
                require(bytes.isNotEmpty() && bytes.size <= 20_000_000) { "Each image must be 20 MB or smaller" }
                val extension = source.extension.lowercase().let { if (it == "jpeg") "jpg" else it }
                require(extension in setOf("png", "jpg", "webp")) { "Only JPEG, PNG, and WebP images are supported" }
                val id = UUID.randomUUID().toString()
                val relative = "$tradeId/$id.$extension"
                val target = store.imageFile(relative)
                target.parentFile.mkdirs(); target.writeBytes(bytes)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val item = JSONObject().put("id", id).put("tradeId", tradeId).put("kind", kind)
                    .put("fileName", source.name.take(160)).put("mimeType", if (extension == "png") "image/png" else if (extension == "webp") "image/webp" else "image/jpeg")
                    .put("relativePath", relative).put("caption", "").put("position", position).put("sha256", hash)
                    .put("sizeBytes", bytes.size).put("createdAt", System.currentTimeMillis())
                store.addAttachment(item)
            }
        }.onSuccess { nativeEvent(true, if (selected.isEmpty()) "No images selected" else "Chart images added", true); autoSync() }
            .onFailure { nativeEvent(false, it.message ?: "Unable to add images", true) }
    }

    fun exportBackup() = saveText("TradingJournal-backup.json", "JSON", "*.json", backupJson().toString(2))
    fun exportCsv() = saveText("TradingJournal-trades.csv", "CSV", "*.csv", csv())
    fun exportPackage() {
        val chooser = FileChooser().apply { title = "Export complete package"; initialFileName = "TradingJournal-full.tpjbackup"; extensionFilters += FileChooser.ExtensionFilter("Trading Journal package", "*.tpjbackup") }
        chooser.showSaveDialog(stage)?.let { file ->
            runCatching { file.writeBytes(packageBytes()) }
                .onSuccess { nativeEvent(true, "Complete backup saved") }.onFailure { nativeEvent(false, it.message ?: "Export failed") }
        }
    }
    fun backupToGoogleDrive() {
        executor.submit {
            runCatching {
                val credential = DesktopGoogleAuth(dataDir).authorize()
                if (credential.accessToken.isNullOrBlank() || credential.expiresInSeconds?.let { it < 60 } == true) credential.refreshToken()
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
                DesktopDriveRest(credential.accessToken ?: error("Google authorization did not return an access token"))
                    .backupJournal(packageBytes(), "TradingJournal-$stamp.tpjbackup")
                store.updateState(connected = true, lastSyncAt = System.currentTimeMillis(), lastError = null)
                store.notify("backup", "Drive backup created", "A complete .tpjbackup file was saved in Trading Journal Backups.")
            }.onSuccess { nativeEvent(true, "Complete backup saved to Google Drive", true) }
                .onFailure { nativeEvent(false, it.message ?: "Drive backup failed", true) }
        }
    }
    fun importBackup(mode: String) {
        val chooser = FileChooser().apply { title = "Import JSON backup"; extensionFilters += FileChooser.ExtensionFilter("JSON backup", "*.json") }
        chooser.showOpenDialog(stage)?.let { file ->
            runCatching { store.importJournal(JSONObject(file.readText()), mode) }
                .onSuccess { nativeEvent(true, "Journal backup imported", true); autoSync() }
                .onFailure { nativeEvent(false, it.message ?: "Import failed") }
        }
    }
    fun importPackage(mode: String) {
        val chooser = FileChooser().apply { title = "Import complete package"; extensionFilters += FileChooser.ExtensionFilter("Trading Journal package", "*.tpjbackup") }
        chooser.showOpenDialog(stage)?.let { file ->
            val temporary = File(dataDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
            runCatching {
                var journal: JSONObject? = null
                var total = 0L
                ZipInputStream(file.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        require(!name.startsWith('/') && !name.contains("../")) { "Unsafe package path" }
                        if (!entry.isDirectory && name == "journal.json") {
                            val bytes = readLimited(zip, 25_000_000); total += bytes.size; journal = JSONObject(bytes.toString(Charsets.UTF_8))
                        } else if (!entry.isDirectory && name.startsWith("images/")) {
                            val relative = name.removePrefix("images/")
                            require(relative.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9-]+\\.(?:jpg|png|webp)"))) { "Unsafe image path" }
                            val target = safeFile(temporary, relative); target.parentFile.mkdirs()
                            val bytes = readLimited(zip, 20_000_000); total += bytes.size; target.writeBytes(bytes)
                        }
                        require(total <= 250_000_000) { "Backup package is larger than 250 MB" }
                        zip.closeEntry()
                    }
                }
                val root = journal ?: error("Backup package does not contain journal.json")
                store.importJournal(root, mode)
                val attachments = root.optJSONArray("attachments") ?: JSONArray()
                for (i in 0 until attachments.length()) {
                    val item = JSONObject(attachments.getJSONObject(i).toString())
                    if (!store.hasTrade(item.getString("tradeId")) || store.hasAttachment(item.getString("id"))) continue
                    val source = safeFile(temporary, item.getString("relativePath"))
                    require(source.isFile) { "Package is missing image: ${item.optString("fileName")}" }
                    store.installSyncedImage(item, source.readBytes())
                    store.addAttachment(item)
                }
            }.onSuccess { nativeEvent(true, "Complete backup restored", true); autoSync() }
                .onFailure { nativeEvent(false, it.message ?: "Package restore failed", true) }
            temporary.deleteRecursively()
        }
    }
    fun chooseBackupFolder() = nativeEvent(true, "Windows backups are saved wherever you choose during export")
    fun backupNow() = exportPackage()

    private fun sync(interactive: Boolean) {
        executor.submit {
            runCatching {
                val auth = DesktopGoogleAuth(dataDir)
                DesktopSync(store, auth).run()
            }.onSuccess { result ->
                nativeEvent(true, if (result.uploaded + result.changed == 0) "Journal is up to date" else "Sync complete: ${result.uploaded} sent, ${result.changed} received", true)
            }.onFailure { error ->
                store.updateState(lastError = error.message ?: "Sync failed", pendingAction = interactive)
                val full = error is DesktopDriveException && error.code == 403 && error.message.orEmpty().contains("storage", true)
                if (full) store.notify("error", "Google Drive storage is full", "Your journal is safe on this PC. Sync will resume after space is available.", true)
                nativeEvent(false, error.message ?: "Google Drive sync failed", true)
            }
        }
    }
    private fun autoSync() { if (store.state().connected) sync(interactive = false) }
    private fun backupJson() = store.initialState().let { state -> JSONObject().put("app", "trading-pnl-journal").put("version", 4).put("trades", state.getJSONArray("trades")).put("attachments", state.getJSONArray("attachments")).put("settings", state.getJSONObject("settings")) }
    private fun packageBytes(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("journal.json")); zip.write(backupJson().toString(2).toByteArray()); zip.closeEntry()
            store.allAttachments().forEach { item ->
                val image = store.imageFile(item.getString("relativePath"))
                if (image.isFile) { zip.putNextEntry(ZipEntry("images/${item.getString("relativePath")}")); image.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        }
        return output.toByteArray()
    }
    private fun csv(): String {
        val rows = store.initialState().getJSONArray("trades")
        return buildString {
            append("id,date,result,pnl,symbol,setup,session,notes\r\n")
            for (i in 0 until rows.length()) { val j = rows.getJSONObject(i); append(listOf("id","date","result","pnl","symbol","setup","session","notes").joinToString(",") { key -> csvValue(j.optString(key)) }); append("\r\n") }
        }
    }
    private fun csvValue(v: String) = "\"${v.replace("\"", "\"\"")}\""
    private fun saveText(name: String, label: String, pattern: String, text: String) {
        val chooser = FileChooser().apply { title = "Export $label"; initialFileName = name; extensionFilters += FileChooser.ExtensionFilter(label, pattern) }
        chooser.showSaveDialog(stage)?.let { runCatching { it.writeText(text) }.onSuccess { nativeEvent(true, "Export saved") }.onFailure { e -> nativeEvent(false, e.message ?: "Export failed") } }
    }
    private fun nativeEvent(success: Boolean, message: String, refresh: Boolean = false) = Platform.runLater {
        val payload = JSONObject().put("success", success).put("message", message).put("refresh", refresh)
        engine.executeScript("window.JournalStorage && JournalStorage.onNativeEvent(${JSONObject.quote(payload.toString())})")
    }
    private fun safeFile(root: File, relative: String): File {
        val candidate = File(root, relative).canonicalFile
        require(candidate.path.startsWith(root.canonicalPath + File.separator)) { "Unsafe package path" }
        return candidate
    }
    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= max) { "A package file is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun response(block: () -> JSONObject): String = try { block().put("success", true).toString() } catch (e: Exception) { JSONObject().put("success", false).put("error", e.message ?: "Operation failed").toString() }
    private fun responseRaw(block: () -> JSONObject): String = try { block().toString() } catch (e: Exception) { JSONObject().put("success", false).put("error", e.message ?: "Operation failed").toString() }
}

fun main() = Application.launch(TradingJournalDesktop::class.java)
