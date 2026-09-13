package com.tradingpnl.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.security.MessageDigest
import java.util.UUID

data class DesktopSyncState(
    val deviceId: String,
    val connected: Boolean,
    val email: String?,
    val name: String?,
    val lastSyncAt: Long?,
    val lastError: String?,
    val pendingUserAction: Boolean
)

data class DesktopEvent(
    val eventId: String,
    val deviceId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val entityUpdatedAt: Long,
    val createdAt: Long,
    val payload: JSONObject
) {
    fun json() = JSONObject()
        .put("protocol", "trading-journal-sync")
        .put("version", 1)
        .put("eventId", eventId)
        .put("deviceId", deviceId)
        .put("entityType", entityType)
        .put("entityId", entityId)
        .put("operation", operation)
        .put("entityUpdatedAt", entityUpdatedAt)
        .put("createdAt", createdAt)
        .put("payload", payload)

    companion object {
        fun parse(raw: String): DesktopEvent {
            val j = JSONObject(raw)
            require(j.getString("protocol") == "trading-journal-sync" && j.getInt("version") == 1)
            return DesktopEvent(
                j.getString("eventId"), j.getString("deviceId"), j.getString("entityType"),
                j.getString("entityId"), j.getString("operation"), j.getLong("entityUpdatedAt"),
                j.getLong("createdAt"), j.optJSONObject("payload") ?: JSONObject()
            ).also {
                require(it.entityType in setOf("trade", "settings", "attachment"))
                require(it.operation in setOf("upsert", "delete"))
            }
        }
    }
}

class DesktopStore(private val dataDir: File) : AutoCloseable {
    private val connection: Connection
    val imageDir = File(dataDir, "trade_images").apply { mkdirs() }

    init {
        dataDir.mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${File(dataDir, "journal.db").absolutePath}")
        connection.createStatement().use {
            it.execute("PRAGMA foreign_keys=ON")
            it.execute("CREATE TABLE IF NOT EXISTS trades(id TEXT PRIMARY KEY,json TEXT NOT NULL,updated_at INTEGER NOT NULL)")
            it.execute("CREATE TABLE IF NOT EXISTS settings(id INTEGER PRIMARY KEY CHECK(id=1),json TEXT NOT NULL,updated_at INTEGER NOT NULL)")
            it.execute("CREATE TABLE IF NOT EXISTS attachments(id TEXT PRIMARY KEY,trade_id TEXT NOT NULL,json TEXT NOT NULL,updated_at INTEGER NOT NULL)")
            it.execute("CREATE TABLE IF NOT EXISTS outbox(event_id TEXT PRIMARY KEY,entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,operation TEXT NOT NULL,payload TEXT NOT NULL,entity_updated_at INTEGER NOT NULL,created_at INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,last_error TEXT)")
            it.execute("CREATE TABLE IF NOT EXISTS receipts(event_id TEXT PRIMARY KEY,applied_at INTEGER NOT NULL)")
            it.execute("CREATE TABLE IF NOT EXISTS versions(entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,event_id TEXT NOT NULL,entity_updated_at INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(entity_type,entity_id))")
            it.execute("CREATE TABLE IF NOT EXISTS sync_state(id INTEGER PRIMARY KEY CHECK(id=1),device_id TEXT NOT NULL,connected INTEGER NOT NULL DEFAULT 0,email TEXT,name TEXT,last_sync_at INTEGER,last_error TEXT,pending_action INTEGER NOT NULL DEFAULT 0)")
            it.execute("CREATE TABLE IF NOT EXISTS notifications(id TEXT PRIMARY KEY,kind TEXT NOT NULL,title TEXT NOT NULL,message TEXT NOT NULL,created_at INTEGER NOT NULL,read INTEGER NOT NULL DEFAULT 0,persistent INTEGER NOT NULL DEFAULT 0)")
        }
        if (one("SELECT COUNT(*) FROM settings") { it.getInt(1) } == 0) {
            val now = System.currentTimeMillis()
            execute("INSERT INTO settings(id,json,updated_at) VALUES(1,?,?)", defaultSettings(now).toString(), now)
        }
        if (one("SELECT COUNT(*) FROM sync_state") { it.getInt(1) } == 0) {
            execute("INSERT INTO sync_state(id,device_id) VALUES(1,?)", UUID.randomUUID().toString())
        }
    }

