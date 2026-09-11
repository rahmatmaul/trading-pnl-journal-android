package com.tradingpnl.journal

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import java.util.UUID

@Entity(
    tableName = "trades",
    indices = [Index("date"), Index("timestamp"), Index("symbol"), Index("result")]
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
    val updatedAt: Long
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

@Database(
    entities = [TradeEntity::class, SettingsEntity::class],
    version = 1,
    exportSchema = false
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun tradeDao(): TradeDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile private var instance: JournalDatabase? = null

        fun get(context: Context): JournalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JournalDatabase::class.java,
                "trading-journal.db"
            )
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

    suspend fun initialize(): Pair<List<TradeEntity>, SettingsEntity> {
        val current = settings.get() ?: SettingsEntity().also { settings.upsert(it) }
        return trades.getAll() to current
    }

    suspend fun allTrades(): List<TradeEntity> = trades.getAll()
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
            createdAt = now,
            updatedAt = now
        )
        trades.insert(copy)
        return copy
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
        // Validate every value before entering the transaction that deletes current trades.
        val validatedTrades = backup.trades.map(TradeValidator::validate)
        val validatedSettings = backup.settings?.let(SettingsValidator::validate)
        return database.withTransaction {
            val unique = LinkedHashMap<String, TradeEntity>()
            validatedTrades.forEach { unique.putIfAbsent(it.id, it) }
            trades.deleteAll()
            if (unique.isNotEmpty()) trades.insertAll(unique.values.toList())
            if (validatedSettings != null) {
                val current = getSettings()
                settings.upsert(validatedSettings.copy(backupFolderUri = current.backupFolderUri))
            }
            ImportResult(unique.size, validatedTrades.size - unique.size, validatedTrades.size)
        }
    }

    suspend fun clearJournal() = database.withTransaction {
        val current = getSettings()
        trades.deleteAll()
        settings.upsert(SettingsEntity(backupFolderUri = current.backupFolderUri))
    }
}
