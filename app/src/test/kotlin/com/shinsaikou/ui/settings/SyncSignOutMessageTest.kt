package com.shinsaikou.ui.settings

import com.shinsaikou.i18n.R
import com.shinsaikou.ui.settings.SyncSettingsViewModel.Service
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The sign-out dialog's text is the configured backend's own (phase 3 unit H, stage 2 fix round 1
 * item 2): WebDAV's speaks of the server address, user name and password, which a Drive sign-in
 * never had, and Drive's of the Google account and the access a sign-out revokes. The English texts
 * are read from the strings file, since `:app`'s JVM tests have no resources to resolve an id with.
 */
class SyncSignOutMessageTest {

    @Test
    fun `each backend's sign-out dialog has its own text`() {
        syncSignOutMessage(Service.GOOGLE_DRIVE) shouldBe R.string.sync_drive_sign_out_message
        syncSignOutMessage(Service.WEBDAV) shouldBe R.string.sync_sign_out_message
        syncSignOutMessage(Service.GOOGLE_DRIVE) shouldNotBe syncSignOutMessage(Service.WEBDAV)

        val drive = english("sync_drive_sign_out_message")
        drive shouldContain "Google account"
        drive shouldContain "revokes"
        drive shouldContain "new device"
        drive shouldNotContain "password"
        english("sync_sign_out_message") shouldContain "server address, user name and password"
    }

    private fun english(name: String): String {
        val strings = File("../i18n/src/main/res/values/strings_screens.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1)
            ?: throw AssertionError("no string $name in the English strings")
    }
}