    @Synchronized fun initialState(): JSONObject = JSONObject()
        .put("success", true)
        .put("trades", array("SELECT json FROM trades ORDER BY updated_at DESC"))
        .put("attachments", JSONArray().also { out ->
            connection.createStatement().executeQuery("SELECT json FROM attachments ORDER BY updated_at").use { rs ->
                while (rs.next()) {
                    val item = JSONObject(rs.getString(1))
                    val local = imageFile(item.getString("relativePath"))
                    item.put("url", local.toURI().toString())
                    out.put(item)
                }
            }
        })
        .put("settings", settings())
        .put("backupFolder", JSONObject.NULL)
        .put("storage", "SQLite on Windows")
        .put("appVersion", "4.0.0")
        .put("sync", syncJson())
        .put("notifications", notifications())

    @Synchronized fun upsertTrade(input: JSONObject, isNew: Boolean): JSONObject = transaction {
        val now = System.currentTimeMillis()
        val item = JSONObject(input.toString())
        val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
        item.put("id", id).put("timestamp", if (isNew) now else item.optLong("timestamp", now))
            .put("createdAt", if (isNew) now else item.optLong("createdAt", now)).put("updatedAt", now)
        execute("INSERT INTO trades(id,json,updated_at) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET json=excluded.json,updated_at=excluded.updated_at", id, item.toString(), now)
        enqueue("trade", id, "upsert", now, item)
        JSONObject().put("success", true).put("trade", item)
    }

    @Synchronized fun deleteTrade(id: String): JSONObject = transaction {
        val now = System.currentTimeMillis()
        val attachmentIds = strings("SELECT id FROM attachments WHERE trade_id=?", id)
        execute("DELETE FROM attachments WHERE trade_id=?", id)
        execute("DELETE FROM trades WHERE id=?", id)
        attachmentIds.forEach { enqueue("attachment", it, "delete", now, JSONObject().put("id", it)) }
        enqueue("trade", id, "delete", now, JSONObject().put("id", id))
        cleanupOrphanImages()
        ok()
    }

    @Synchronized fun duplicateTrade(id: String): JSONObject {
        val source = objectJson("SELECT json FROM trades WHERE id=?", id) ?: error("Trade not found")
        source.remove("id")
        return upsertTrade(source, true)
    }

    @Synchronized fun addAttachment(item: JSONObject): JSONObject = transaction {
        val id = item.getString("id")
        val updated = item.optLong("createdAt", System.currentTimeMillis()).coerceAtLeast(1)
        execute("INSERT INTO attachments(id,trade_id,json,updated_at) VALUES(?,?,?,?)", id, item.getString("tradeId"), item.toString(), updated)
        enqueue("attachment", id, "upsert", updated, item)
        JSONObject().put("success", true).put("attachment", item)
    }

    @Synchronized fun deleteAttachment(id: String): JSONObject = transaction {
        val item = objectJson("SELECT json FROM attachments WHERE id=?", id) ?: error("Attachment not found")
        execute("DELETE FROM attachments WHERE id=?", id)
        enqueue("attachment", id, "delete", System.currentTimeMillis(), JSONObject().put("id", id))
        imageFile(item.getString("relativePath")).delete()
        ok()
    }

    @Synchronized fun saveSettings(input: JSONObject): JSONObject = transaction {
        val now = System.currentTimeMillis()
        val item = JSONObject(input.toString()).put("updatedAt", now)
        execute("UPDATE settings SET json=?,updated_at=? WHERE id=1", item.toString(), now)
        enqueue("settings", "journal", "upsert", now, item)
        JSONObject().put("success", true).put("settings", item)
    }

    @Synchronized fun clear(): JSONObject = transaction {
        val now = System.currentTimeMillis()
        strings("SELECT id FROM attachments").forEach { enqueue("attachment", it, "delete", now, JSONObject().put("id", it)) }
        strings("SELECT id FROM trades").forEach { enqueue("trade", it, "delete", now, JSONObject().put("id", it)) }
        execute("DELETE FROM attachments")
        execute("DELETE FROM trades")
        val reset = defaultSettings(now)
        execute("UPDATE settings SET json=?,updated_at=? WHERE id=1", reset.toString(), now)
        enqueue("settings", "journal", "upsert", now, reset)
        cleanupOrphanImages()
        ok()
    }

