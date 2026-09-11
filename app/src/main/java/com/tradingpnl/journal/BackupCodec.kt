package com.tradingpnl.journal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class BackupData(
    val trades: List<TradeEntity>,
    val settings: SettingsEntity?
)

object TradeValidator {
    fun validate(trade: TradeEntity): TradeEntity {
        require(trade.id.isNotBlank()) { "Trade ID is required" }
        try { LocalDate.parse(trade.date) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid trade date: ${trade.date}")
        }
        require(trade.result == "win" || trade.result == "loss") { "Result must be win or loss" }
        require(trade.pnl.isFinite()) { "PnL must be a finite number" }
        require(trade.symbol.length <= 40) { "Symbol is longer than 40 characters" }
        require(trade.notes.length <= 1000) { "Notes are longer than 1000 characters" }
        require(trade.entryTime.length <= 20) { "Entry time is longer than 20 characters" }
        val signed = if (trade.result == "win") kotlin.math.abs(trade.pnl) else -kotlin.math.abs(trade.pnl)
        return trade.copy(
            pnl = signed,
            symbol = trade.symbol.trim(),
            notes = trade.notes.trim(),
            entryTime = trade.entryTime.trim()
        )
    }
}

object SettingsValidator {
    fun validate(settings: SettingsEntity): SettingsEntity {
        require(settings.theme in setOf("system", "light", "dark")) { "Invalid theme" }
        listOf(
            "Starting balance" to settings.startingBalance,
            "Profit target" to settings.profitTarget,
            "Max drawdown" to settings.maxDrawdown,
            "Daily drawdown" to settings.dailyDrawdown
        ).forEach { (label, value) ->
            require(value == null || (value.isFinite() && value >= 0.0)) { "$label must be zero or greater" }
        }
        return settings
    }
}

object BackupCodec {
    const val APP_ID = "trading-pnl-journal"

    fun exportJson(trades: List<TradeEntity>, settings: SettingsEntity, appVersion: String): String {
        val root = JSONObject()
            .put("app", APP_ID)
            .put("version", 1)
            .put("schemaVersion", 1)
            .put("appVersion", appVersion)
            .put("exportedAt", Instant.now().toString())
            .put("settings", settingsToJson(settings))
        val array = JSONArray()
        trades.sortedWith(compareBy<TradeEntity> { it.timestamp }.thenBy { it.id }).forEach {
            array.put(tradeToJson(it))
        }
        root.put("trades", array)
        return root.toString(2)
    }

    fun parseJson(raw: String): BackupData {
        try {
            val root = JSONObject(raw)
            if (root.has("app")) require(root.getString("app") == APP_ID) { "This is not a Trading PnL Journal backup" }
            require(root.has("trades")) { "Backup does not contain trades" }
            val array = root.getJSONArray("trades")
            val parsed = ArrayList<TradeEntity>(array.length())
            for (index in 0 until array.length()) {
                try {
                    parsed += parseTrade(array.getJSONObject(index))
                } catch (error: Exception) {
                    throw IllegalArgumentException("Invalid trade at item ${index + 1}: ${error.message}")
                }
            }
            val importedSettings = if (root.has("settings") && !root.isNull("settings")) {
                parseSettings(root.getJSONObject("settings"))
            } else null
            return BackupData(parsed, importedSettings)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Malformed JSON backup", error)
        }
    }

    fun exportCsv(trades: List<TradeEntity>): String = buildString {
        append("id,date,entryTime,result,pnl,symbol,notes\r\n")
        trades.sortedWith(compareBy<TradeEntity> { it.timestamp }.thenBy { it.id }).forEach { trade ->
            append(listOf(
                trade.id,
                trade.date,
                trade.entryTime,
                trade.result,
                trade.pnl.toString(),
                trade.symbol,
                trade.notes
            ).joinToString(",") { csvEscape(it) })
            append("\r\n")
        }
    }

    private fun parseTrade(json: JSONObject): TradeEntity {
        val date = json.getString("date")
        val timestamp = parseTimestamp(json.opt("timestamp"), date)
        val now = System.currentTimeMillis()
        return TradeValidator.validate(
            TradeEntity(
                id = json.getString("id"),
                date = date,
                timestamp = timestamp,
                result = json.getString("result").lowercase(),
                pnl = json.getDouble("pnl"),
                symbol = json.optString("symbol", ""),
                notes = json.optString("notes", ""),
                entryTime = json.optString("entryTime", json.optString("entry_time", "")),
                createdAt = json.optLong("createdAt", json.optLong("created_at", timestamp.takeIf { it > 0 } ?: now)),
                updatedAt = json.optLong("updatedAt", json.optLong("updated_at", now))
            )
        )
    }

    private fun parseSettings(json: JSONObject): SettingsEntity {
        val parsed = SettingsEntity(
            theme = json.optString("theme", "system"),
            startingBalance = optionalNumber(json, "startingBalance"),
            profitTarget = optionalNumber(json, "profitTarget"),
            maxDrawdown = optionalNumber(json, "maxDrawdown"),
            dailyDrawdown = optionalNumber(json, "dailyDrawdown")
        )
        return SettingsValidator.validate(parsed)
    }

    private fun parseTimestamp(value: Any?, date: String): Long {
        val derived = try {
            LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) { 0L }
        return when (value) {
            is Number -> value.toLong().takeIf { it > 0 } ?: derived
            is String -> value.toLongOrNull()?.takeIf { it > 0 }
                ?: try { Instant.parse(value).toEpochMilli() } catch (_: Exception) { derived }
            else -> derived
        }
    }

    private fun optionalNumber(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key) || json.optString(key).isBlank()) return null
        return json.getDouble(key)
    }

    fun tradeToJson(trade: TradeEntity): JSONObject = JSONObject()
        .put("id", trade.id)
        .put("date", trade.date)
        .put("timestamp", trade.timestamp)
        .put("result", trade.result)
        .put("pnl", trade.pnl)
        .put("symbol", trade.symbol)
        .put("notes", trade.notes)
        .put("entryTime", trade.entryTime)

    fun settingsToJson(settings: SettingsEntity): JSONObject = JSONObject()
        .put("theme", settings.theme)
        .put("startingBalance", settings.startingBalance ?: JSONObject.NULL)
        .put("profitTarget", settings.profitTarget ?: JSONObject.NULL)
        .put("maxDrawdown", settings.maxDrawdown ?: JSONObject.NULL)
        .put("dailyDrawdown", settings.dailyDrawdown ?: JSONObject.NULL)

    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
