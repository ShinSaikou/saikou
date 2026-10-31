package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.sync.KeystoreSecretStore
import com.shinsaikou.sync.SealedSecret
import com.shinsaikou.sync.SecretCipher
import com.shinsaikou.sync.SecretWriter
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Phase 3 Q8: sync's secrets never travel in a `.tachibk`, not even with private settings on, which
 * is how tracker logins travel and so how the owner exports. And a backup that carries one anyway,
 * from a foreign file, never overwrites the secret this device holds.
 *
 * The secrets are written by the real [KeystoreSecretStore] into the same preference store the real
 * exporter and restorer use, through a software cipher, since the JVM has no Android Keystore.
 */
class SyncSecretsBackupTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var env: BackupTestEnvironment
    private lateinit var secrets: KeystoreSecretStore

    /** The preference keys and the stored values the secret store wrote, and nothing else. */
    private lateinit var secretEntries: Map<String, Any>

    @BeforeEach
    fun seed() = runTest {
        env = BackupTestEnvironment(tempDir)
        val fixture = SyntheticBackupFixture.backup(mangaCount = 5, chaptersPerManga = 2, privateSettings = true)
        env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(fixture)), RestoreOptions()) {}

        val store = env.preferenceStore
        // The store's writes land in the same fake store the exporter and restorer read.
        val writer = SecretWriter { key, value ->
            if (value == null) {
                store.values.remove(key)
            } else {
                store.values[key] = value
            }
        }
        secrets = KeystoreSecretStore(store, SoftwareCipher(), writer)
        val before = env.preferenceStore.values.keys.toSet()
        SECRETS.forEach { (name, value) -> secrets.put(name, value) }
        secretEntries = env.preferenceStore.values.filterKeys { it !in before }
        secretEntries.keys shouldHaveSize SECRETS.size
    }

    @AfterEach
    fun close() = env.close()

    @Test
    fun `a backup with private settings on carries none of the secret store's keys or values`() = runTest {
        val backup = env.export(BackupOptions(privateSettings = true))

        // The export did carry private settings, so the flag under test was really on.
        backup.backupPreferences.any { Preference.isPrivate(it.key) } shouldBe true

        val exportedKeys = backup.backupPreferences.map { it.key }.toSet()
        exportedKeys.intersect(secretEntries.keys) shouldBe emptySet()

        // The whole encoded file, not only the preference list: no key, no ciphertext, no secret.
        val encoded = ProtoBuf.encodeToByteArray(Backup.serializer(), backup).decodeToString()
        val needles = secretEntries.keys + secretEntries.values.map { it as String } + SECRETS.values
        needles.filter { it in encoded } shouldBe emptyList()
    }

    @Test
    fun `a backup that carries a secret's key never overwrites the secret`() = runTest {
        val (key, stored) = secretEntries.entries.first()
        val errors = mutableListOf<ImportError>()

        env.preferenceRestorer.restoreApp(
            listOf(BackupPreference(key, StringPreferenceValue("another device's ciphertext"))),
            emptyMap(),
            errors,
        )

        env.preferenceStore.values[key] shouldBe stored
        SECRETS.forEach { (name, value) -> secrets.get(name) shouldBe value }
    }

    /** AES-256-GCM from the JVM's own provider, standing in for the Keystore's. */
    private class SoftwareCipher : SecretCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedSecret {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associatedData)
            return SealedSecret(cipher.iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(sealed: SealedSecret, associatedData: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(sealed.ciphertext)
        }
    }

    private companion object {
        val SECRETS = mapOf(
            "webdav_url" to "https://dav.example.invalid/saikou",
            "webdav_user" to "owner",
            "webdav_password" to "correct horse battery staple",
            "drive_refresh_token" to "1//refresh-token-that-must-stay-home",
        )
    }
}
