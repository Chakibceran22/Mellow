package com.mellow.core.designsystem.light

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import com.mellow.core.designsystem.theme.MellowTheme
import kotlin.math.ceil

/**
 * The room is lit at a quarter of the screen's resolution in each direction and stretched
 * to fit. Its light has no detail finer than a few centimetres, so the result is the same
 * picture for one sixteenth of the pixel work. On a mid-range GPU this is the difference
 * between holding the display's refresh rate and missing every frame.
 */
private const val Downscale = 4f

/**
 * The living background: an opaque surface lit by the app's [LightingEngine].
 *
 * It is rendered into its own small offscreen buffer once per frame of light. Everything
 * that shows it, the screen itself and each piece of glass looking through to it, reuses
 * that one buffer instead of running the lighting again.
 *
 * @param vividness scales the lamps. 1 is the calm level used behind lists; the player
 *   turns it up so the room takes on more of the track's colour.
 * @param active set false while this room is completely covered, so it holds its last
 *   frame instead of being lit again for nobody.
 */
@Composable
fun LightField(modifier: Modifier = Modifier, vividness: Float = 1f, active: Boolean = true) {
    val engine = LocalLighting.current
    val scheme = MellowTheme.lighting

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val painter = remember { LightFieldPainter() }
        val layer = rememberGraphicsLayer()
        Spacer(
            modifier.drawBehind {
                val small = IntSize(ceil(size.width / Downscale).toInt(), ceil(size.height / Downscale).toInt())
                // Reading the tick subscribes this block to the engine's frames. A covered
                // room skips the read, and so is never asked to redraw.
                if (active || layer.size != small) {
                    engine.tick
                    layer.compositingStrategy = CompositingStrategy.Offscreen
                    layer.record(size = small) {
                        with(painter) { render(engine, scheme, vividness) }
                    }
                    layer.pivotOffset = Offset.Zero
                    layer.scaleX = size.width / small.width
                    layer.scaleY = size.height / small.height
                }
                drawLayer(layer)
            },
        )
    } else {
        Spacer(
            modifier.drawBehind {
                if (active) engine.tick
                paintFlatRoom(engine, scheme, vividness)
            },
        )
    }
}

/**
 * Before Android 13 there are no runtime shaders. The lamps still move, drawn as flat
 * discs of their own colour on the canvas colour, which keeps the room alive and on-brand
 * without pretending to be lit.
 */
private fun DrawScope.paintFlatRoom(engine: LightingEngine, scheme: LightingScheme, vividness: Float) {
    drawRect(scheme.canvas)
    val lamps = engine.lamps
    for (index in 0 until LightingEngine.LampCount) {
        val i = index * 4
        val alpha = (lamps[i + 3] * scheme.lampStrength * vividness * 0.6f).coerceIn(0f, 1f)
        drawCircle(
            color = scheme.lamps[index].copy(alpha = alpha),
            radius = size.width * lamps[i + 2] * 0.9f,
            center = Offset(lamps[i] * size.width, lamps[i + 1] * size.height),
        )
    }
}
