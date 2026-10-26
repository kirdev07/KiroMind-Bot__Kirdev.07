package com.kirdev.kiromindbot.feature.settings.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kirdev.kiromindbot.feature.engine.domain.Persona
import com.kirdev.kiromindbot.feature.settings.domain.AppPreferences
import org.junit.Assert.*
import org.junit.Test

class SettingsRepositoryTest {
    private val custom = AppPreferences(
        contextEnabled = false, autoMood = true, persona = Persona.CHEEKY, groupsEnabled = true,
        mentionsEnabled = false, antiSpam = false, autoBan = true, logUnknown = false, diagnosticLogs = true,
        notifications = false, groupPrefix = "Кира,", ownerIds = "11, 22", logRetentionDays = "30"
    )

    @Test fun everyFieldRoundTrips() {
        val p = mutablePreferencesOf()
        SettingsKeys.write(p, custom)
        assertEquals(custom, SettingsKeys.read(p))
    }

    @Test fun emptyOrUnknownValuesUseDefaults() {
        assertEquals(AppPreferences(), SettingsKeys.read(mutablePreferencesOf()))
        val p = mutablePreferencesOf(stringPreferencesKey("persona") to "FUTURE")
        assertEquals(Persona.NEUTRAL, SettingsKeys.read(p).persona)
    }
}
