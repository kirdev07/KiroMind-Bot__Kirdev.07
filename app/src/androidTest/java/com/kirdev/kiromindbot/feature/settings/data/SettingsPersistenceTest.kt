package com.kirdev.kiromindbot.feature.settings.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kirdev.kiromindbot.feature.engine.domain.Persona
import com.kirdev.kiromindbot.feature.settings.domain.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real DataStore file on the device; each block is a separate "process run". */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.filesDir, "settings-test.preferences_pb")
    private val custom = AppPreferences(contextEnabled = false, persona = Persona.POLITE, groupsEnabled = true, groupPrefix = "Кира,", ownerIds = "11, 22")

    @Before @After fun clean() { file.delete() }

    private fun <T> run(block: suspend (SettingsRepository) -> T): T = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
            withTimeout(5_000) { repo.loaded.first { it } }
            block(repo)
        } finally {
            job.cancelAndJoin() // DataStore allows one active instance per file
        }
    }

    @Test fun settingsAndBotFlagSurviveRestart() {
        run { repo ->
            assertEquals(AppPreferences(), repo.preferences.value)
            assertFalse(repo.isBotEnabled())
            repo.update(custom.copy(ownerIds = "1"))
            repo.update(custom) // the last edit wins
            repo.setBotEnabled(true)
            withTimeout(5_000) { while (!repo.isBotEnabled()) delay(20) }
            delay(300)
        }
        run { repo ->
            assertEquals(custom, repo.preferences.value)
            assertTrue(repo.isBotEnabled())
            repo.setBotEnabled(false)
            withTimeout(5_000) { while (repo.isBotEnabled()) delay(20) }
        }
        run { repo -> assertFalse(repo.isBotEnabled()) }
    }
}
