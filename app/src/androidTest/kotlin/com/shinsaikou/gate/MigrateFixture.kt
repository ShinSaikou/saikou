package com.shinsaikou.gate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.net.HttpURLConnection
import java.net.URL

/**
 * The catalogue `tools/bench/migrate_fixture_server.py` serves, read from its control path, and that
 * path's two failures (phase 4 unit MB, design g7 final section 20.1). Nothing here is a test: the
 * batch migration's device checks in [GateRowsTest] seed from it and read the server's counts back.
 *
 * The fixture extension's twenty sources are "Update Fixture 01" to "20"; the server's catalogue
 * numbers them the same way, so source 1 is the old source A, 2 the second old source, 3 target B
 * and 4 target C.
 */
internal class MigrateFixture(private val server: String) {

    /** One entry of the catalogue. [onB] and [onC] are an old entry's entry numbers on B and C. */
    data class Entry(
        val source: Int,
        val entry: Int,
        val title: String,
        val role: String,
        val chapters: Int,
        val onB: Int?,
        val onC: Int?,
    ) {
        val url: String get() = "/manga/$entry"

        /** The entry on [target] holding this entry's title, B's or C's. */
        fun on(target: Int): Int? = when (target) {
            TARGET_B -> onB
            TARGET_C -> onC
            else -> null
        }
    }

    val entries: Map<Int, Map<Int, Entry>> by lazy {
        val sources = JSONObject(get(CATALOGUE_PATH)).getJSONObject("sources")
        sources.keys().asSequence().associate { key ->
            val list = sources.getJSONArray(key)
            key.toInt() to (0 until list.length()).associate { i ->
                val json = list.getJSONObject(i)
                val targets = json.optJSONObject("targets")
                json.getInt("entry") to Entry(
                    source = json.getInt("source"),
                    entry = json.getInt("entry"),
                    title = json.getString("title"),
                    role = json.getString("role"),
                    chapters = json.getInt("chapters"),
                    onB = targets?.optNullableInt(TARGET_B.toString()),
                    onC = targets?.optNullableInt(TARGET_C.toString()),
                )
            }
        }
    }

    fun entry(source: Int, entry: Int): Entry = entries.getValue(source).getValue(entry)

    /** The old source A's entries of [role], in entry order. */
    fun onA(role: String): List<Entry> = entries.getValue(OLD_A).values.filter { it.role == role }.sortedBy { it.entry }

    /** The failure state and the counts, as the control path answers them. */
    fun state(): JSONObject = JSONObject(get(CONTROL_PATH))

    /** Sets the failures with the control path's own query, `unavailable=3:5` or `fail_chapters=3:4-9`. */
    fun control(query: String): JSONObject = JSONObject(get("$CONTROL_PATH?$query"))

    /** How many requests of [kind] (`search`, `details`, `chapters`) [source] has answered. */
    fun answered(kind: String, source: Int): Int =
        state().getJSONObject("answered").getJSONObject(kind).optInt(source.toString(), 0)

    private fun get(path: String): String {
        val connection = URL("$server$path").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        return try {
            assertEquals(
                "$server$path did not answer as migrate_fixture_server.py does; is it the server running?",
                HttpURLConnection.HTTP_OK,
                connection.responseCode,
            )
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optNullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)

    companion object {
        const val OLD_A = 1
        const val OLD_OVERFLOW = 2
        const val TARGET_B = 3
        const val TARGET_C = 4
        const val SOURCES = 4
        const val CONTROL_PATH = "/control"
        const val CATALOGUE_PATH = "/control/catalogue"
        const val TIMEOUT_MILLIS = 10_000
    }
}
