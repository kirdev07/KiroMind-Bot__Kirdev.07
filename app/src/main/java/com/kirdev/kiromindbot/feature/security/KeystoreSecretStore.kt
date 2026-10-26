package com.kirdev.kiromindbot.feature.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore. Only ciphertext and IV
 * reach SharedPreferences (excluded from backup by allowBackup=false). Nothing here logs.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("kiromind_secrets", Context.MODE_PRIVATE)

    override suspend fun save(name: String, secret: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            // The entry name is bound as associated data, so blobs cannot be swapped between names.
            cipher.updateAAD(name.toByteArray())
            val ct = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(name, encode(cipher.iv) + ":" + encode(ct)).commit()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun read(name: String): SecretRead = withContext(Dispatchers.IO) {
        val blob = prefs.getString(name, null) ?: return@withContext SecretRead.Missing
        try {
            val (iv, ct) = blob.split(':').also { require(it.size == 2) }.map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, existingKey() ?: return@withContext SecretRead.Unreadable, GCMParameterSpec(128, iv))
            }
            cipher.updateAAD(name.toByteArray())
            SecretRead.Value(String(cipher.doFinal(ct), Charsets.UTF_8))
        } catch (e: Exception) {
            SecretRead.Unreadable
        }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { prefs.edit().remove(name).commit() }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kiromind_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
