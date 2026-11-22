package com.mellow.feature.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import com.mellow.core.playback.NoCoverPicture
import kotlin.math.max
import kotlin.math.min

/**
 * Makes the picture a widget shows: the song's cover, read from the phone, cropped to the
 * space it is for and with its corners already rounded.
 *
 * The rounding is done here, into the picture, because a widget cannot do it: the views
 * the launcher builds for it have no way to clip a picture to a curve on most versions of
 * Android. So what is sent is a finished bitmap, and the launcher only has to place it.
 *
 * Everything here reads files and fills bitmaps. Call it off the main thread, and from
 * one caller at a time: the pictures it keeps are not guarded.
 */
internal class WidgetCovers(context: Context) {

    private val context = context.applicationContext

    /**
     * The last few pictures made. Pressing pause redraws the widget with the same cover,
     * and so does every widget after the first when several show the same song.
     */
    private val kept = object : LinkedHashMap<String, Bitmap>(Kept, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > Kept
    }

    /**
     * The cover at [uri], [width] by [height] pixels, cropped about its middle to fill
     * that. Music with no cover (and there is plenty) gets the [NoCoverPicture] instead.
     *
     * @param topRadius how round to make the top two corners, in pixels.
     * @param bottomRadius the same for the bottom two.
     */
    fun picture(uri: String, width: Int, height: Int, topRadius: Float, bottomRadius: Float = topRadius): Bitmap {
        val key = "$uri|$width|$height|$topRadius|$bottomRadius"
        kept[key]?.let { return it }

        val source = read(uri, max(width, height)) ?: NoCoverPicture.draw(context, min(max(width, height), NoCoverSide))
        val shaped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Scaled to cover the space, and whatever hangs over the edges is lost equally from both sides.
        val scale = max(width / source.width.toFloat(), height / source.height.toFloat())
        val placed = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(placed) }
        }
        val outline = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                floatArrayOf(topRadius, topRadius, topRadius, topRadius, bottomRadius, bottomRadius, bottomRadius, bottomRadius),
                Path.Direction.CW,
            )
        }
        Canvas(shaped).drawPath(outline, paint)
        source.recycle()

        kept[key] = shaped
        return shaped
    }

    /**
     * Decodes the picture at [uri], no larger than it needs to be: a cover can be several
     * thousand pixels across, and the widget shows it a few hundred wide. Null when there
     * is nothing there to decode.
     */
    private fun read(uri: String, side: Int): Bitmap? = runCatching {
        if (uri.isEmpty()) return null
        val address = Uri.parse(uri)
        val resolver = context.contentResolver

        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(address)?.use { BitmapFactory.decodeStream(it, null, size) }
        if (size.outWidth <= 0 || size.outHeight <= 0) return null

        // Halved for as long as the smaller side stays at least as large as what is wanted.
        var sample = 1
        while (min(size.outWidth, size.outHeight) / (sample * 2) >= side) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(address)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private companion object {
        const val Kept = 6

        /** The no-cover picture is drawn from shapes and is sharp at any size; this is as large as it is ever needed. */
        const val NoCoverSide = 512
    }
}
