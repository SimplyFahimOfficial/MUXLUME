package com.muxlume.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.settingsDataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How much of a stream the player buffers ahead: more rides out a weak connection, less starts and switches faster. */
@Suppress("MagicNumber") // buffer durations in milliseconds
enum class BufferMode(val minMs: Int, val maxMs: Int, val startMs: Int, val resumeMs: Int) {
    NORMAL(50_000, 50_000, 2_500, 5_000),
    LARGE(30_000, 120_000, 5_000, 10_000),
    SMALL(5_000, 15_000, 1_000, 2_000),
}

/** Highlight colour; maps to a [androidx.compose.ui.graphics.Color] in the theme. */
enum class Accent { ORANGE, AMBER, CRIMSON, TEAL, VIOLET }

data class AppSettings(
    val onboardingDone: Boolean = false,
    /** Keep audio playing when the app is left (otherwise playback pauses, except in picture-in-picture). */
    val backgroundPlayback: Boolean = false,
    /** Reduce motion even when the system animator scale is on. */
    val reduceMotion: Boolean = false,
    val theme: ThemeMode = ThemeMode.DARK,
    /** In dark mode, use pure black for raised surfaces too (OLED-friendly). */
    val amoled: Boolean = false,
    val accent: Accent = Accent.ORANGE,
    /** Android 12+: take the highlight colour from the system (Material You), ignoring [accent]. */
    val dynamicColor: Boolean = false,
    /** Refresh the TV guide in the background only on an unmetered (Wi-Fi) network. */
    val guideWifiOnly: Boolean = true,
    val buffer: BufferMode = BufferMode.NORMAL,
)

class SettingsRepository(private val store: DataStore<Preferences>) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val theme = stringPreferencesKey("theme")
        val amoled = booleanPreferencesKey("amoled")
        val accent = stringPreferencesKey("accent")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val guideWifiOnly = booleanPreferencesKey("guide_wifi_only")
        val buffer = stringPreferencesKey("buffer")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e } // unreadable file: use defaults
        .map {
        AppSettings(
            onboardingDone = it[Keys.onboardingDone] ?: false,
            backgroundPlayback = it[Keys.backgroundPlayback] ?: false,
            reduceMotion = it[Keys.reduceMotion] ?: false,
            theme = it[Keys.theme]?.let(::themeOf) ?: ThemeMode.DARK,
            amoled = it[Keys.amoled] ?: false,
            accent = it[Keys.accent]?.let(::accentOf) ?: Accent.ORANGE,
            dynamicColor = it[Keys.dynamicColor] ?: false,
            guideWifiOnly = it[Keys.guideWifiOnly] ?: true,
            buffer = BufferMode.entries.firstOrNull { m -> m.name == it[Keys.buffer] } ?: BufferMode.NORMAL,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone() = store.edit { it[Keys.onboardingDone] = true }
    suspend fun setBackgroundPlayback(on: Boolean) = store.edit { it[Keys.backgroundPlayback] = on }
    suspend fun setReduceMotion(on: Boolean) = store.edit { it[Keys.reduceMotion] = on }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }
    suspend fun setAmoled(on: Boolean) = store.edit { it[Keys.amoled] = on }
    suspend fun setAccent(accent: Accent) = store.edit { it[Keys.accent] = accent.name }
    suspend fun setDynamicColor(on: Boolean) = store.edit { it[Keys.dynamicColor] = on }
    suspend fun setGuideWifiOnly(on: Boolean) = store.edit { it[Keys.guideWifiOnly] = on }
    suspend fun setBuffer(mode: BufferMode) = store.edit { it[Keys.buffer] = mode.name }

    private fun themeOf(name: String) = runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.DARK)
    private fun accentOf(name: String) = runCatching { Accent.valueOf(name) }.getOrDefault(Accent.ORANGE)
}
