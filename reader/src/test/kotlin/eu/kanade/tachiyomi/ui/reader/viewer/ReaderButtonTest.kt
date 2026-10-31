// Fresh: exercises the internal touch rule of the vendored ReaderButton, so it sits in that package.
package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.MotionEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Which touches give the pager its tap navigation back after a press on a reader button. */
class ReaderButtonTest {

    @Test
    fun `a lifted and a cancelled touch both end the press`() {
        ReaderButton.endsTouch(MotionEvent.ACTION_UP) shouldBe true
        ReaderButton.endsTouch(MotionEvent.ACTION_CANCEL) shouldBe true
    }

    @Test
    fun `a touch still going on does not`() {
        ReaderButton.endsTouch(MotionEvent.ACTION_DOWN) shouldBe false
        ReaderButton.endsTouch(MotionEvent.ACTION_MOVE) shouldBe false
    }
}
