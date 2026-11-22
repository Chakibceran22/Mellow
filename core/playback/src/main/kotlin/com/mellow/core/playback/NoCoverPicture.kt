package com.mellow.core.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat

/**
 * The picture that stands in for a cover wherever the music is shown outside the app: the
 * phone's own player card, the lock screen, a widget on the home screen. Plenty of music
 * has no cover; rather than leave those places blank, they get the Mellow mark on the
 * app's own pale green, so what is there always looks like Mellow's.
 *
 * Inside the app a track with no cover gets a plain tile with a note on it instead. That
 * tile is drawn by the design system, in the colours of the screen around it; out here
 * there is no screen of ours around it, so the picture brings its own.
 */
object NoCoverPicture {

    /** Draws it [side] pixels square, the mark in the exact middle. A new bitmap every time: keep it if it is needed again. */
    fun draw(context: Context, side: Int): Bitmap =
        Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Cream)
            val inset = (side * (1f - MarkShare) / 2f).toInt()
            ContextCompat.getDrawable(context, R.drawable.mellow_mark)?.apply {
                setBounds(inset, inset, side - inset, side - inset)
                draw(canvas)
            }
        }

    /** How much of the picture's width the mark takes. */
    private const val MarkShare = 0.62f

    /** The palette's Cream. This module has no view of the design system's colours. */
    private const val Cream = 0xFFE6F1E0.toInt()
}
