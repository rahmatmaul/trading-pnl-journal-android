package com.tradingpnl.journal

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONObject
import java.util.UUID

@Entity(tableName = "sync_outbox", indices = [Index("createdAt")])
data class SyncOutboxEntity(
    @PrimaryKey val eventId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val entityUpdatedAt: Long,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null
)

@Entity(tableName = "sync_receipts")
data class SyncReceiptEntity(
    @PrimaryKey val eventId: String,
    val appliedAt: Long
)

@Entity(tableName = "sync_versions", primaryKeys = ["entityType", "entityId"])
data class SyncVersionEntity(
    val entityType: String,
    val entityId: String,
    val eventId: String,
    val entityUpdatedAt: Long,
    val deleted: Boolean
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val deviceId: String,
    val connected: Boolean = false,
    val accountEmail: String? = null,
    val accountName: String? = null,
    val accountPhotoUrl: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val pendingUserAction: Boolean = false
)

@Entity(tableName = "journal_notifications", indices = [Index("createdAt"), Index("read")])
data class JournalNotificationEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val message: String,
    val createdAt: Long,
    val read: Boolean = false,
    val persistent: Boolean = false
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_outbox ORDER BY createdAt, eventId LIMIT :limit")
    suspend fun pending(limit: Int = 100): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun pendingCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(item: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE eventId = :eventId")
    suspend fun removePending(eventId: String)

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastError = :message WHERE eventId = :eventId")
    suspend fun recordFailure(eventId: String, message: String)

    @Query("SELECT EXISTS(SELECT 1 FROM sync_receipts WHERE eventId = :eventId)")
    suspend fun hasReceipt(eventId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addReceipt(item: SyncReceiptEntity)

    @Query("SELECT * FROM sync_versions WHERE entityType = :entityType AND entityId = :entityId LIMIT 1")
    suspend fun version(entityType: String, entityId: String): SyncVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveVersion(item: SyncVersionEntity)

    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    suspend fun state(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(item: SyncStateEntity)

    @Query("SELECT * FROM journal_notifications ORDER BY createdAt DESC LIMIT :limit")
    suspend fun notifications(limit: Int = 100): List<JournalNotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNotification(item: JournalNotificationEntity)

    @Query("UPDATE journal_notifications SET read = 1")
    suspend fun markNotificationsRead()
}

data class SyncEnvelope(
    val eventId: String,
    val deviceId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val entityUpdatedAt: Long,
    val createdAt: Long,
    val payload: JSONObject
) {
    fun toJson(): JSONObject = JSONObject()
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

    fun outbox(): SyncOutboxEntity = SyncOutboxEntity(
        eventId = eventId,
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        payload = payload.toString(),
        entityUpdatedAt = entityUpdatedAt,
        createdAt = createdAt
    )

    fun versionRow(): SyncVersionEntity = SyncVersionEntity(
        entityType = entityType,
        entityId = entityId,
        eventId = eventId,
        entityUpdatedAt = entityUpdatedAt,
        deleted = operation == "delete"
    )

    companion object {
        fun local(
            deviceId: String,
            entityType: String,
            entityId: String,
            operation: String,
            entityUpdatedAt: Long,
            payload: JSONObject
        ): SyncEnvelope {
            require(entityType in setOf("trade", "settings", "attachment")) { "Unsupported sync entity" }
            require(operation in setOf("upsert", "delete")) { "Unsupported sync operation" }
            val now = System.currentTimeMillis()
            return SyncEnvelope(
                eventId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                entityUpdatedAt = entityUpdatedAt,
                createdAt = now,
                payload = payload
            )
        }

        fun parse(raw: String): SyncEnvelope {
            val json = JSONObject(raw)
            require(json.optString("protocol") == "trading-journal-sync") { "Unknown sync payload" }
            require(json.optInt("version") == 1) { "Unsupported sync protocol" }
            val envelope = SyncEnvelope(
                eventId = json.getString("eventId"),
                deviceId = json.getString("deviceId"),
                entityType = json.getString("entityType"),
                entityId = json.getString("entityId"),
                operation = json.getString("operation"),
                entityUpdatedAt = json.getLong("entityUpdatedAt"),
                createdAt = json.getLong("createdAt"),
                payload = json.optJSONObject("payload") ?: JSONObject()
            )
            require(envelope.eventId.isNotBlank() && envelope.deviceId.isNotBlank()) { "Invalid sync identity" }
            require(envelope.entityType in setOf("trade", "settings", "attachment")) { "Unsupported sync entity" }
            require(envelope.operation in setOf("upsert", "delete")) { "Unsupported sync operation" }
            require(envelope.entityUpdatedAt > 0 && envelope.createdAt > 0) { "Invalid sync timestamp" }
            return envelope
        }

        fun fromOutbox(deviceId: String, item: SyncOutboxEntity): SyncEnvelope = SyncEnvelope(
            eventId = item.eventId,
            deviceId = deviceId,
            entityType = item.entityType,
            entityId = item.entityId,
            operation = item.operation,
            entityUpdatedAt = item.entityUpdatedAt,
            createdAt = item.createdAt,
            payload = JSONObject(item.payload)
        )
    }
}

internal fun isRemoteVersionNewer(remoteAt: Long, remoteEventId: String, local: SyncVersionEntity?): Boolean {
    if (local == null) return true
    return remoteAt > local.entityUpdatedAt ||
        (remoteAt == local.entityUpdatedAt && remoteEventId > local.eventId)
}
