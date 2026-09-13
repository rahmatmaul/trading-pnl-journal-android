package com.tradingpnl.journal

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.util.UUID

@Entity(
    tableName = "trades",
    indices = [Index("date"), Index("timestamp"), Index("symbol"), Index("result"), Index("setup"), Index("session")]
)
data class TradeEntity(
    @PrimaryKey val id: String,
    val date: String,
    val timestamp: Long,
    val result: String,
    val pnl: Double,
    val symbol: String = "",
    val notes: String = "",
    val entryTime: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val setup: String = "",
    val session: String = "",
    val emotion: String = "",
    val mistakes: String = "",
    val plannedR: Double? = null,
    val realizedR: Double? = null,
    val executionScore: Int? = null,
    val reviewed: Boolean = false
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val theme: String = "system",
    val startingBalance: Double? = null,
    val profitTarget: Double? = null,
    val maxDrawdown: Double? = null,
    val dailyDrawdown: Double? = null,
    val backupFolderUri: String? = null,
    val updatedAt: Long = 0L
) {
    companion object { const val SINGLETON_ID = 1 }
}

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = TradeEntity::class,
        parentColumns = ["id"],
        childColumns = ["tradeId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tradeId"), Index(value = ["tradeId", "position"])]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val tradeId: String,
    val kind: String,
    val fileName: String,
    val mimeType: String,
    val relativePath: String,
    val caption: String = "",
    val position: Int = 0,
    val sha256: String,
    val sizeBytes: Long,
    val createdAt: Long
)

@Dao
interface TradeDao {
    @Query("SELECT * FROM trades ORDER BY timestamp DESC, id DESC")
    suspend fun getAll(): List<TradeEntity>

