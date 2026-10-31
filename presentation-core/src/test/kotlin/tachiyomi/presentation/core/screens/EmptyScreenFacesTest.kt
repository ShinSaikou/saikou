// Fresh: written for Saikou. Placed in the vendored package so it can read the internal face list.
package tachiyomi.presentation.core.screens

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The empty state's faces are upstream's twelve, verbatim and in upstream's order, then Saikou's four
 * (look rethink build plan, decision 6). `upstream-v0.20.4-error-faces.txt` was taken once from upstream
 * v0.20.4's `EmptyScreen.kt`, one face per line, so a face retyped by hand cannot pass here.
 */
class EmptyScreenFacesTest {

    private val upstreamFaces: List<String> = javaClass.getResourceAsStream("/upstream-v0.20.4-error-faces.txt")!!
        .bufferedReader(Charsets.UTF_8)
        .readLines()

    @Test
    fun `the copy of upstream's list holds its twelve faces, the leading space kept`() {
        upstreamFaces.size shouldBe 12
        upstreamFaces[10] shouldBe " \u02D9\u1BC5\u02D9)"
    }

    @Test
    fun `the first twelve faces are upstream's, in upstream's order`() {
        ErrorFaces.take(12) shouldBe upstreamFaces
    }

    @Test
    fun `Saikou's four follow upstream's twelve`() {
        ErrorFaces.drop(12) shouldBe listOf(
            "\u2510(\u00B4\u0434`)\u250C",
            "(\u30FB\u30FB?)",
            "(\u00B4-`).\uFF61oO",
            "(\uFF3F\u3000\uFF3F)\u3002\u309Cz\uFF5A",
        )
        ErrorFaces.size shouldBe 16
    }
}
