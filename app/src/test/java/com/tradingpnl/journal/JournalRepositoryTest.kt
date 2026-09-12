package com.tradingpnl.journal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
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
    fun tearDown() {
        database.close()
        File(context.filesDir, "trade_images").deleteRecursively()
    }

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
        assertTrue(csv.startsWith("id,date,entryTime,result,pnl,symbol,setup,session,emotion,mistakes"))
        assertTrue(csv.contains("\"one, \"\"two\"\"\""))
        assertFalse(csv.contains("null"))
    }

    @Test
    fun v2MetadataRoundTripsAndValidatesScore() {
        val original = trade("story", "win", 125.0).copy(
            setup = "Breakout",
            session = "London",
            emotion = "Calm",
            mistakes = "Early exit|FOMO",
            plannedR = 2.0,
            realizedR = 1.4,
            executionScore = 4,
            reviewed = true
        )
        val parsed = BackupCodec.parseJson(
            BackupCodec.exportJson(listOf(original), SettingsEntity(), "test")
        ).trades.single()
        assertEquals("Breakout", parsed.setup)
        assertEquals("London", parsed.session)
        assertEquals("Early exit|FOMO", parsed.mistakes)
        assertEquals(1.4, parsed.realizedR!!, 0.0)
        assertTrue(parsed.reviewed)
        assertFails { TradeValidator.validate(original.copy(executionScore = 6)) }
    }

    @Test
    fun deletingTradeCascadesAttachmentRow() = runBlocking {
        repository.add(trade("with-image", "win", 10.0))
        repository.addAttachment(attachment("image-1", "with-image", "with-image/image-1.png", "0".repeat(64)))
        assertEquals(1, repository.allAttachments().size)
        repository.delete("with-image")
        assertTrue(repository.allAttachments().isEmpty())
    }

    @Test
    fun completePackageRoundTripsJournalAndImageChecksum() = runBlocking {
        val item = trade("package", "loss", 30.0).copy(setup = "Reversal", reviewed = true)
        repository.add(item)
        val store = AttachmentStore(context, repository)
        val bytes = "offline-chart-image".toByteArray()
        val hash = bytes.sha256()
        val image = attachment("chart", item.id, "${item.id}/chart.png", hash)
            .copy(sizeBytes = bytes.size.toLong())
        store.fileFor(image.relativePath).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        repository.addAttachment(image)
        val output = ByteArrayOutputStream()
        BackupPackageCodec.write(
            output,
            BackupCodec.exportJson(repository.allTrades(), repository.getSettings(), "test"),
            repository.allAttachments(),
            store
        )
        val staged = BackupPackageCodec.read(ByteArrayInputStream(output.toByteArray()), context.cacheDir)
        try {
            assertEquals("package", staged.backup.trades.single().id)
            assertEquals("Reversal", staged.backup.trades.single().setup)
            assertEquals(hash, staged.attachments.single().sha256)
        } finally {
            staged.directory.deleteRecursively()
        }
    }

    @Test
    fun migrationOneToTwoKeepsOldTradeAndAddsStoryDefaults() = runBlocking {
        database.close()
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(path, null)
        legacy.execSQL("CREATE TABLE trades (id TEXT NOT NULL PRIMARY KEY, date TEXT NOT NULL, timestamp INTEGER NOT NULL, result TEXT NOT NULL, pnl REAL NOT NULL, symbol TEXT NOT NULL, notes TEXT NOT NULL, entryTime TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        legacy.execSQL("CREATE INDEX index_trades_date ON trades(date)")
        legacy.execSQL("CREATE INDEX index_trades_timestamp ON trades(timestamp)")
        legacy.execSQL("CREATE INDEX index_trades_symbol ON trades(symbol)")
        legacy.execSQL("CREATE INDEX index_trades_result ON trades(result)")
        legacy.execSQL("CREATE TABLE settings (id INTEGER NOT NULL PRIMARY KEY, theme TEXT NOT NULL, startingBalance REAL, profitTarget REAL, maxDrawdown REAL, dailyDrawdown REAL, backupFolderUri TEXT)")
        legacy.execSQL("INSERT INTO trades VALUES ('legacy','2026-09-10',1,'win',9.0,'NQ','','09:30',1,1)")
        legacy.version = 1
        legacy.close()
        val migrated = Room.databaseBuilder(context, JournalDatabase::class.java, name)
            .addMigrations(JournalDatabase.MIGRATION_1_2)
            .build()
        val saved = JournalRepository(migrated).allTrades().single()
        assertEquals("legacy", saved.id)
        assertEquals("", saved.setup)
        assertFalse(saved.reviewed)
        migrated.close()
        context.deleteDatabase(name)
        database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).build()
        repository = JournalRepository(database)
    }

    @Test
    fun bundledV31UiUsesNativeStorageAndHasNoBrowserStorageWarning() {
        val html = context.assets.open("index.html").bufferedReader().use { it.readText() }
        assertTrue(html.contains("Trading PnL Journal 3.1.0"))
        assertTrue(html.lowercase().contains("adaptive playbook"))
        assertTrue(html.contains("Trade Replay"))
        assertTrue(html.contains("Motion & Glass V3.1"))
        assertTrue(html.contains("AndroidJournal"))
        assertFalse(html.contains("localStorage"))
        assertFalse(html.contains("Browser storage is unavailable"))
    }

    private fun trade(id: String, result: String, pnl: Double): TradeEntity {
        val now = 1_789_000_000_000L
        return TradeEntity(id, "2026-09-10", now, result, pnl, "EURUSD", "", "09:30", now, now)
    }

    private fun attachment(id: String, tradeId: String, path: String, sha: String) = AttachmentEntity(
        id = id,
        tradeId = tradeId,
        kind = "before",
        fileName = "chart.png",
        mimeType = "image/png",
        relativePath = path,
        sha256 = sha,
        sizeBytes = 1,
        createdAt = 1_789_000_000_000L
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected operation to fail", failed)
    }
}
