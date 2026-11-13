package com.muxlume.app

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxlume.app.data.AppSettings
import com.muxlume.app.ui.MuxlumeRoot
import com.muxlume.app.ui.theme.MuxlumeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

    private val pip = MutableStateFlow(false)

    @Volatile private var settingsLoaded = false

    /** True while the activity shows in picture-in-picture. */
    val inPictureInPicture: StateFlow<Boolean> get() = pip

    /** Set by the player screen while a video is playing, so leaving the app enters picture-in-picture. */
    var autoPip: Boolean = false
        set(value) {
            field = value
            // Android 12+ enters PiP smoothly by itself; older versions use onUserLeaveHint below.
            if (Build.VERSION.SDK_INT >= 31 && hasPip()) runCatching { setPictureInPictureParams(pipParams().setAutoEnterEnabled(value).build()) }
        }

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { pip.value = it.isInPictureInPictureMode }

    private fun hasPip() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams() = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).apply {
        if (Build.VERSION.SDK_INT >= 31) setSeamlessResizeEnabled(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        addOnPictureInPictureModeChangedListener(pipListener)
        // Keep the splash until settings load, so a light-theme user never sees a dark first frame.
        splash.setKeepOnScreenCondition { !settingsLoaded }
        setContent {
            val loaded by container.settings.settings.collectAsStateWithLifecycle(null)
            val settings = loaded ?: AppSettings(onboardingDone = true)
            SideEffect { settingsLoaded = loaded != null }
            MuxlumeTheme(
                theme = settings.theme,
                amoled = settings.amoled,
                accent = settings.accent,
                dynamicColor = settings.dynamicColor,
                reduceMotion = settings.reduceMotion,
            ) { MuxlumeRoot() }
        }
    }

    override fun onStart() {
        super.onStart()
        container.lock.onForeground()
    }

    override fun onStop() {
        container.lock.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (autoPip && Build.VERSION.SDK_INT < 31) enterPip()
    }

    /** False when the device has no picture-in-picture or the user turned it off for this app. */
    fun enterPip(): Boolean = hasPip() && runCatching { enterPictureInPictureMode(pipParams().build()) }.getOrDefault(false)
}
