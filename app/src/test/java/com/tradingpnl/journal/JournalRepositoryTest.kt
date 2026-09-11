package com.tradingpnl.journal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class JournalRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: JournalDatabase
    private lateinit var repository: JournalRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).build()
        repository = JournalRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun addUpdateDeleteAndDuplicateAreCommitted() = runBlocking {
        val original = trade("one", "win", 120.0)
        repository.add(original)
        assertEquals(1, repository.allTrades().size)

        repository.update(original.copy(pnl = 175.0, notes = "updated"))
        assertEquals(175.0, repository.allTrades().single().pnl, 0.0)
        assertEquals("updated", repository.allTrades().single().notes)

        val duplicate = repository.duplicate(original.id)
        assertNotEquals(original.id, duplicate.id)
        assertEquals(2, repository.allTrades().size)

        repository.delete(original.id)
        assertEquals(listOf(duplicate.id), repository.allTrades().map { it.id })
    }

    @Test
    fun dataAndSettingsSurviveDatabaseReopen() = runBlocking {
        database.close()
        val name = "reopen-${UUID.randomUUID()}.db"
        var diskDb = Room.databaseBuilder(context, JournalDatabase::class.java, name).build()
        var diskRepo = JournalRepository(diskDb)
        diskRepo.add(trade("persisted", "loss", 42.0))
        diskRepo.saveSettings(SettingsEntity(theme = "dark", startingBalance = 10_000.0))
        diskDb.close()

        diskDb = Room.databaseBuilder(context, JournalDatabase::class.java, name).build()
        diskRepo = JournalRepository(diskDb)
        assertEquals(-42.0, diskRepo.allTrades().single().pnl, 0.0)
        assertEquals("dark", diskRepo.getSettings().theme)
        assertEquals(10_000.0, diskRepo.getSettings().startingBalance!!, 0.0)
        diskDb.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun winAndLossSignsAreNormalized() {
        assertEquals(25.0, TradeValidator.validate(trade("w", "win", -25.0)).pnl, 0.0)
        assertEquals(-25.0, TradeValidator.validate(trade("l", "loss", 25.0)).pnl, 0.0)
    }

    @Test
    fun invalidTradeFieldsAreRejected() {
        assertFails { TradeValidator.validate(trade("x", "win", 1.0).copy(date = "2026-13-45")) }
        assertFails { TradeValidator.validate(trade("x", "breakeven", 1.0)) }
        assertFails { TradeValidator.validate(trade("x", "win", Double.NaN)) }
    }

    @Test
    fun exportedJsonRoundTripsLegacyShape() {
        val settings = SettingsEntity(theme = "dark", startingBalance = 5_000.0, dailyDrawdown = 250.0)
        val raw = BackupCodec.exportJson(listOf(trade("a", "win", 10.0)), settings, "test")
        val parsed = BackupCodec.parseJson(raw)
        assertEquals("a", parsed.trades.single().id)
        assertEquals(10.0, parsed.trades.single().pnl, 0.0)
        assertEquals("dark", parsed.settings?.theme)
        assertTrue(raw.contains("\"app\": \"trading-pnl-journal\""))
    }

    @Test
    fun isoAndMissingLegacyTimestampsAreAccepted() {
        val iso = """{"app":"trading-pnl-journal","version":1,"trades":[{"id":"a","date":"2026-09-10","timestamp":"2026-09-10T02:00:00Z","result":"win","pnl":1}],"settings":{}}"""
        val missing = """{"trades":[{"id":"b","date":"2026-09-11","result":"loss","pnl":2}]}"""
        assertTrue(BackupCodec.parseJson(iso).trades.single().timestamp > 0)
        assertTrue(BackupCodec.parseJson(missing).trades.single().timestamp > 0)
    }

    @Test
    fun malformedAndInvalidBackupsFailBeforeImport() {
        assertFails { BackupCodec.parseJson("not-json") }
        assertFails { BackupCodec.parseJson("""{"settings":{}}""") }
        assertFails { BackupCodec.parseJson("""{"trades":[{"id":"x","date":"bad","result":"win","pnl":2}]}""") }
        assertFails { BackupCodec.parseJson("""{"trades":[{"id":"x","date":"2026-09-10","result":"other","pnl":2}]}""") }
    }

    @Test
    fun mergeSkipsExistingAndDuplicateIds() = runBlocking {
        repository.add(trade("same", "win", 1.0))
        val backup = BackupData(
            listOf(trade("same", "loss", 2.0), trade("new", "win", 3.0), trade("new", "win", 4.0)),
            SettingsEntity(theme = "light")
        )
        val result = repository.merge(backup)
        assertEquals(1, result.added)
        assertEquals(2, result.skipped)
        assertEquals(setOf("same", "new"), repository.allTrades().map { it.id }.toSet())
        assertEquals("light", repository.getSettings().theme)
    }

    @Test
    fun replaceAtomicallyReplacesTradesAndRestoresSettings() = runBlocking {
        repository.add(trade("old", "win", 1.0))
        repository.saveSettings(SettingsEntity(theme = "dark", backupFolderUri = "content://folder"))
        val result = repository.replace(
            BackupData(listOf(trade("new", "loss", 9.0)), SettingsEntity(theme = "light", profitTarget = 400.0))
        )
        assertEquals(1, result.added)
        assertEquals("new", repository.allTrades().single().id)
        assertEquals(-9.0, repository.allTrades().single().pnl, 0.0)
        assertEquals("light", repository.getSettings().theme)
        assertEquals(400.0, repository.getSettings().profitTarget!!, 0.0)
        assertEquals("content://folder", repository.getSettings().backupFolderUri)
    }

    @Test
    fun csvIncludesRequiredColumnsAndEscapesUserText() {
        val csv = BackupCodec.exportCsv(listOf(trade("csv", "win", 2.0).copy(notes = "one, \"two\"")))
        assertTrue(csv.startsWith("id,date,entryTime,result,pnl,symbol,notes"))
        assertTrue(csv.contains("\"one, \"\"two\"\"\""))
        assertFalse(csv.contains("null"))
    }

    private fun trade(id: String, result: String, pnl: Double): TradeEntity {
        val now = 1_789_000_000_000L
        return TradeEntity(id, "2026-09-10", now, result, pnl, "EURUSD", "", "09:30", now, now)
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected operation to fail", failed)
    }
}
