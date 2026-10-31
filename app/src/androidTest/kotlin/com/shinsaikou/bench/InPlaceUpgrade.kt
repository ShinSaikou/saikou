package com.shinsaikou.bench

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.di.AppGraph
import com.shinsaikou.util.ForceStopOutcome
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * What unit R1's in-place upgrade check (phase 3 decision Q15) records before an install and
 * compares after it. [InPlaceUpgradeSeedTest] leaves a debug build's state behind and writes
 * [snapshot] to an outcome file; the host installs another build over it with `adb install -r`; and
 * [InPlaceUpgradeCheckTest] takes the snapshot again in that build and compares, then records it
 * afresh for the next install. `design/plans/phase3-units/evidence/phase3-release-signing/` holds
 * the host script that runs the three in order.
 *
 * The check runs against the minified release, and AGP minifies its test APK with the application's
 * mapping, so every member of the application's it calls has to survive R8 or be kept for it
 * (`tools/r8/check_test_references.py`). The snapshot therefore reads the database, the download root
 * and the preferences without the application's classes: the library's tables in the application's
 * own database file, through the SQLite library the application uses ([query] says why that
 * matters), WorkManager's in its own, every file under `files/downloads`, and the preference file by the
 * keys the seed recorded. What goes through the application's graph is what only it can answer: the
 * secret, which `SecretStore` decrypts with a key the platform keystore holds for this package; the
 * crash files `CrashLog` lists; and the extensions the extension manager loaded. That the installed
 * build reads its database is shown by the check's other half, which draws the library and runs a
 * library update through the screen.
 *
 * The outcome file is [ForceStopOutcome]'s, outside the application's storage, so nothing an install
 * or the application does to its data can change what the next build compares against.
 *
 * Infrastructure rather than a test, like [com.shinsaikou.util.GateFragments]. Every name is synthetic.
 */
object InPlaceUpgrade {

    /** `seed`, `hold` or `check`; a test whose stage is not the one asked for logs a skip. */
    const val STAGE_ARGUMENT = "saikou.upgrade.stage"

    /** A label for the build under test (for example `release-1`), for the log and the outcome. */
    const val BUILD_ARGUMENT = "saikou.upgrade.build"

    /** Where the host pushed the frozen update fixture extension, as `GateRowsTest` takes it. */
    const val FIXTURE_APK_ARGUMENT = "saikou.download.fixture.apk"

    const val OUTCOME = "in_place_upgrade"
    const val SCHEMA = 1
    const val LOG_TAG = "InPlaceUpgrade"

    /** A name no backend uses; the check's own. */
    const val SECRET_NAME = "upgrade_check"
    const val SECRET_VALUE = "written by the debug build before the switch"

    const val FIXTURE_CATEGORY = "Upgrade check fixture"

    /** The frozen update fixture's server (R66), which the host runs on port 18794. */
    const val FIXTURE_SERVER = "http://10.0.2.2:18794"

    /** Chapters of the fixture entry the seed inserts, of the [FIXTURE_LISTED] its source lists. */
    const val FIXTURE_SEEDED = 45
    const val FIXTURE_LISTED = 50

    /** `DatabaseBindings.DATABASE_NAME`, under `databases/`. */
    const val DATABASE = "saikou.db"

    /** WorkManager's own database, which it keeps under `no_backup/`. */
    const val WORK_DATABASE = "androidx.work.workdb"

    /** WorkManager's stored states (`WorkTypeConverters`): enqueued, running, then the finished ones. */
    const val WORK_ENQUEUED = 0
    const val WORK_RUNNING = 1
    const val WORK_SUCCEEDED = 2

    fun stage(): String? = InstrumentationRegistry.getArguments().getString(STAGE_ARGUMENT)?.takeIf { it.isNotBlank() }

    fun build(): String = InstrumentationRegistry.getArguments().getString(BUILD_ARGUMENT)?.takeIf { it.isNotBlank() } ?: "unnamed"

