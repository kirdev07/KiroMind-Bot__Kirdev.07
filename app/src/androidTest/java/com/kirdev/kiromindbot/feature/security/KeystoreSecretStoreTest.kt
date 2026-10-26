package com.kirdev.kiromindbot.feature.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore on the device. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = KeystoreSecretStore(context)
    private val raw = context.getSharedPreferences("kiromind_secrets", Context.MODE_PRIVATE)

    @After fun cleanUp() = runBlocking { store.delete("t1"); store.delete("t2") }

    @Test fun roundTripAndNoPlaintextOnDisk() = runBlocking {
        val secret = "123456789:AAH-secret_value_for_test_only_xxxxxxx"
        assertTrue(store.save("t1", secret))
        assertEquals(SecretRead.Value(secret), store.read("t1"))
        val blob = raw.getString("t1", null)!!
        assertFalse(blob.contains("secret_value"))
        assertFalse(blob.contains("123456789"))
        // A fresh instance (as after restart) reads it too.
        assertEquals(SecretRead.Value(secret), KeystoreSecretStore(context).read("t1"))
    }

    @Test fun damagedOrSwappedBlobFailsClosed() = runBlocking {
        assertTrue(store.save("t1", "first"))
        assertTrue(store.save("t2", "second"))
        // Associated data binds a blob to its name.
        raw.edit().putString("t2", raw.getString("t1", null)).commit()
        assertEquals(SecretRead.Unreadable, store.read("t2"))
        raw.edit().putString("t1", "garbage").commit()
        assertEquals(SecretRead.Unreadable, store.read("t1"))
    }

    @Test fun missingAndDelete() = runBlocking {
        assertEquals(SecretRead.Missing, store.read("t1"))
        store.save("t1", "x"); store.delete("t1")
        assertEquals(SecretRead.Missing, store.read("t1"))
    }
}
