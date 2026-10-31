package com.shinsaikou.reader

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The S Pen's air actions find the reader through its manifest entry and send the keys its action
 * file lists. Both are resources the JVM tests cannot load, so the files are read as XML: the entry
 * has to be on the reader activity, and every key has to be one the reader handles (the paged
 * viewer turns pages on Ctrl with the arrows, the activity changes chapter on N and P).
 */
class SPenActionsTest {

    @Test
    fun `the reader activity offers the S Pen its action file`() {
        val activity = xml("src/main/AndroidManifest.xml").elements("activity")
            .single { it.getAttribute("android:name") == "com.shinsaikou.reader.ReaderActivity" }

        activity.elements("action").map { it.getAttribute("android:name") } shouldBe listOf(REMOTE_ACTION)
        val meta = activity.elements("meta-data").single()
        meta.getAttribute("android:name") shouldBe REMOTE_ACTION
        meta.getAttribute("android:resource") shouldBe "@xml/s_pen_actions"
    }

    @Test
    fun `every action sends a key the reader handles, under a label that exists`() {
        val actions = xml("src/main/res/xml/s_pen_actions.xml").elements("action")

        actions.map { it.getAttribute("trigger_key") } shouldContainExactlyInAnyOrder
            listOf("CTRL_LEFT+DPAD_RIGHT", "CTRL_LEFT+DPAD_LEFT", "BACK", "N", "P")
        val strings = File("../i18n/src/main/res/values").listFiles().orEmpty().joinToString("") { it.readText() }
        actions.map { it.getAttribute("label").removePrefix("@string/") }.forEach { name ->
            strings.contains("<string name=\"$name\"") shouldBe true
        }
    }

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private companion object {
        const val REMOTE_ACTION = "com.samsung.android.support.REMOTE_ACTION"
    }
}