    /** Writes the line a gate step counts, as `GateRowsTest` does: an early return says why. */
    fun skip(method: String, wanted: String) {
        Log.i(LOG_TAG, "Skipped: $method: runs only when $STAGE_ARGUMENT is $wanted")
    }

    /**
     * The state an in-place install must keep. Library counts cover the entries in the library that
     * are not deleted. Downloads are the manifest's rows and every file under the download root
     * outside a `_tmp` folder, so a chapter still in flight is in neither; the queue is compared on
     * its own. The preferences are the values stored under [preferenceKeys], as text.
     */
    fun snapshot(graph: AppGraph, context: Context, preferenceKeys: Collection<String>): JSONObject {
        val library = "FROM mangas m WHERE m.favorite = 1 AND m.deleted_at IS NULL"
        val (chapters, read, bookmarked) = query(
            context,
            DATABASE,
            "SELECT count(*), coalesce(sum(c.read), 0), coalesce(sum(c.bookmark), 0) FROM chapters c " +
                "JOIN mangas m ON m._id = c.manga_id WHERE m.favorite = 1 AND m.deleted_at IS NULL AND c.deleted_at IS NULL",
        ).single()
        val downloads = JSONArray()
        query(context, DATABASE, "SELECT chapter_id, chapter_dir_name, page_count, size_bytes FROM downloads_manifest ORDER BY chapter_id")
            .forEach { (chapterId, dir, pages, bytes) ->
                downloads.put(JSONObject().put("chapterId", chapterId).put("dir", dir).put("pages", pages).put("bytes", bytes))
            }
        val underRoot = downloadRoot(context).walkTopDown()
            .onEnter { !it.name.endsWith(TMP_SUFFIX) }
            .filter { it.isFile }
            .toList()
        val stored = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE).all
        val preferences = JSONObject()
        preferenceKeys.sorted().forEach { key -> preferences.put(key, stored[key]?.toString() ?: JSONObject.NULL) }
        return JSONObject()
            .put("favorites", count(context, "SELECT count(*) $library"))
            .put("categories", JSONArray(query(context, DATABASE, "SELECT name FROM categories WHERE deleted_at IS NULL ORDER BY name").map { it[0] }))
            .put(
                "memberships",
                count(context, "SELECT count(*) FROM mangas_categories mc WHERE mc.manga_id IN (SELECT m._id $library)"),
            )
            .put("chapters", chapters)
            .put("read", read)
            .put("bookmarked", bookmarked)
            .put(
                "history",
                count(
                    context,
                    "SELECT count(*) FROM history h JOIN chapters c ON c._id = h.chapter_id WHERE c.manga_id IN (SELECT m._id $library)",
                ),
            )
            .put("downloads", downloads)
            .put("filesUnderDownloads", underRoot.size)
            .put("bytesUnderDownloads", underRoot.sumOf { it.length() })
            .put("preferences", preferences)
            .put("secret", graph.secretStore.get(SECRET_NAME) ?: JSONObject.NULL)
            .put("crashFiles", JSONArray(graph.crashLog.list().map { it.name }.sorted()))
            .put(
                "extensions",
                JSONArray(graph.extensionManager.installedExtensions.value.map { "${it.packageName}:${it.versionCode}" }.sorted()),
            )
    }

    /** Each key of [expected] whose value differs in [actual], with both values. */
    fun differences(expected: JSONObject, actual: JSONObject): List<String> =
        expected.keys().asSequence().toList().sorted().mapNotNull { key ->
            val was = expected.opt(key)?.toString()
            val now = actual.opt(key)?.toString()
            if (was == now) null else "$key: was $was, now $now"
        }

    /** One line a report can quote: the counts, without the per-chapter list. */
    fun summary(snapshot: JSONObject): String =
        listOf(
            "favorites", "memberships", "chapters", "read", "bookmarked", "history",
            "filesUnderDownloads", "bytesUnderDownloads",
        ).joinToString(" ") { "$it=${snapshot.opt(it)}" } +
            " downloadedChapters=${snapshot.getJSONArray("downloads").length()}" +
            " categories=${snapshot.getJSONArray("categories").length()}" +
            " preferences=${snapshot.getJSONObject("preferences").length()}" +
            " secret=${if (snapshot.isNull("secret")) "absent" else "present"}" +
            " crashFiles=${snapshot.getJSONArray("crashFiles").length()}" +
            " extensions=${snapshot.getJSONArray("extensions").length()}"

    /**
     * Every row [sql] returns from [database] ([DATABASE] or [WORK_DATABASE]), each column as text.
     *
     * Each is read through the SQLite its owner uses, because two copies of SQLite in one process do
     * not see each other's locks. The application opens [DATABASE] through the bundled driver; the
     * first version of this check read it through the platform's SQLite, and on the API 28 image the
     * platform connection's close truncated the WAL index under the application's own connection,
     * which died with SIGBUS mid-check. WorkManager keeps [WORK_DATABASE] through the platform's.
     */
    fun query(context: Context, database: String, sql: String, vararg args: String): List<List<String?>> {
        val path = if (database == WORK_DATABASE) File(context.noBackupFilesDir, database) else context.getDatabasePath(database)
        check(path.isFile) { "no database at $path" }
        if (database == WORK_DATABASE) {
            val db = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
            return try {
                db.rawQuery(sql, args).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
                    }
                }
            } finally {
                db.close()
            }
        }
        val connection = BundledSQLiteDriver().open(path.path)
        try {
            val statement = connection.prepare(sql)
            try {
                args.forEachIndexed { index, arg -> statement.bindText(index + 1, arg) }
                return buildList {
                    while (statement.step()) {
                        add((0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) })
                    }
                }
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
    }

    fun count(context: Context, sql: String, vararg args: String): Long =
        checkNotNull(query(context, DATABASE, sql, *args).single().single()).toLong()

    /** WorkManager's rows for the unique work [name], as (id, state). */
    fun uniqueWork(context: Context, name: String): List<Pair<String, Int>> =
        query(
            context,
            WORK_DATABASE,
            "SELECT s.id, s.state FROM WorkName n JOIN WorkSpec s ON s.id = n.work_spec_id WHERE n.name = ?",
            name,
        ).map { (id, state) -> checkNotNull(id) to checkNotNull(state).toInt() }

    /** `DownloadRoot`'s folder: `File(filesDir, "downloads")`. */
    fun downloadRoot(context: Context): File = File(context.filesDir, "downloads")

    fun readOutcome(): JSONObject {
        val text = ForceStopOutcome.read(OUTCOME)
            ?: throw AssertionError("no outcome at ${ForceStopOutcome.path(OUTCOME)}: the seed did not run on this device")
        val outcome = JSONObject(text)
        if (outcome.optInt("schema") != SCHEMA || outcome.optString("row") != OUTCOME) {
            throw AssertionError("the outcome at ${ForceStopOutcome.path(OUTCOME)} is not this check's")
        }
        return outcome
    }

    fun writeOutcome(outcome: JSONObject) {
        ForceStopOutcome.write(OUTCOME, outcome.put("schema", SCHEMA).put("row", OUTCOME).toString())
    }

    /** A GET against the fixture server, as `GateRowsTest` makes it. Fails on anything but 200. */
    fun fixtureServer(path: String): JSONObject {
        val connection = URL("$FIXTURE_SERVER$path").openConnection() as HttpURLConnection
        connection.connectTimeout = SERVER_TIMEOUT_MILLIS
        connection.readTimeout = SERVER_TIMEOUT_MILLIS
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw AssertionError("the fixture server answered $path with ${connection.responseCode}")
            }
            JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            connection.disconnect()
        }
    }

    /** Polls [condition] until it holds or [timeoutMillis] passes, then fails with [message]. */
    suspend fun await(timeoutMillis: Long, message: () -> String, condition: suspend () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("${message()} (after $timeoutMillis ms)")
            delay(POLL_MILLIS)
        }
    }

    private const val TMP_SUFFIX = "_tmp"
    private const val SERVER_TIMEOUT_MILLIS = 10_000
    private const val POLL_MILLIS = 250L
}
