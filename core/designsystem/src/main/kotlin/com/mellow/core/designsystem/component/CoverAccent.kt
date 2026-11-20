package com.mellow.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The colour a cover is mostly about, for tinting the room while its track plays.
 *
 * Null while it is being worked out, and for covers with no colour to speak of (missing,
 * black and white, washed out), where the room should keep its own light. Worked out from
 * a thumbnail the size of a fingernail, on a background thread, once per cover.
 *
 * The previous cover's colour is held until the new one is known, so a track change
 * crosses from one tint to the next instead of dropping to none in between.
 */
@Composable
fun rememberCoverAccent(uri: String?): Color? {
    val context = LocalPlatformContext.current
    return produceState<Color?>(initialValue = null, uri, context) {
        value = if (uri.isNullOrEmpty()) null else coverAccent(context, uri)
    }.value
}

private suspend fun coverAccent(context: PlatformContext, uri: String): Color? {
    val request = ImageRequest.Builder(context)
        .data(uri)
        .size(SampleSide)
        // Pixels have to be readable, which a bitmap living on the GPU is not.
        .allowHardware(false)
        .build()
    val bitmap = SingletonImageLoader.get(context).execute(request).image?.toBitmap() ?: return null
    return withContext(Dispatchers.Default) { vividAverage(bitmap) }
}

/**
 * Averages the pixels, letting each count for as much as it is colourful. A plain average
 * of a cover is nearly always mud; this lands on the colour the eye picks out instead.
 */
private fun vividAverage(bitmap: Bitmap): Color? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

    var red = 0f
    var green = 0f
    var blue = 0f
    var total = 0f
    for (pixel in pixels) {
        val r = (pixel shr 16 and 0xFF) / 255f
        val g = (pixel shr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val brightest = max(r, max(g, b))
        val chroma = brightest - min(r, min(g, b))
        // Colourful, and neither nearly black nor blown out.
        val weight = chroma * chroma * (1f - abs(brightest - 0.65f))
        red += r * weight
        green += g * weight
        blue += b * weight
        total += weight
    }
    // Too little colour anywhere to call it a colour.
    if (total < pixels.size * MinColourfulness) return null
    return Color(red / total, green / total, blue / total)
}

private const val SampleSide = 24
private const val MinColourfulness = 0.004f
