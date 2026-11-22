package com.mellow.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.mellow.app.ui.MellowApp
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.core.model.Appearance
import com.mellow.feature.widget.MellowWidgets
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as MellowApplication).container

    /**
     * When the player was last asked for from outside the app (a tap on a widget), on the
     * clock that counts from when the phone started. Zero if it never was.
     */
    private var playerAskedAt by mutableLongStateOf(0L)

    /**
     * Before Android 12 the phone cannot be told that one app is light or dark, so this
     * screen tells itself: it is built believing the phone is in whichever the user chose.
     * Everything that asks "is it dark?" (the colours, the window behind them, the icons
     * in the status bar) then gets that answer. On newer phones the phone does this for
     * the whole app, before a screen exists, and nothing is done here.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        val night = when ((newBase.applicationContext as MellowApplication).container.appearance.appearance.value) {
            Appearance.Automatic -> return
            Appearance.Light -> Configuration.UI_MODE_NIGHT_NO
            Appearance.Dark -> Configuration.UI_MODE_NIGHT_YES
        }
        // Only the one answer is overridden. Everything else about the phone is left as it is.
        applyOverrideConfiguration(Configuration().apply { uiMode = night })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The other half of the above: a screen can only be told once, as it is built, so
        // a new choice means a new screen. Newer phones rebuild it themselves.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            lifecycleScope.launch { container.appearance.appearance.drop(1).collect { recreate() } }
        }
        // The volume keys always mean music here, even before anything is playing.
        volumeControlStream = AudioManager.STREAM_MUSIC
        // Only a fresh start: a screen being rebuilt (rotated, or brought back from the
        // list of recent apps) still carries the intent it was first opened with, and
        // that was answered then.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) answer(intent)

        setContent {
            MellowTheme {
                MellowApp(container, playerAskedAt = playerAskedAt)
            }
        }
    }

    /** The app was already open when the widget was tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        answer(intent)
    }

    private fun answer(intent: Intent) {
        if (intent.action == MellowWidgets.ActionShowPlayer) playerAskedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Rebinds to the playback service if it is already running in the background, so
        // the UI picks up whatever is playing.
        container.playback.connect()
    }
}
