package com.tradingpnl.journal

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

open class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var repository: JournalRepository
    private lateinit var attachmentStore: AttachmentStore
    private lateinit var backupManager: BackupManager
    private var exportKind = "json"
    private var importMode = "merge"
    private var pendingAttachmentTradeId: String? = null
    private var pendingAttachmentKind = "other"

    private val createJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeManualExport(uri, "json")
    }
    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) writeManualExport(uri, "csv")
    }
    private val createPackage = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) writeManualExport(uri, "package")
    }
    private val openImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBackup(uri, importMode)
    }
    private val openPackageImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPackage(uri, importMode)
    }
    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) saveBackupFolder(uri)
    }
    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        val tradeId = pendingAttachmentTradeId
        pendingAttachmentTradeId = null
        if (tradeId != null && uris.isNotEmpty()) importImages(tradeId, pendingAttachmentKind, uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = JournalRepository(JournalDatabase.get(this))
        attachmentStore = AttachmentStore(this, repository)
        backupManager = BackupManager(this, repository, attachmentStore, lifecycleScope, BuildConfig.VERSION_NAME, ::backupEvent)

        webView = WebView(this)
        webView.setBackgroundColor(if (isSystemDark()) Color.rgb(16, 19, 24) else Color.rgb(242, 245, 249))
        configureWebView(webView)
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.App && App.handleBack ? App.handleBack() : false") { handled ->
                    if (handled != "true" && handled != "\"true\"") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled") // Required by the bundled, network-blocked application UI.
    private fun configureWebView(view: WebView) {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/trade-images/", WebViewAssetLoader.InternalStoragePathHandler(this, attachmentStore.rootDir))
            .build()
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkLoads = true
            setSupportMultipleWindows(false)
        }
        view.addJavascriptInterface(NativeBridge(this, repository, attachmentStore, backupManager), "AndroidJournal")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return blocked()
                return if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net") {
                    loader.shouldInterceptRequest(uri) ?: blocked()
                } else blocked()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return !(uri.scheme == "https" && uri.host == "appassets.androidplatform.net")
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    fun launchExport(kind: String) {
        exportKind = kind
        runOnUiThread {
            when (kind) {
                "csv" -> createCsv.launch("TradingJournal-trades.csv")
                "package" -> createPackage.launch("TradingJournal-full.tpjbackup")
                else -> createJson.launch("TradingJournal-backup.json")
            }
        }
    }

    fun launchImport(mode: String) {
        importMode = mode
        runOnUiThread { openImport.launch(arrayOf("application/json", "text/json", "text/plain")) }
    }

    fun launchPackageImport(mode: String) {
        importMode = mode
        runOnUiThread { openPackageImport.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
    }

    fun launchAttachmentPicker(tradeId: String, kind: String) = runOnUiThread {
        pendingAttachmentTradeId = tradeId
        pendingAttachmentKind = kind
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun launchFolderPicker() = runOnUiThread { chooseFolder.launch(null) }

    private fun writeManualExport(uri: Uri, kind: String) {
        lifecycleScope.launch {
            runCatching {
                if (kind == "package") backupManager.writePackage(uri)
                else {
                    val text = if (kind == "csv") backupManager.createCsv() else backupManager.createJson()
                    backupManager.writeText(uri, text)
                }
            }.onSuccess { nativeEvent("export", true, "Export saved") }
                .onFailure { nativeEvent("export", false, it.message ?: "Export failed") }
        }
    }

    private fun importBackup(uri: Uri, mode: String) {
        lifecycleScope.launch {
            runCatching {
                val raw = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val value = reader.readText()
                        require(value.length <= 25_000_000) { "Backup file is too large" }
                        value
                    } ?: error("Unable to read the selected backup")
                }
                val backup = withContext(Dispatchers.Default) { BackupCodec.parseJson(raw) }
                val result = withContext(Dispatchers.IO) {
                    if (mode == "replace") repository.replace(backup) else repository.merge(backup)
                }
                backupManager.scheduleAutoBackup()
                result
            }.onSuccess { result ->
                nativeEvent(
                    "import",
                    true,
                    "Import complete: ${result.added} added, ${result.skipped} skipped",
                    refresh = true
                )
            }.onFailure { nativeEvent("import", false, it.message ?: "Import failed") }
        }
    }

    private fun importPackage(uri: Uri, mode: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val staged = contentResolver.openInputStream(uri)?.use { BackupPackageCodec.read(it, cacheDir) }
                        ?: error("Unable to read the selected backup package")
                    try {
                        val existingIds = repository.allTrades().mapTo(HashSet()) { it.id }
                        val acceptedIds = if (mode == "replace") staged.backup.trades.mapTo(HashSet()) { it.id }
                        else staged.backup.trades.filterNot { it.id in existingIds }.mapTo(HashSet()) { it.id }
                        val oldAttachments = if (mode == "replace") repository.allAttachments() else emptyList()
                        val installed = BackupPackageCodec.installImages(staged, acceptedIds, attachmentStore)
                        try {
                            val result = repository.importPackage(staged.backup, installed, mode)
                            if (mode == "replace") attachmentStore.deleteFiles(oldAttachments)
                            result
                        } catch (error: Exception) {
                            attachmentStore.deleteFiles(installed)
                            throw error
                        }
                    } finally {
                        staged.directory.deleteRecursively()
                    }
                }
            }.onSuccess { result ->
                backupManager.scheduleAutoBackup()
                nativeEvent("import", true, "Package import complete: ${result.added} added, ${result.skipped} skipped", refresh = true)
            }.onFailure { nativeEvent("import", false, it.message ?: "Package import failed") }
        }
    }

    private fun importImages(tradeId: String, kind: String, uris: List<Uri>) {
        lifecycleScope.launch {
            runCatching { attachmentStore.importUris(tradeId, kind, uris) }
                .onSuccess { items ->
                    backupManager.scheduleAutoBackup()
                    nativeEvent("attachments", true, "${items.size} chart image${if (items.size == 1) "" else "s"} added", refresh = true)
                }
                .onFailure { nativeEvent("attachments", false, it.message ?: "Image import failed") }
        }
    }

    private fun saveBackupFolder(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                withContext(Dispatchers.IO) {
                    val current = repository.getSettings()
                    repository.saveSettings(current.copy(backupFolderUri = uri.toString()))
                }
                backupManager.backupNow()
            }.onSuccess {
                nativeEvent("folder", true, "Backup folder selected", refresh = true)
            }.onFailure { nativeEvent("folder", false, it.message ?: "Could not save folder access") }
        }
    }

    private fun backupEvent(success: Boolean, message: String) = nativeEvent("backup", success, message, refresh = success)

    private fun nativeEvent(type: String, success: Boolean, message: String, refresh: Boolean = false) {
        val payload = JSONObject()
            .put("type", type)
            .put("success", success)
            .put("message", message)
            .put("refresh", refresh)
        runOnUiThread {
            webView.evaluateJavascript("window.JournalStorage && JournalStorage.onNativeEvent(${JSONObject.quote(payload.toString())})", null)
        }
    }

    private fun isSystemDark(): Boolean = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onDestroy() {
        webView.removeJavascriptInterface("AndroidJournal")
        webView.destroy()
        super.onDestroy()
    }
}

