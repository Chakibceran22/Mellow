package com.mellow.feature.home.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mellow.core.designsystem.component.Text
import com.mellow.core.designsystem.theme.MellowTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The date, then a title that changes with the time of day. */
@Composable
internal fun HomeHeader(modifier: Modifier = Modifier) {
    val now = remember { LocalDateTime.now() }
    val date = remember(now) { now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) }

    Column(modifier) {
        Text(date, style = MellowTheme.type.callout, color = MellowTheme.colors.inkMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            text = titleFor(now.hour),
            style = MellowTheme.type.display,
            modifier = Modifier.semantics { heading() },
        )
    }
}

private fun titleFor(hour: Int): String = when (hour) {
    in 5..11 -> "Slow morning"
    in 12..16 -> "Easy afternoon"
    in 17..21 -> "Quiet evening"
    else -> "Late hours"
}
