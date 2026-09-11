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

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var repository: JournalRepository
    private lateinit var backupManager: BackupManager
    private var exportKind = "json"
    private var importMode = "merge"

    private val createJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeManualExport(uri, "json")
    }
    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) writeManualExport(uri, "csv")
    }
    private val openImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBackup(uri, importMode)
    }
    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) saveBackupFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = JournalRepository(JournalDatabase.get(this))
        backupManager = BackupManager(this, repository, lifecycleScope, BuildConfig.VERSION_NAME, ::backupEvent)

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
        view.addJavascriptInterface(NativeBridge(this, repository, backupManager), "AndroidJournal")
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
            if (kind == "csv") createCsv.launch("TradingJournal-trades.csv")
            else createJson.launch("TradingJournal-backup.json")
        }
    }

    fun launchImport(mode: String) {
        importMode = mode
        runOnUiThread { openImport.launch(arrayOf("application/json", "text/json", "text/plain")) }
    }

    fun launchFolderPicker() = runOnUiThread { chooseFolder.launch(null) }

    private fun writeManualExport(uri: Uri, kind: String) {
        lifecycleScope.launch {
            runCatching {
                val text = if (kind == "csv") backupManager.createCsv() else backupManager.createJson()
                backupManager.writeText(uri, text)
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
        repository.delete(id)
        backupManager.scheduleAutoBackup()
        JSONObject()
    }

    @JavascriptInterface
    fun duplicateTrade(id: String): String = response {
        val duplicate = repository.duplicate(id)
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
        JSONObject()
    }

    @JavascriptInterface
    fun exportBackup() = activity.launchExport("json")

    @JavascriptInterface
    fun exportCsv() = activity.launchExport("csv")

    @JavascriptInterface
    fun importBackup(mode: String) {
        require(mode == "merge" || mode == "replace")
        activity.launchImport(mode)
    }

    @JavascriptInterface
    fun chooseBackupFolder() = activity.launchFolderPicker()

    @JavascriptInterface
    fun backupNow() = backupManager.backupNow()

    private fun stateJson(trades: List<TradeEntity>, settings: SettingsEntity): JSONObject {
        val array = JSONArray()
        trades.forEach { array.put(BackupCodec.tradeToJson(it)) }
        return JSONObject()
            .put("trades", array)
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
