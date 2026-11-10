package com.muxlume.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.settingsDataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val onboardingDone: Boolean = false,
    /** Keep audio playing when the app is left (otherwise playback pauses, except in picture-in-picture). */
    val backgroundPlayback: Boolean = false,
    /** Reduce motion even when the system animator scale is on. */
    val reduceMotion: Boolean = false,
)

class SettingsRepository(private val store: androidx.datastore.core.DataStore<Preferences>) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
    }

    val settings: Flow<AppSettings> = store.data.map {
        AppSettings(
            onboardingDone = it[Keys.onboardingDone] ?: false,
            backgroundPlayback = it[Keys.backgroundPlayback] ?: false,
            reduceMotion = it[Keys.reduceMotion] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone() = store.edit { it[Keys.onboardingDone] = true }
    suspend fun setBackgroundPlayback(on: Boolean) = store.edit { it[Keys.backgroundPlayback] = on }
    suspend fun setReduceMotion(on: Boolean) = store.edit { it[Keys.reduceMotion] = on }
}
