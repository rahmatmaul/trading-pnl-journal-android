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
    val backupFolderUri: String? = null
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
    entities = [TradeEntity::class, SettingsEntity::class, AttachmentEntity::class],
    version = 4,
    exportSchema = false
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun tradeDao(): TradeDao
    abstract fun settingsDao(): SettingsDao
    abstract fun attachmentDao(): AttachmentDao

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

        // V3 briefly contained optional cloud-sync tables. V4.0.1 removes them
        // while preserving the local journal, images, settings, and backup folder.
        val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(database: SupportSQLiteDatabase) = Unit
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS settings_offline (
                        id INTEGER NOT NULL PRIMARY KEY,
                        theme TEXT NOT NULL,
                        startingBalance REAL,
                        profitTarget REAL,
                        maxDrawdown REAL,
                        dailyDrawdown REAL,
                        backupFolderUri TEXT
                    )""".trimIndent()
                )
                database.execSQL(
                    """INSERT OR REPLACE INTO settings_offline
                        (id, theme, startingBalance, profitTarget, maxDrawdown, dailyDrawdown, backupFolderUri)
                        SELECT id, theme, startingBalance, profitTarget, maxDrawdown, dailyDrawdown, backupFolderUri
                        FROM settings""".trimIndent()
                )
                database.execSQL("DROP TABLE settings")
                database.execSQL("ALTER TABLE settings_offline RENAME TO settings")
                database.execSQL("DROP TABLE IF EXISTS sync_outbox")
                database.execSQL("DROP TABLE IF EXISTS sync_receipts")
                database.execSQL("DROP TABLE IF EXISTS sync_versions")
                database.execSQL("DROP TABLE IF EXISTS sync_state")
                database.execSQL("DROP TABLE IF EXISTS journal_notifications")
            }
        }

        fun get(context: Context): JournalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JournalDatabase::class.java,
                "trading-journal.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_4, MIGRATION_3_4)
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

    suspend fun initialize(): Pair<List<TradeEntity>, SettingsEntity> {
        val current = settings.get() ?: SettingsEntity().also { settings.upsert(it) }
        return trades.getAll() to current
    }

    suspend fun allTrades(): List<TradeEntity> = trades.getAll()
    suspend fun allAttachments(): List<AttachmentEntity> = attachments.getAll()
    suspend fun attachmentsForTrade(tradeId: String): List<AttachmentEntity> = attachments.forTrade(tradeId)
    suspend fun getAttachment(id: String): AttachmentEntity? = attachments.getById(id)
    suspend fun getSettings(): SettingsEntity = settings.get() ?: SettingsEntity().also { settings.upsert(it) }

    suspend fun add(trade: TradeEntity) {
        trades.insert(TradeValidator.validate(trade))
    }

    suspend fun update(trade: TradeEntity) {
        val old = trades.getById(trade.id) ?: error("Trade not found")
        val checked = TradeValidator.validate(trade).copy(createdAt = old.createdAt)
        check(trades.update(checked) == 1) { "Trade update was not committed" }
    }

    suspend fun delete(id: String) {
        check(trades.deleteById(id) == 1) { "Trade not found" }
    }

    suspend fun duplicate(id: String): TradeEntity {
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
        return copy
    }

    suspend fun addAttachment(attachment: AttachmentEntity) = attachments.insert(AttachmentValidator.validate(attachment))

    suspend fun addAttachments(items: List<AttachmentEntity>) {
        if (items.isNotEmpty()) attachments.insertAll(items.map(AttachmentValidator::validate))
    }

    suspend fun updateAttachment(attachment: AttachmentEntity) {
        check(attachments.update(AttachmentValidator.validate(attachment)) == 1) { "Attachment not found" }
    }

    suspend fun deleteAttachment(id: String) {
        check(attachments.deleteById(id) == 1) { "Attachment not found" }
    }

    suspend fun saveSettings(next: SettingsEntity) {
        SettingsValidator.validate(next)
        settings.upsert(next.copy(id = SettingsEntity.SINGLETON_ID))
    }

    suspend fun merge(backup: BackupData): ImportResult {
        val validatedTrades = backup.trades.map(TradeValidator::validate)
        val validatedSettings = backup.settings?.let(SettingsValidator::validate)
        return database.withTransaction {
            var added = 0
            var skipped = 0
            validatedTrades.forEach {
                if (trades.insertIgnore(it) == -1L) skipped++ else added++
            }
            if (validatedSettings != null) {
                val current = getSettings()
                settings.upsert(validatedSettings.copy(backupFolderUri = current.backupFolderUri))
            }
            ImportResult(added, skipped, validatedTrades.size)
        }
    }

    suspend fun replace(backup: BackupData): ImportResult {
        val validatedTrades = backup.trades.map(TradeValidator::validate)
        val validatedSettings = backup.settings?.let(SettingsValidator::validate)
        return database.withTransaction {
            val unique = LinkedHashMap<String, TradeEntity>()
            validatedTrades.forEach { unique.putIfAbsent(it.id, it) }
            attachments.deleteAll()
            trades.deleteAll()
            if (unique.isNotEmpty()) trades.insertAll(unique.values.toList())
            if (validatedSettings != null) {
                val current = getSettings()
                settings.upsert(validatedSettings.copy(backupFolderUri = current.backupFolderUri))
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
            val result = if (mode == "replace") {
                val unique = LinkedHashMap<String, TradeEntity>()
                validatedTrades.forEach { unique.putIfAbsent(it.id, it) }
                attachments.deleteAll()
                trades.deleteAll()
                if (unique.isNotEmpty()) trades.insertAll(unique.values.toList())
                val acceptedIds = unique.keys
                val uniqueAttachments = LinkedHashMap<String, AttachmentEntity>()
                validatedAttachments.filter { it.tradeId in acceptedIds }.forEach { uniqueAttachments.putIfAbsent(it.id, it) }
                if (uniqueAttachments.isNotEmpty()) attachments.insertAll(uniqueAttachments.values.toList())
                ImportResult(unique.size, validatedTrades.size - unique.size, validatedTrades.size)
            } else {
                var added = 0
                var skipped = 0
                val acceptedTradeIds = HashSet<String>()
                validatedTrades.forEach {
                    if (trades.insertIgnore(it) == -1L) skipped++ else {
                        added++
                        acceptedTradeIds += it.id
                    }
                }
                val acceptedAttachments = validatedAttachments.filter { it.tradeId in acceptedTradeIds }
                if (acceptedAttachments.isNotEmpty()) attachments.insertAll(acceptedAttachments.distinctBy { it.id })
                ImportResult(added, skipped, validatedTrades.size)
            }
            if (validatedSettings != null) {
                val current = getSettings()
                settings.upsert(validatedSettings.copy(backupFolderUri = current.backupFolderUri))
            }
            result
        }
    }

    suspend fun clearJournal() = database.withTransaction {
        val current = getSettings()
        attachments.deleteAll()
        trades.deleteAll()
        settings.upsert(SettingsEntity(backupFolderUri = current.backupFolderUri))
    }
}
