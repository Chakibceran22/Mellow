package com.mellow.app

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.mellow.app.ui.MellowApp
import com.mellow.core.designsystem.theme.MellowTheme
import com.mellow.feature.widget.MellowWidgets

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as MellowApplication).container

    /**
     * When the player was last asked for from outside the app (a tap on a widget), on the
     * clock that counts from when the phone started. Zero if it never was.
     */
    private var playerAskedAt by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