    @Synchronized fun importJournal(root: JSONObject, mode: String): JSONObject = transaction {
        require(mode == "merge" || mode == "replace") { "Invalid import mode" }
        val incoming = root.getJSONArray("trades")
        val now = System.currentTimeMillis()
        if (mode == "replace") {
            strings("SELECT id FROM attachments").forEach { enqueue("attachment", it, "delete", now, JSONObject().put("id", it)) }
            strings("SELECT id FROM trades").forEach { enqueue("trade", it, "delete", now, JSONObject().put("id", it)) }
            execute("DELETE FROM attachments")
            execute("DELETE FROM trades")
        }
        var added = 0
        var skipped = 0
        for (i in 0 until incoming.length()) {
            val item = JSONObject(incoming.getJSONObject(i).toString())
            val id = item.getString("id")
            val exists = one("SELECT EXISTS(SELECT 1 FROM trades WHERE id=?)", id) { it.getInt(1) != 0 }
            if (exists && mode == "merge") { skipped++; continue }
            val updated = item.optLong("updatedAt", item.optLong("timestamp", now)).coerceAtLeast(1)
            item.put("updatedAt", updated)
            execute("INSERT INTO trades(id,json,updated_at) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET json=excluded.json,updated_at=excluded.updated_at", id, item.toString(), updated)
            enqueue("trade", id, "upsert", updated, item)
            added++
        }
        if (root.has("settings") && !root.isNull("settings")) {
            val item = JSONObject(root.getJSONObject("settings").toString()).put("updatedAt", now)
            execute("UPDATE settings SET json=?,updated_at=? WHERE id=1", item.toString(), now)
            enqueue("settings", "journal", "upsert", now, item)
        }
        cleanupOrphanImages()
        JSONObject().put("success", true).put("added", added).put("skipped", skipped)
    }

    @Synchronized fun hasTrade(id: String): Boolean = one("SELECT EXISTS(SELECT 1 FROM trades WHERE id=?)", id) { it.getInt(1) != 0 }
    @Synchronized fun hasAttachment(id: String): Boolean = one("SELECT EXISTS(SELECT 1 FROM attachments WHERE id=?)", id) { it.getInt(1) != 0 }

    @Synchronized fun settings(): JSONObject = objectJson("SELECT json FROM settings WHERE id=1") ?: defaultSettings(1)

    @Synchronized fun state(): DesktopSyncState = one("SELECT device_id,connected,email,name,last_sync_at,last_error,pending_action FROM sync_state WHERE id=1") {
        DesktopSyncState(it.getString(1), it.getInt(2) != 0, it.getString(3), it.getString(4),
            it.getLong(5).takeUnless { _ -> it.wasNull() }, it.getString(6), it.getInt(7) != 0)
    }

    @Synchronized fun updateState(connected: Boolean? = null, email: String? = null, name: String? = null,
                                  lastSyncAt: Long? = null, lastError: String? = null, pendingAction: Boolean? = null) {
        val old = state()
        execute("UPDATE sync_state SET connected=?,email=?,name=?,last_sync_at=?,last_error=?,pending_action=? WHERE id=1",
            bool(connected ?: old.connected), email ?: old.email, name ?: old.name, lastSyncAt ?: old.lastSyncAt,
            lastError, bool(pendingAction ?: old.pendingUserAction))
    }

    @Synchronized fun bootstrap() = transaction {
        val device = state().deviceId
        listObjects("SELECT json FROM trades").forEach { item ->
            if (!hasVersion("trade", item.getString("id"))) enqueue("trade", item.getString("id"), "upsert", item.optLong("updatedAt", 1), item, device)
        }
        listObjects("SELECT json FROM attachments").forEach { item ->
            if (!hasVersion("attachment", item.getString("id"))) enqueue("attachment", item.getString("id"), "upsert", item.optLong("createdAt", 1), item, device)
        }
        val s = settings()
        if (!hasVersion("settings", "journal")) enqueue("settings", "journal", "upsert", s.optLong("updatedAt", 1), s, device)
    }

    @Synchronized fun pending(limit: Int = 500): List<DesktopEvent> {
        val device = state().deviceId
        val out = ArrayList<DesktopEvent>()
        connection.prepareStatement("SELECT event_id,entity_type,entity_id,operation,payload,entity_updated_at,created_at FROM outbox ORDER BY created_at,event_id LIMIT ?").use { st ->
            st.setInt(1, limit)
            st.executeQuery().use { rs -> while (rs.next()) out += DesktopEvent(rs.getString(1), device, rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(6), rs.getLong(7), JSONObject(rs.getString(5))) }
        }
        return out
    }

