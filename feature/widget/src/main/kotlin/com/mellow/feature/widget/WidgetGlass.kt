package com.mellow.feature.widget

import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The light on a widget's glass: the line of light along its rim, brightest where the edge
 * faces the lamp, the fainter one where the light leaves through the far edge, and the
 * soft sheen on the curve between.
 *
 * Inside the app glass is drawn by a shader, every frame, and comes in two kinds. One
 * blurs and bends what is behind it. The other, used where there is nothing behind worth
 * bending, is only a tint laid over whatever is there and this same light added on top
 * (`plainGlassSource` in the design system's GlassPainter). A widget can be the second
 * kind and never the first: the launcher that draws it will run no shader of ours, and
 * Android shows a widget nothing of the wallpaper it sits on, so there is nothing to blur
 * or bend. The tint is a see-through colour in the widget's background. The light is
 * worked out here, once per size, with the shader's arithmetic done in plain code, and
 * sent as a picture to lay over the widget.
 *
 * It is the light as it falls with the phone held still: the lamp where the lighting
 * engine rests it. A widget is a still picture; it cannot follow the tilt of the phone or
 * the music as the app's glass does.
 *
 * The picture holds no colour, only how much light there is at each point. Whatever shows
 * it gives the light its colour, so one picture serves the light theme and the dark.
 *
 * Call from one caller at a time: the pictures it keeps are not guarded.
 */
internal class WidgetGlass {

    /** The last few made. A widget is redrawn far more often than it is resized. */
    private val kept = object : LinkedHashMap<String, Bitmap>(Kept, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > Kept
    }

    /**
     * The light on a pane [width] by [height] pixels with corners of [radius] pixels.
     *
     * @param density pixels per dp at the scale the picture is being made at, for the
     *   parts of the recipe that are given in dp.
     * @param recipe which glass it is: the pane, or the button on it.
     */
    fun light(width: Int, height: Int, radius: Float, density: Float, recipe: Recipe = Recipe.Pane): Bitmap {
        val key = "$width|$height|$radius|$density|$recipe"
        kept[key]?.let { return it }

        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val corner = min(radius, min(halfWidth, halfHeight))
        val bezel = min(recipe.bezelDp * density, min(halfWidth, halfHeight))
        val hairline = HairlineDp * density

        // The lamp, a short way off in the direction the key light comes from, and the
        // half-vector between the light and the eye that the sheen is measured against.
        val flat = sqrt(KeyX * KeyX + KeyY * KeyY)
        val reach = max(width, height) * 0.75f
        val lampX = KeyX / flat * reach
        val lampY = KeyY / flat * reach
        val towardEye = KeyZ + 1f
        val halfLength = sqrt(KeyX * KeyX + KeyY * KeyY + towardEye * towardEye)
        val halfX = KeyX / halfLength
        val halfY = KeyY / halfLength
        val halfZ = towardEye / halfLength

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val stride = bitmap.rowBytes
        val levels = ByteArray(stride * height)

        for (row in 0 until height) {
            val y = row + 0.5f - halfHeight
            val cornerY = abs(y) - halfHeight + corner
            for (column in 0 until width) {
                val x = column + 0.5f - halfWidth
                val cornerX = abs(x) - halfWidth + corner

                // How far this point is from the edge of the rounded shape: negative inside.
                val overX = max(cornerX, 0f)
                val overY = max(cornerY, 0f)
                val over = sqrt(overX * overX + overY * overY)
                val distance = over + min(max(cornerX, cornerY), 0f) - corner
                // The flat face of the glass, which is most of it, and everything outside: no light.
                if (distance <= -bezel || distance >= 0.5f) continue

                // Which way is out across the nearest edge.
                var outX: Float
                var outY: Float
                if (over > 0.0001f) {
                    outX = overX / over
                    outY = overY / over
                } else if (cornerX > cornerY) {
                    outX = 1f
                    outY = 0f
                } else {
                    outX = 0f
                    outY = 1f
                }
                if (x < 0f) outX = -outX
                if (y < 0f) outY = -outY

                // 0 where the rim begins, 1 at the very edge.
                val rise = (1f + distance / bezel).coerceIn(0f, 1f)
                val curve = rise * rise

                // The line of light: how squarely the edge here faces the lamp, and its mirror image.
                var toX = lampX - x
                var toY = lampY - y
                var length = sqrt(toX * toX + toY * toY)
                val toward = max((outX * toX + outY * toY) / length, 0f)
                toX = -lampX - x
                toY = -lampY - y
                length = sqrt(toX * toX + toY * toY)
                val away = max((outX * toX + outY * toY) / length, 0f)
                val hair = (1f + distance / hairline).coerceIn(0f, 1f)
                val lit = toward * toward * (0.55f + 0.45f * toward)
                val leaving = away * away * away * 0.5f
                val rim = hair * (0.04f + lit + leaving)

                // The sheen: a highlight on the curve of the rim, to the sixteenth power.
                val tiltX = outX * curve * 1.6f
                val tiltY = outY * curve * 1.6f
                val tilt = sqrt(tiltX * tiltX + tiltY * tiltY + 1f)
                val facing = max((tiltX * halfX + tiltY * halfY + halfZ) / tilt, 0f)
                val facing2 = facing * facing
                val facing4 = facing2 * facing2
                val facing8 = facing4 * facing4
                val sheen = facing8 * facing8 * curve * recipe.sheen

                // The shape's own edge, softened over a pixel.
                val inside = (0.5f - distance).coerceIn(0f, 1f)
                val level = (rim + sheen).coerceIn(0f, 1f) * inside
                levels[row * stride + column] = (level * 255f + 0.5f).toInt().toByte()
            }
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(levels))

        kept[key] = bitmap
        return bitmap
    }

    /**
     * The parts of a glass's recipe that change how its light falls, copied from the
     * design system's GlassStyle. Keep in step with it.
     *
     * @property bezelDp the width of the curved rim. On something as small as a button
     *   the rim is most of it, which is what makes a button look like a bead of glass.
     * @property sheen how strong the sheen is beside the line of light (sheen over rim).
     */
    enum class Recipe(val bezelDp: Float, val sheen: Float) {
        /** Regular glass: the widget itself. */
        Pane(bezelDp = 18f, sheen = 0.38f),

        /** Accent glass: the play button, as the main button on a screen of the app. */
        Button(bezelDp = 16f, sheen = 0.56f),
    }

    private companion object {
        const val Kept = 8

        // The rest of the recipe, the same for every glass. Copied from the design system too.

        /** Where the lighting engine rests the key light (LightingEngine): up and to the left, towards the eye. */
        const val KeyX = -0.46f
        const val KeyY = -0.52f
        const val KeyZ = 0.70f

        /** How wide the line of light is (GlassPainter). */
        const val HairlineDp = 1.25f
    }
}
