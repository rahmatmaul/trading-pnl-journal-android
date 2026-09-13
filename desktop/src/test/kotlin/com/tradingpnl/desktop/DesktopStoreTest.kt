package com.tradingpnl.desktop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun localWritesAreDurableAndCreateTickets() {
        val root = temporary.newFolder("journal")
        DesktopStore(root).use { store ->
            val saved = store.upsertTrade(trade("one", 20.0, 10), true).getJSONObject("trade")
            store.saveSettings(JSONObject().put("theme", "dark"))
            assertEquals("one", saved.getString("id"))
            assertEquals(2, store.pending().size)
        }
        DesktopStore(root).use { reopened ->
            assertEquals(1, reopened.initialState().getJSONArray("trades").length())
            assertEquals("dark", reopened.settings().getString("theme"))
        }
    }

    @Test fun remoteTombstoneWinsOverAnOlderUpdate() {
        DesktopStore(temporary.newFolder("remote")).use { store ->
            val upsert = event("up", "upsert", 100, trade("remote", 9.0, 100))
            assertTrue(store.applyRemote(upsert))
            assertEquals(1, store.initialState().getJSONArray("trades").length())
            assertTrue(store.applyRemote(event("delete", "delete", 120, JSONObject().put("id", "remote"))))
            assertEquals(0, store.initialState().getJSONArray("trades").length())
            assertFalse(store.applyRemote(event("stale", "upsert", 110, trade("remote", 99.0, 110))))
            assertEquals(0, store.initialState().getJSONArray("trades").length())
        }
    }

    @Test fun androidCompatibleEnvelopeRoundTrips() {
        val original = event("shared-ticket", "upsert", 321, trade("shared", 7.5, 321))
        val parsed = DesktopEvent.parse(original.json().toString())
        assertEquals("trading-journal-sync", parsed.json().getString("protocol"))
        assertEquals("shared", parsed.entityId)
        assertEquals(7.5, parsed.payload.getDouble("pnl"), 0.0)
    }

    private fun event(eventId: String, operation: String, updated: Long, payload: JSONObject) = DesktopEvent(
        eventId, "android-device", "trade", payload.optString("id", "remote"), operation, updated, updated, payload
    )

    private fun trade(id: String, pnl: Double, updated: Long) = JSONObject()
        .put("id", id).put("date", "2026-09-13").put("timestamp", updated)
        .put("result", if (pnl >= 0) "win" else "loss").put("pnl", pnl).put("symbol", "XAUUSD")
        .put("notes", "").put("entryTime", "").put("setup", "").put("session", "")
        .put("emotion", "").put("mistakes", org.json.JSONArray()).put("plannedR", JSONObject.NULL)
        .put("realizedR", JSONObject.NULL).put("executionScore", JSONObject.NULL).put("reviewed", false)
        .put("createdAt", updated).put("updatedAt", updated)
}