    @Synchronized fun hasReceipt(id: String) = one("SELECT EXISTS(SELECT 1 FROM receipts WHERE event_id=?)", id) { it.getInt(1) != 0 }

    @Synchronized fun shouldApply(e: DesktopEvent): Boolean {
        if (hasReceipt(e.eventId)) return false
        return connection.prepareStatement("SELECT event_id,entity_updated_at FROM versions WHERE entity_type=? AND entity_id=?").use { st ->
            st.setString(1, e.entityType); st.setString(2, e.entityId)
            st.executeQuery().use { rs -> !rs.next() || e.entityUpdatedAt > rs.getLong(2) || (e.entityUpdatedAt == rs.getLong(2) && e.eventId > rs.getString(1)) }
        }
    }

    @Synchronized fun applyRemote(e: DesktopEvent): Boolean = transaction {
        if (hasReceipt(e.eventId)) return@transaction false
        val apply = shouldApply(e)
        if (apply) {
            when (e.entityType) {
                "trade" -> if (e.operation == "delete") execute("DELETE FROM trades WHERE id=?", e.entityId) else execute("INSERT INTO trades(id,json,updated_at) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET json=excluded.json,updated_at=excluded.updated_at", e.entityId, e.payload.toString(), e.entityUpdatedAt)
                "settings" -> if (e.operation == "upsert") execute("UPDATE settings SET json=?,updated_at=? WHERE id=1", e.payload.toString(), e.entityUpdatedAt)
                "attachment" -> if (e.operation == "delete") execute("DELETE FROM attachments WHERE id=?", e.entityId) else execute("INSERT INTO attachments(id,trade_id,json,updated_at) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET trade_id=excluded.trade_id,json=excluded.json,updated_at=excluded.updated_at", e.entityId, e.payload.getString("tradeId"), e.payload.toString(), e.entityUpdatedAt)
            }
            saveVersion(e)
        }
        execute("INSERT OR IGNORE INTO receipts(event_id,applied_at) VALUES(?,?)", e.eventId, System.currentTimeMillis())
        apply
    }

    @Synchronized fun markUploaded(id: String) = transaction {
        execute("INSERT OR IGNORE INTO receipts(event_id,applied_at) VALUES(?,?)", id, System.currentTimeMillis())
        execute("DELETE FROM outbox WHERE event_id=?", id)
    }

    @Synchronized fun syncJson(): JSONObject {
        val s = state()
        val pending = one("SELECT COUNT(*) FROM outbox") { it.getInt(1) }
        return JSONObject().put("connected", s.connected).put("email", s.email ?: JSONObject.NULL)
            .put("name", s.name ?: JSONObject.NULL).put("lastSyncAt", s.lastSyncAt ?: JSONObject.NULL)
            .put("lastError", s.lastError ?: JSONObject.NULL).put("pendingUserAction", s.pendingUserAction).put("pending", pending)
    }

    @Synchronized fun notify(kind: String, title: String, message: String, persistent: Boolean = false) {
        execute("INSERT INTO notifications(id,kind,title,message,created_at,read,persistent) VALUES(?,?,?,?,?,0,?)",
            UUID.randomUUID().toString(), kind, title, message.take(500), System.currentTimeMillis(), bool(persistent))
    }

    @Synchronized fun notifications(): JSONArray = JSONArray().also { out ->
        connection.createStatement().executeQuery("SELECT id,kind,title,message,created_at,read,persistent FROM notifications ORDER BY created_at DESC LIMIT 100").use { rs ->
            while (rs.next()) out.put(JSONObject().put("id", rs.getString(1)).put("kind", rs.getString(2)).put("title", rs.getString(3)).put("message", rs.getString(4)).put("createdAt", rs.getLong(5)).put("read", rs.getInt(6) != 0).put("persistent", rs.getInt(7) != 0))
        }
    }

