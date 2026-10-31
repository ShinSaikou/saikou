package com.shinsaikou.appupdate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Whether a downloaded APK may be installed over this app: same package, same signers, newer. */
class ArchiveRulesTest {

    private val installed = PackageFacts(packageName = "com.shinsaikou", versionCode = 2, signers = setOf(OWNER))
    private val update = installed.copy(versionCode = 3)

    @Test
    fun `this app, signed by the same key, with a higher version code, is accepted`() {
        ArchiveRules.judge(update, installed).shouldBeNull()
    }

    @Test
    fun `a file Android could not read is refused`() {
        ArchiveRules.judge(null, installed) shouldBe UpdateRefusal.UNREADABLE
    }

    @Test
    fun `another package is refused`() {
        ArchiveRules.judge(update.copy(packageName = "com.shinsaikou.other"), installed) shouldBe UpdateRefusal.OTHER_PACKAGE
    }

    @Test
    fun `another key, an extra key, a missing key or no key at all is refused`() {
        ArchiveRules.judge(update.copy(signers = setOf(OTHER)), installed) shouldBe UpdateRefusal.OTHER_SIGNER
        ArchiveRules.judge(update.copy(signers = setOf(OWNER, OTHER)), installed) shouldBe UpdateRefusal.OTHER_SIGNER
        ArchiveRules.judge(update.copy(signers = emptySet()), installed) shouldBe UpdateRefusal.OTHER_SIGNER
        ArchiveRules.judge(update.copy(signers = setOf(OWNER)), installed.copy(signers = setOf(OWNER, OTHER))) shouldBe
            UpdateRefusal.OTHER_SIGNER
    }

    @Test
    fun `an unsigned installed app accepts no update, not even an unsigned one`() {
        val unsigned = installed.copy(signers = emptySet())
        ArchiveRules.judge(update.copy(signers = emptySet()), unsigned) shouldBe UpdateRefusal.OTHER_SIGNER
    }

    @Test
    fun `the same or a lower version code is refused`() {
        ArchiveRules.judge(update.copy(versionCode = 2), installed) shouldBe UpdateRefusal.NOT_NEWER
        ArchiveRules.judge(update.copy(versionCode = 1), installed) shouldBe UpdateRefusal.NOT_NEWER
    }

    private companion object {
        const val OWNER = "1111111111111111111111111111111111111111111111111111111111111111"
        const val OTHER = "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
