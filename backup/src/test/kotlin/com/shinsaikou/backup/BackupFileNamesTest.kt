package com.shinsaikou.backup

import com.shinsaikou.backup.export.BackupFileNames
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class BackupFileNamesTest {

    @Test
    fun `a suggested name matches the rotation pattern`() {
        val name = BackupFileNames.suggest(1_700_000_000_000L)

        name shouldMatch BackupFileNames.AUTO_BACKUP_REGEX
    }

    @Test
    fun `a name the app did not write is never rotated`() {
        BackupFileNames.AUTO_BACKUP_REGEX.matches("something-else.tachibk") shouldBe false
        BackupFileNames.AUTO_BACKUP_REGEX.matches("com.shinsaikou_2026-01-01_10-00.tachibk.bak") shouldBe false
    }

    @Test
    fun `a safety backup's name has its own pattern, which the automatic rotation never matches`() {
        val name = BackupFileNames.suggestSync(1_700_000_000_000L)

        name shouldMatch BackupFileNames.SYNC_BACKUP_REGEX
        BackupFileNames.AUTO_BACKUP_REGEX.matches(name) shouldBe false
        BackupFileNames.SYNC_BACKUP_REGEX.matches(BackupFileNames.suggest(1_700_000_000_000L)) shouldBe false
    }

    @Test
    fun `names sort chronologically, so rotation keeps the newest four`() {
        val day = TimeUnit.DAYS.toMillis(1)
        val names = (0..9).map { BackupFileNames.suggest(1_700_000_000_000L + it * day) }

        val kept = names.shuffled().sorted().takeLast(BackupFileNames.MAX_KEPT)

        kept shouldContainExactly names.takeLast(BackupFileNames.MAX_KEPT)
    }
}