    @Synchronized fun markNotificationsRead() = execute("UPDATE notifications SET read=1")
    fun imageFile(relative: String): File {
        val file = File(imageDir, relative).canonicalFile
        require(file.path.startsWith(imageDir.canonicalPath + File.separator)) { "Unsafe image path" }
        return file
    }
    @Synchronized fun allAttachments() = listObjects("SELECT json FROM attachments")
    fun installSyncedImage(item: JSONObject, bytes: ByteArray) {
        require(bytes.size.toLong() == item.getLong("sizeBytes") && bytes.size <= 20_000_000) { "Synced image size does not match" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == item.getString("sha256").lowercase()) { "Synced image checksum does not match" }
        val target = imageFile(item.getString("relativePath"))
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, ".${item.getString("id")}.sync")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Unable to install synced image" }
    }
    @Synchronized fun cleanupOrphanImages() {
        val keep = allAttachments().mapTo(HashSet()) { imageFile(it.getString("relativePath")).canonicalPath }
        imageDir.walkTopDown().filter(File::isFile).filter { it.canonicalPath !in keep }.forEach(File::delete)
    }
    @Synchronized fun closeQuietly() = connection.close()
    override fun close() = closeQuietly()

    private fun enqueue(type: String, id: String, operation: String, updated: Long, payload: JSONObject, knownDevice: String? = null) {
        val e = DesktopEvent(UUID.randomUUID().toString(), knownDevice ?: state().deviceId, type, id, operation, updated.coerceAtLeast(1), System.currentTimeMillis(), payload)
        execute("INSERT INTO outbox(event_id,entity_type,entity_id,operation,payload,entity_updated_at,created_at) VALUES(?,?,?,?,?,?,?)", e.eventId, type, id, operation, payload.toString(), e.entityUpdatedAt, e.createdAt)
        saveVersion(e)
    }
    private fun saveVersion(e: DesktopEvent) = execute("INSERT INTO versions(entity_type,entity_id,event_id,entity_updated_at,deleted) VALUES(?,?,?,?,?) ON CONFLICT(entity_type,entity_id) DO UPDATE SET event_id=excluded.event_id,entity_updated_at=excluded.entity_updated_at,deleted=excluded.deleted", e.entityType, e.entityId, e.eventId, e.entityUpdatedAt, bool(e.operation == "delete"))
    private fun hasVersion(type: String, id: String) = one("SELECT EXISTS(SELECT 1 FROM versions WHERE entity_type=? AND entity_id=?)", type, id) { it.getInt(1) != 0 }
    private fun defaultSettings(now: Long) = JSONObject().put("theme", "system").put("startingBalance", JSONObject.NULL).put("profitTarget", JSONObject.NULL).put("maxDrawdown", JSONObject.NULL).put("dailyDrawdown", JSONObject.NULL).put("updatedAt", now)
    private fun ok() = JSONObject().put("success", true)
    private fun bool(value: Boolean) = if (value) 1 else 0
    private fun array(sql: String) = JSONArray().also { out -> listObjects(sql).forEach(out::put) }
    private fun listObjects(sql: String): List<JSONObject> = ArrayList<JSONObject>().also { out -> connection.createStatement().executeQuery(sql).use { rs -> while (rs.next()) out += JSONObject(rs.getString(1)) } }
    private fun strings(sql: String, vararg args: Any?): List<String> = ArrayList<String>().also { out -> connection.prepareStatement(sql).use { st -> args.forEachIndexed { i, v -> st.setObject(i + 1, v) }; st.executeQuery().use { rs -> while (rs.next()) out += rs.getString(1) } } }
    private fun objectJson(sql: String, vararg args: Any?): JSONObject? = connection.prepareStatement(sql).use { st -> args.forEachIndexed { i, v -> st.setObject(i + 1, v) }; st.executeQuery().use { if (it.next()) JSONObject(it.getString(1)) else null } }
    private fun execute(sql: String, vararg args: Any?): Int = connection.prepareStatement(sql).use { st -> args.forEachIndexed { i, v -> st.setObject(i + 1, v) }; st.executeUpdate() }
    private fun <T> one(sql: String, vararg args: Any?, read: (java.sql.ResultSet) -> T): T = connection.prepareStatement(sql).use { st -> args.forEachIndexed { i, v -> st.setObject(i + 1, v) }; st.executeQuery().use { check(it.next()); read(it) } }
    private fun <T> transaction(block: () -> T): T {
        val old = connection.autoCommit; connection.autoCommit = false
        return try { block().also { connection.commit() } } catch (e: Exception) { connection.rollback(); throw e } finally { connection.autoCommit = old }
    }
}