    @Query("SELECT * FROM trades WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TradeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(trade: TradeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(trades: List<TradeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(trade: TradeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFromSync(trade: TradeEntity)

    @Update
    suspend fun update(trade: TradeEntity): Int

    @Query("DELETE FROM trades WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM trades")
    suspend fun deleteAll()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    suspend fun get(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SettingsEntity)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments ORDER BY tradeId, position, createdAt")
    suspend fun getAll(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE tradeId = :tradeId ORDER BY position, createdAt")
    suspend fun forTrade(tradeId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFromSync(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Update
    suspend fun update(attachment: AttachmentEntity): Int

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM attachments")
    suspend fun deleteAll()
}

@Database(
    entities = [
        TradeEntity::class,
        SettingsEntity::class,
        AttachmentEntity::class,
        SyncOutboxEntity::class,
        SyncReceiptEntity::class,
        SyncVersionEntity::class,
        SyncStateEntity::class,
        JournalNotificationEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun tradeDao(): TradeDao
    abstract fun settingsDao(): SettingsDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile private var instance: JournalDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE trades ADD COLUMN setup TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE trades ADD COLUMN session TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE trades ADD COLUMN emotion TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE trades ADD COLUMN mistakes TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE trades ADD COLUMN plannedR REAL DEFAULT NULL")
                database.execSQL("ALTER TABLE trades ADD COLUMN realizedR REAL DEFAULT NULL")
                database.execSQL("ALTER TABLE trades ADD COLUMN executionScore INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE trades ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trades_setup ON trades(setup)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trades_session ON trades(session)")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS attachments (
                        id TEXT NOT NULL PRIMARY KEY,
                        tradeId TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        caption TEXT NOT NULL DEFAULT '',
                        position INTEGER NOT NULL DEFAULT 0,
                        sha256 TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(tradeId) REFERENCES trades(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_tradeId ON attachments(tradeId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_tradeId_position ON attachments(tradeId, position)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE settings ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("""CREATE TABLE IF NOT EXISTS sync_outbox (
                    eventId TEXT NOT NULL PRIMARY KEY,
                    entityType TEXT NOT NULL,
                    entityId TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    entityUpdatedAt INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    lastError TEXT
                )""".trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_createdAt ON sync_outbox(createdAt)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS sync_receipts (
                    eventId TEXT NOT NULL PRIMARY KEY,
                    appliedAt INTEGER NOT NULL
                )""".trimIndent())
                database.execSQL("""CREATE TABLE IF NOT EXISTS sync_versions (
                    entityType TEXT NOT NULL,
                    entityId TEXT NOT NULL,
                    eventId TEXT NOT NULL,
                    entityUpdatedAt INTEGER NOT NULL,
                    deleted INTEGER NOT NULL,
                    PRIMARY KEY(entityType, entityId)
                )""".trimIndent())
                database.execSQL("""CREATE TABLE IF NOT EXISTS sync_state (
                    id INTEGER NOT NULL PRIMARY KEY,
                    deviceId TEXT NOT NULL,
                    connected INTEGER NOT NULL DEFAULT 0,
                    accountEmail TEXT,
                    accountName TEXT,
                    accountPhotoUrl TEXT,
                    lastSyncAt INTEGER,
                    lastError TEXT,
                    pendingUserAction INTEGER NOT NULL DEFAULT 0
                )""".trimIndent())
                database.execSQL("""CREATE TABLE IF NOT EXISTS journal_notifications (
                    id TEXT NOT NULL PRIMARY KEY,
                    kind TEXT NOT NULL,
                    title TEXT NOT NULL,
                    message TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    read INTEGER NOT NULL DEFAULT 0,
                    persistent INTEGER NOT NULL DEFAULT 0
                )""".trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_journal_notifications_createdAt ON journal_notifications(createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_journal_notifications_read ON journal_notifications(read)")
            }
        }

        fun get(context: Context): JournalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JournalDatabase::class.java,
                "trading-journal.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // Intentionally no destructive migration fallback.
                .build()
                .also { instance = it }
        }
    }
}

data class ImportResult(val added: Int, val skipped: Int, val total: Int)

class JournalRepository(private val database: JournalDatabase) {
    private val trades = database.tradeDao()
    private val settings = database.settingsDao()
    private val attachments = database.attachmentDao()
    private val sync = database.syncDao()

    suspend fun initialize(): Pair<List<TradeEntity>, SettingsEntity> {
        val current = settings.get() ?: SettingsEntity().also { settings.upsert(it) }
        return trades.getAll() to current
    }

    suspend fun allTrades(): List<TradeEntity> = trades.getAll()
    suspend fun allAttachments(): List<AttachmentEntity> = attachments.getAll()
    suspend fun attachmentsForTrade(tradeId: String): List<AttachmentEntity> = attachments.forTrade(tradeId)
    suspend fun getAttachment(id: String): AttachmentEntity? = attachments.getById(id)
    suspend fun getSettings(): SettingsEntity = settings.get() ?: SettingsEntity().also { settings.upsert(it) }

    suspend fun getSyncState(): SyncStateEntity {
        sync.state()?.let { return it }
        return SyncStateEntity(deviceId = UUID.randomUUID().toString()).also { sync.saveState(it) }
    }
    suspend fun pendingSyncCount(): Int = sync.pendingCount()
    suspend fun pendingSync(limit: Int = 100): List<SyncOutboxEntity> = sync.pending(limit)
    suspend fun hasSyncReceipt(eventId: String): Boolean = sync.hasReceipt(eventId)

    suspend fun shouldApplyRemote(envelope: SyncEnvelope): Boolean {
        if (sync.hasReceipt(envelope.eventId)) return false
        return isRemoteVersionNewer(
            envelope.entityUpdatedAt,
            envelope.eventId,
            sync.version(envelope.entityType, envelope.entityId)
        )
    }
    suspend fun syncNotifications(): List<JournalNotificationEntity> = sync.notifications()

    suspend fun saveSyncState(transform: (SyncStateEntity) -> SyncStateEntity): SyncStateEntity {
        val next = transform(getSyncState()).copy(id = 1)
        sync.saveState(next)
        return next
    }

    suspend fun notify(kind: String, title: String, message: String, persistent: Boolean = false) {
        sync.saveNotification(
            JournalNotificationEntity(UUID.randomUUID().toString(), kind, title, message, System.currentTimeMillis(), persistent = persistent)
        )
    }

    suspend fun markNotificationsRead() = sync.markNotificationsRead()

    suspend fun add(trade: TradeEntity) {
        val checked = TradeValidator.validate(trade)
        database.withTransaction {
            trades.insert(checked)
            enqueue("trade", checked.id, "upsert", checked.updatedAt, BackupCodec.tradeToJson(checked))
        }
    }

    suspend fun update(trade: TradeEntity) {
        database.withTransaction {
            val old = trades.getById(trade.id) ?: error("Trade not found")
            val checked = TradeValidator.validate(trade).copy(createdAt = old.createdAt)
            check(trades.update(checked) == 1) { "Trade update was not committed" }
            enqueue("trade", checked.id, "upsert", checked.updatedAt, BackupCodec.tradeToJson(checked))
        }
    }

    suspend fun delete(id: String) {
        database.withTransaction {
            check(trades.getById(id) != null) { "Trade not found" }
            val attachmentIds = attachments.forTrade(id).map { it.id }
            val deletedAt = System.currentTimeMillis()
            check(trades.deleteById(id) == 1) { "Trade not found" }
            attachmentIds.forEach { attachmentId ->
                enqueue("attachment", attachmentId, "delete", deletedAt, JSONObject().put("id", attachmentId))
            }
            enqueue("trade", id, "delete", deletedAt, JSONObject().put("id", id))
        }
    }

    suspend fun duplicate(id: String): TradeEntity {
        return database.withTransaction {
            val source = trades.getById(id) ?: error("Trade not found")
            val now = System.currentTimeMillis()
            val copy = source.copy(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                reviewed = false,
                createdAt = now,
                updatedAt = now
            )
            trades.insert(copy)
            enqueue("trade", copy.id, "upsert", copy.updatedAt, BackupCodec.tradeToJson(copy))
            copy
        }
    }

    suspend fun addAttachment(attachment: AttachmentEntity) = database.withTransaction {
        val checked = AttachmentValidator.validate(attachment)
        attachments.insert(checked)
        enqueue("attachment", checked.id, "upsert", checked.createdAt, BackupCodec.attachmentToJson(checked))
    }

    suspend fun addAttachments(items: List<AttachmentEntity>) {
        if (items.isEmpty()) return
        database.withTransaction {
            val checked = items.map(AttachmentValidator::validate)
            attachments.insertAll(checked)
            checked.forEach { enqueue("attachment", it.id, "upsert", it.createdAt, BackupCodec.attachmentToJson(it)) }
        }
    }

    suspend fun updateAttachment(attachment: AttachmentEntity) = database.withTransaction {
        val checked = AttachmentValidator.validate(attachment)
        check(attachments.update(checked) == 1) { "Attachment not found" }
        enqueue("attachment", checked.id, "upsert", System.currentTimeMillis(), BackupCodec.attachmentToJson(checked))
    }

    suspend fun deleteAttachment(id: String) = database.withTransaction {
        check(attachments.deleteById(id) == 1) { "Attachment not found" }
        val now = System.currentTimeMillis()
        enqueue("attachment", id, "delete", now, JSONObject().put("id", id))
    }

    suspend fun saveSettings(next: SettingsEntity) {
        val now = System.currentTimeMillis()
        val checked = SettingsValidator.validate(next).copy(id = SettingsEntity.SINGLETON_ID, updatedAt = now)
        database.withTransaction {
            settings.upsert(checked)
            enqueue("settings", "journal", "upsert", checked.updatedAt, BackupCodec.settingsToJson(checked))
        }
    }

    suspend fun merge(backup: BackupData): ImportResult {
        val validatedTrades = backup.trades.map(TradeValidator::validate)
        val validatedSettings = backup.settings?.let(SettingsValidator::validate)
        return database.withTransaction {
            var added = 0
            var skipped = 0
            validatedTrades.forEach {
                if (trades.insertIgnore(it) == -1L) skipped++ else {
                    added++
                    enqueue("trade", it.id, "upsert", it.updatedAt, BackupCodec.tradeToJson(it))
                }
            }
            if (validatedSettings != null) {
                val current = getSettings()
                val restored = validatedSettings.copy(backupFolderUri = current.backupFolderUri, updatedAt = System.currentTimeMillis())
                settings.upsert(restored)
                enqueue("settings", "journal", "upsert", restored.updatedAt, BackupCodec.settingsToJson(restored))
            }
            ImportResult(added, skipped, validatedTrades.size)
        }
    }

    suspend fun replace(backup: BackupData): ImportResult {
        val validatedTrades = backup.trades.map(TradeValidator::validate)
        val validatedSettings = backup.settings?.let(SettingsValidator::validate)
        return database.withTransaction {
            val previousIds = trades.getAll().mapTo(HashSet()) { it.id }
            val previousAttachmentIds = attachments.getAll().map { it.id }
            val unique = LinkedHashMap<String, TradeEntity>()
            validatedTrades.forEach { unique.putIfAbsent(it.id, it) }
            attachments.deleteAll()
            trades.deleteAll()
            if (unique.isNotEmpty()) trades.insertAll(unique.values.toList())
            val deletedAt = System.currentTimeMillis()
            previousIds.filterNot { it in unique }.forEach { id ->
                enqueue("trade", id, "delete", deletedAt, JSONObject().put("id", id))
            }
            previousAttachmentIds.forEach { id ->
                enqueue("attachment", id, "delete", deletedAt, JSONObject().put("id", id))
            }
            unique.values.forEach { enqueue("trade", it.id, "upsert", it.updatedAt, BackupCodec.tradeToJson(it)) }
            if (validatedSettings != null) {
                val current = getSettings()
                val restored = validatedSettings.copy(backupFolderUri = current.backupFolderUri, updatedAt = System.currentTimeMillis())
                settings.upsert(restored)
                enqueue("settings", "journal", "upsert", restored.updatedAt, BackupCodec.settingsToJson(restored))
            }
            ImportResult(unique.size, validatedTrades.size - unique.size, validatedTrades.size)
        }
    }

    suspend fun importPackage(backup: BackupData, importedAttachments: List<AttachmentEntity>, mode: String): ImportResult {
        val validatedTrades = backup.trades.map(TradeValidator::validate)
        val validIds = validatedTrades.mapTo(HashSet()) { it.id }
        val validatedAttachments = importedAttachments.map(AttachmentValidator::validate).onEach {
            require(it.tradeId in validIds) { "Attachment references a missing trade" }
        }
        val validatedSettings = backup.settings?.let(SettingsValidator::validate)
        return database.withTransaction {
            val existingBeforeImport = trades.getAll().mapTo(HashSet()) { it.id }
            val result = if (mode == "replace") {
                val unique = LinkedHashMap<String, TradeEntity>()
                validatedTrades.forEach { unique.putIfAbsent(it.id, it) }
                val previousAttachmentIds = attachments.getAll().map { it.id }
                attachments.deleteAll()
                trades.deleteAll()
                if (unique.isNotEmpty()) trades.insertAll(unique.values.toList())
                val now = System.currentTimeMillis()
                existingBeforeImport.filterNot { it in unique }.forEach { id ->
                    enqueue("trade", id, "delete", now, JSONObject().put("id", id))
                }
                previousAttachmentIds.forEach { id ->
                    enqueue("attachment", id, "delete", now, JSONObject().put("id", id))
                }
                unique.values.forEach { enqueue("trade", it.id, "upsert", it.updatedAt, BackupCodec.tradeToJson(it)) }
                val acceptedIds = unique.keys
                val uniqueAttachments = LinkedHashMap<String, AttachmentEntity>()
                validatedAttachments.filter { it.tradeId in acceptedIds }.forEach { uniqueAttachments.putIfAbsent(it.id, it) }
                if (uniqueAttachments.isNotEmpty()) {
                    attachments.insertAll(uniqueAttachments.values.toList())
                    uniqueAttachments.values.forEach {
                        enqueue("attachment", it.id, "upsert", it.createdAt, BackupCodec.attachmentToJson(it))
                    }
                }
                ImportResult(unique.size, validatedTrades.size - unique.size, validatedTrades.size)
            } else {
                var added = 0
                var skipped = 0
                val acceptedTradeIds = HashSet<String>()
                validatedTrades.forEach {
                    if (trades.insertIgnore(it) == -1L) skipped++ else {
                        added++
                        acceptedTradeIds += it.id
                        enqueue("trade", it.id, "upsert", it.updatedAt, BackupCodec.tradeToJson(it))
                    }
                }
                val acceptedAttachments = validatedAttachments.filter { it.tradeId in acceptedTradeIds }
                if (acceptedAttachments.isNotEmpty()) {
                    val uniqueAttachments = acceptedAttachments.distinctBy { it.id }
                    attachments.insertAll(uniqueAttachments)
                    uniqueAttachments.forEach {
                        enqueue("attachment", it.id, "upsert", it.createdAt, BackupCodec.attachmentToJson(it))
                    }
                }
                ImportResult(added, skipped, validatedTrades.size)
            }
            if (validatedSettings != null) {
                val current = getSettings()
                val restored = validatedSettings.copy(backupFolderUri = current.backupFolderUri, updatedAt = System.currentTimeMillis())
                settings.upsert(restored)
                enqueue("settings", "journal", "upsert", restored.updatedAt, BackupCodec.settingsToJson(restored))
            }
            result
        }
    }

    suspend fun clearJournal() = database.withTransaction {
        val current = getSettings()
        val ids = trades.getAll().map { it.id }
        val attachmentIds = attachments.getAll().map { it.id }
        val now = System.currentTimeMillis()
        attachments.deleteAll()
        trades.deleteAll()
        ids.forEach { id -> enqueue("trade", id, "delete", now, JSONObject().put("id", id)) }
        attachmentIds.forEach { id -> enqueue("attachment", id, "delete", now, JSONObject().put("id", id)) }
        val reset = SettingsEntity(backupFolderUri = current.backupFolderUri, updatedAt = now)
        settings.upsert(reset)
        enqueue("settings", "journal", "upsert", now, BackupCodec.settingsToJson(reset))
    }

    suspend fun ensureBootstrapEvents() = database.withTransaction {
        val state = getSyncState()
        trades.getAll().forEach { trade ->
            if (sync.version("trade", trade.id) == null) {
                enqueue("trade", trade.id, "upsert", trade.updatedAt, BackupCodec.tradeToJson(trade), state.deviceId)
            }
        }
        attachments.getAll().forEach { attachment ->
            if (sync.version("attachment", attachment.id) == null) {
                enqueue(
                    "attachment",
                    attachment.id,
                    "upsert",
                    attachment.createdAt,
                    BackupCodec.attachmentToJson(attachment),
                    state.deviceId
                )
            }
        }
        val current = getSettings()
        if (sync.version("settings", "journal") == null) {
            val stamped = if (current.updatedAt > 0) current else current.copy(updatedAt = System.currentTimeMillis())
            if (stamped != current) settings.upsert(stamped)
            enqueue("settings", "journal", "upsert", stamped.updatedAt, BackupCodec.settingsToJson(stamped), state.deviceId)
        }
    }

    suspend fun markUploaded(eventId: String) = database.withTransaction {
        sync.addReceipt(SyncReceiptEntity(eventId, System.currentTimeMillis()))
        sync.removePending(eventId)
    }

    suspend fun recordSyncFailure(eventId: String, message: String) = sync.recordFailure(eventId, message.take(500))

    suspend fun applyRemote(envelope: SyncEnvelope): Boolean = database.withTransaction {
        if (sync.hasReceipt(envelope.eventId)) return@withTransaction false
        val localVersion = sync.version(envelope.entityType, envelope.entityId)
        var changed = false
        if (isRemoteVersionNewer(envelope.entityUpdatedAt, envelope.eventId, localVersion)) {
            when (envelope.entityType) {
                "trade" -> if (envelope.operation == "delete") {
                    trades.deleteById(envelope.entityId)
                    changed = true
                } else {
                    val incoming = BackupCodec.tradeFromJson(envelope.payload)
                        .copy(id = envelope.entityId, updatedAt = envelope.entityUpdatedAt)
                    trades.upsertFromSync(TradeValidator.validate(incoming))
                    changed = true
                }
                "settings" -> if (envelope.operation == "upsert") {
                    val local = getSettings()
                    val incoming = BackupCodec.settingsFromJson(envelope.payload).copy(
                        id = SettingsEntity.SINGLETON_ID,
                        backupFolderUri = local.backupFolderUri,
                        updatedAt = envelope.entityUpdatedAt
                    )
                    settings.upsert(SettingsValidator.validate(incoming))
                    changed = true
                }
                "attachment" -> if (envelope.operation == "delete") {
                    attachments.deleteById(envelope.entityId)
                    changed = true
                } else {
                    val incoming = BackupCodec.attachmentFromJson(envelope.payload).copy(id = envelope.entityId)
                    attachments.upsertFromSync(AttachmentValidator.validate(incoming))
                    changed = true
                }
            }
            sync.saveVersion(envelope.versionRow())
        }
        sync.addReceipt(SyncReceiptEntity(envelope.eventId, System.currentTimeMillis()))
        changed
    }

    private suspend fun enqueue(
        entityType: String,
        entityId: String,
        operation: String,
        entityUpdatedAt: Long,
        payload: JSONObject,
        knownDeviceId: String? = null
    ) {
        val envelope = SyncEnvelope.local(
            deviceId = knownDeviceId ?: getSyncState().deviceId,
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            entityUpdatedAt = entityUpdatedAt.coerceAtLeast(1L),
            payload = payload
        )
        sync.enqueue(envelope.outbox())
        sync.saveVersion(envelope.versionRow())
    }
}
