package com.mellow.feature.home.component

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.mellow.core.data.LibraryAccess
import com.mellow.core.designsystem.component.Button
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.theme.MellowTheme

/**
 * Shown on Home until Mellow may read the phone's music. It says what will happen and why,
 * then asks with the system's own dialog.
 *
 * Android stops showing that dialog once it has been refused twice. From then on the only
 * way in is the app's page in Settings, so the button changes to take the user there.
 *
 * @param onAnswered called once the user has answered, whichever way.
 */
@Composable
internal fun AccessPrompt(onAnswered: () -> Unit, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    var systemWillNotAsk by rememberSaveable { mutableStateOf(false) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && activity != null) {
            // After a refusal Android says whether it is still willing to ask again.
            systemWillNotAsk = !ActivityCompat.shouldShowRequestPermissionRationale(activity, LibraryAccess.permission)
        }
        onAnswered()
    }

    Notice(
        title = "Play what is already here",
        message = if (systemWillNotAsk) {
            "Mellow plays the music stored on this phone, and it needs your permission to read it. " +
                "Turn on Music and audio for Mellow in Settings."
        } else {
            "Mellow plays the music stored on this phone. Allow it to read your audio files and they " +
                "will appear here. Nothing is uploaded and nothing leaves the phone."
        },
        modifier = modifier,
    ) {
        Button(
            text = if (systemWillNotAsk) "Open Settings" else "Allow access to music",
            onClick = {
                if (systemWillNotAsk) {
                    activity?.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
                    )
                } else {
                    ask.launch(LibraryAccess.permission)
                }
            },
        )
    }
}

/** Allowed in, and the phone has no music on it. */
@Composable
internal fun EmptyLibrary(modifier: Modifier = Modifier) {
    Notice(
        title = "No music on this phone yet",
        message = "Copy audio files to the phone, into its Music folder for instance, and they will " +
            "show up here on their own. There is nothing to import.",
        modifier = modifier,
    )
}

@Composable
private fun Notice(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = MellowTheme.spacing
    Column(modifier) {
        Text(title, style = MellowTheme.type.title, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(spacing.xs))
        Text(message, color = MellowTheme.colors.inkMuted, modifier = Modifier.widthIn(max = 340.dp))
        if (action != null) {
            Spacer(Modifier.height(spacing.lg))
            action()
        }
    }
}