class NativeBridge(
    private val activity: MainActivity,
    private val repository: JournalRepository,
    private val attachmentStore: AttachmentStore,
    private val backupManager: BackupManager
) {
    @JavascriptInterface
    fun initialize(): String = response {
        val (trades, settings) = repository.initialize()
        stateJson(trades, settings)
    }

    @JavascriptInterface
    fun addTrade(raw: String): String = response {
        val trade = parseTrade(JSONObject(raw), isNew = true)
        repository.add(trade)
        backupManager.scheduleAutoBackup()
        JSONObject().put("trade", BackupCodec.tradeToJson(trade))
    }

    @JavascriptInterface
    fun updateTrade(raw: String): String = response {
        val trade = parseTrade(JSONObject(raw), isNew = false)
        repository.update(trade)
        backupManager.scheduleAutoBackup()
        JSONObject().put("trade", BackupCodec.tradeToJson(trade))
    }

    @JavascriptInterface
    fun deleteTrade(id: String): String = response {
        val files = repository.attachmentsForTrade(id)
        repository.delete(id)
        attachmentStore.deleteFiles(files)
        backupManager.scheduleAutoBackup()
        JSONObject()
    }

    @JavascriptInterface
    fun duplicateTrade(id: String): String = response {
        val duplicate = repository.duplicate(id)
        attachmentStore.duplicate(id, duplicate.id)
        backupManager.scheduleAutoBackup()
        JSONObject().put("trade", BackupCodec.tradeToJson(duplicate))
    }

    @JavascriptInterface
    fun saveSettings(raw: String): String = response {
        val json = JSONObject(raw)
        val old = repository.getSettings()
        val next = SettingsEntity(
            theme = json.optString("theme", old.theme),
            startingBalance = json.optionalDouble("startingBalance"),
            profitTarget = json.optionalDouble("profitTarget"),
            maxDrawdown = json.optionalDouble("maxDrawdown"),
            dailyDrawdown = json.optionalDouble("dailyDrawdown"),
            backupFolderUri = old.backupFolderUri
        )
        repository.saveSettings(next)
        backupManager.scheduleAutoBackup()
        JSONObject().put("settings", BackupCodec.settingsToJson(next))
    }

    @JavascriptInterface
    fun clearAllData(): String = response {
        repository.clearJournal()
        attachmentStore.deleteAllFiles()
        JSONObject()
    }

    @JavascriptInterface
    fun exportBackup() = activity.launchExport("json")

    @JavascriptInterface
    fun exportCsv() = activity.launchExport("csv")

    @JavascriptInterface
    fun exportPackage() = activity.launchExport("package")

    @JavascriptInterface
    fun importBackup(mode: String) {
        require(mode == "merge" || mode == "replace")
        activity.launchImport(mode)
    }

    @JavascriptInterface
    fun importPackage(mode: String) {
        require(mode == "merge" || mode == "replace")
        activity.launchPackageImport(mode)
    }

    @JavascriptInterface
    fun addAttachments(tradeId: String, kind: String) {
        require(kind in setOf("before", "after", "other"))
        activity.launchAttachmentPicker(tradeId, kind)
    }

    @JavascriptInterface
    fun deleteAttachment(id: String): String = response {
        attachmentStore.delete(id)
        backupManager.scheduleAutoBackup()
        JSONObject()
    }

    @JavascriptInterface
    fun updateAttachment(raw: String): String = response {
        val json = JSONObject(raw)
        val current = repository.getAttachment(json.getString("id")) ?: error("Attachment not found")
        val updated = current.copy(
            kind = json.optString("kind", current.kind),
            caption = json.optString("caption", current.caption),
            position = json.optInt("position", current.position)
        )
        repository.updateAttachment(updated)
        backupManager.scheduleAutoBackup()
        JSONObject().put("attachment", BackupCodec.attachmentToJson(updated))
    }

    @JavascriptInterface
    fun chooseBackupFolder() = activity.launchFolderPicker()

    @JavascriptInterface
    fun backupNow() = backupManager.backupNow()

    private suspend fun stateJson(trades: List<TradeEntity>, settings: SettingsEntity): JSONObject {
        val array = JSONArray()
        trades.forEach { array.put(BackupCodec.tradeToJson(it)) }
        val attachmentArray = JSONArray()
        repository.allAttachments().forEach { attachment ->
            attachmentArray.put(
                BackupCodec.attachmentToJson(attachment)
                    .put("url", "https://appassets.androidplatform.net/trade-images/${attachment.relativePath}")
            )
        }
        return JSONObject()
            .put("trades", array)
            .put("attachments", attachmentArray)
            .put("settings", BackupCodec.settingsToJson(settings))
            .put("backupFolder", backupManager.folderName(settings.backupFolderUri) ?: JSONObject.NULL)
            .put("storage", "Room / SQLite")
    }

    private fun parseTrade(json: JSONObject, isNew: Boolean): TradeEntity {
        val now = System.currentTimeMillis()
        return TradeValidator.validate(
            TradeEntity(
                id = json.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                date = json.getString("date"),
                timestamp = if (isNew) now else json.optLong("timestamp", now),
                result = json.getString("result").lowercase(),
                pnl = json.getDouble("pnl"),
                symbol = json.optString("symbol", ""),
                notes = json.optString("notes", ""),
                entryTime = json.optString("entryTime", ""),
                setup = json.optString("setup", ""),
                session = json.optString("session", ""),
                emotion = json.optString("emotion", ""),
                mistakes = when (val value = json.opt("mistakes")) {
                    is JSONArray -> (0 until value.length()).joinToString("|") { value.optString(it) }
                    else -> json.optString("mistakes", "")
                },
                plannedR = json.optionalDouble("plannedR"),
                realizedR = json.optionalDouble("realizedR"),
                executionScore = if (!json.has("executionScore") || json.isNull("executionScore") || json.optString("executionScore").isBlank()) null else json.getInt("executionScore"),
                reviewed = json.optBoolean("reviewed", false),
                createdAt = json.optLong("createdAt", now),
                updatedAt = now
            )
        )
    }

    private fun response(block: suspend () -> JSONObject): String = try {
        val data = runBlocking(Dispatchers.IO) { block() }
        data.put("success", true).toString()
    } catch (error: Exception) {
        JSONObject().put("success", false).put("error", error.message ?: "Database operation failed").toString()
    }

    private fun JSONObject.optionalDouble(key: String): Double? {
        if (!has(key) || isNull(key) || optString(key).isBlank()) return null
        return getDouble(key)
    }
}
