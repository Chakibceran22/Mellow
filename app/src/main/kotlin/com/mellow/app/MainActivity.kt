package com.mellow.app

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mellow.app.ui.MellowApp
import com.mellow.core.designsystem.theme.MellowTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as MellowApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The volume keys always mean music here, even before anything is playing.
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContent {
            MellowTheme {
                MellowApp(container)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Rebinds to the playback service if it is already running in the background, so
        // the UI picks up whatever is playing.
        container.playback.connect()
    }
}
