package com.mokamusic.player.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SavedNetworkLogin(
    val server: String,
    val username: String,
    val password: String
)

/**
 * Device-only saved login: AES-256-GCM key lives in Android Keystore and encrypted
 * bytes live in noBackupFilesDir. Neither the password nor the key is backed up.
 * Never put a Subsonic password or signed stream URL in logs.
 */
internal class NetworkCredentialStore(context: Context) {
    private val file = AtomicFile(context.applicationContext.noBackupFilesDir
        .resolve("moka_network_login_v1.bin"))

    fun isSaved(): Boolean = file.baseFile.exists()

    fun save(login: SavedNetworkLogin) {
        require(login.server.startsWith("https://") && login.username.isNotBlank())
        val data = org.json.JSONObject().apply {
            put("server", login.server)
            put("username", login.username)
            put("password", login.password)
        }.toString().toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(data)
        val payload = cipher.iv + encrypted

        val out = file.startWrite()
        try {
            out.write(payload)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    fun load(): SavedNetworkLogin? = runCatching {
        if (!isSaved()) return@runCatching null
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > IV_BYTES + 16) { "Saved login is incomplete" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        val decrypted = cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size))
        val json = org.json.JSONObject(String(decrypted, Charsets.UTF_8))
        SavedNetworkLogin(
            server = json.getString("server"),
            username = json.getString("username"),
            password = json.getString("password")
        ).takeIf { it.server.startsWith("https://") && it.username.isNotBlank() }
    }.getOrNull()

    fun clear() {
        file.delete()
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "moka.network.login.aes.v1"
        const val IV_BYTES = 12
    }
}
