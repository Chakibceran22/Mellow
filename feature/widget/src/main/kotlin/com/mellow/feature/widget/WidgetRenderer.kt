package com.mellow.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.RemoteViews
import com.mellow.core.model.Appearance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a widget has to say: the song that is on, and what its buttons can do. */
internal data class Shown(
    val title: String,
    val artist: String,
    val artworkUri: String,
    val isPlaying: Boolean,
    val hasNext: Boolean,
)

/**
 * Turns what is on into the description of a widget that the launcher draws.
 *
 * A widget can be any size the user drags it to, so there is not one layout but three,
 * and the size decides between them:
 *
 * - a **bar**, when it is one row high: cover, song, buttons, side by side;
 * - a **card**, when it is two rows high and wider than that: the whole cover down one
 *   side, as tall as the widget, the song and the buttons beside it;
 * - a **cover**, for everything else (square, tall, or too narrow for a card): the cover
 *   fills the widget, cropped to it, and the song sits in a strip under it.
 *
 * Within each, what there is no room for is left out. The back button is the first to
 * go and the play button is never left out; see [plan] for the order in between.
 *
 * Sizes are what the launcher reports, which is close but not exact, so nothing here
 * depends on being right to the pixel: the layouts stretch, and the sizes only pick
 * between them and say how sharp a picture to make.
 *
 * Every one of them is a pane of glass: a see-through tint (the layouts' background) with
 * the light of the app's glass on its rim ([WidgetGlass]). It is pale glass or dark glass
 * with the phone's theme, unless the user has chosen one of the two for the app.
 *
 * Blocking, and reads files. Call it off the main thread, one call at a time.
 */
internal class WidgetRenderer(context: Context) {

    private val context = context.applicationContext
    private val covers = WidgetCovers(this.context)
    private val glass = WidgetGlass()
    private val intents = Intents()

    /** The look of the widget being described. Set at the start of each [render]. */
    private var tone = Appearance.Automatic

    /**
     * @param shown what is on, or null when there is nothing to play.
     * @param appearance the look the user has chosen for the app. A widget wears it too.
     * @param options what the launcher says about this widget, its size above all.
     * @param fallback the size to assume when the launcher says nothing, in dp.
     */
    fun render(shown: Shown?, appearance: Appearance, options: Bundle, fallback: Size): RemoteViews {
        tone = appearance
        // The launcher gives a range: the widget is narrowest and tallest with the phone
        // upright, widest and shortest on its side.
        val upright = size(options, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: fallback
        val sideways = size(options, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: fallback
        if (shown == null) return RemoteViews(nothing(sideways), nothing(upright))
        val uprightPlan = plan(upright)
        val sidewaysPlan = plan(sideways)
        val uprightViews = views(uprightPlan, shown, upright)
        // One description does for both ways up only if nothing in it was made to a size.
        val same = sidewaysPlan == uprightPlan && sideways == upright
        return if (same) uprightViews else RemoteViews(views(sidewaysPlan, shown, sideways), uprightViews)
    }

    private fun size(options: Bundle, widthKey: String, heightKey: String): Size? {
        val width = options.getInt(widthKey)
        val height = options.getInt(heightKey)
        return if (width > 0 && height > 0) Size(width, height) else null
    }

    // ── Deciding ────────────────────────────────────────────────────────────

    private sealed interface Plan {
        /** @property cover the side of the cover in pixels, or 0 when there is no room for one. */
        data class Bar(val cover: Int, val radius: Float, val words: Boolean, val previous: Boolean, val next: Boolean) : Plan

        data class Card(val cover: Int, val radius: Float, val previous: Boolean) : Plan

        /** @property radius how round the picture's top corners are made; 0 where the widget rounds them itself. */
        data class Cover(val width: Int, val height: Int, val radius: Float, val words: Boolean, val previous: Boolean, val next: Boolean) : Plan
    }

    /** Picks the layout for a widget [size] dp large, and what of it there is room for. */
    private fun plan(size: Size): Plan {
        val width = size.width
        val height = size.height

        if (height < TallFrom) {
            val padding = dp(R.dimen.widget_bar_padding)
            val side = (height - 2 * padding).coerceIn(MinCover, MaxBarCover)
            // The play button first. The cover gives way only when the two cannot both fit.
            val cover = if (width - padding - BarEndPadding - ToggleWidth >= side) side else 0
            var room = width - padding - BarEndPadding - cover - ToggleWidth
            val next = room >= StepWidth
            if (next) room -= StepWidth
            val words = cover > 0 && room >= WordsMargin + MinWords
            if (words) room -= WordsMargin + MinWords
            val previous = words && room >= StepWidth
            return Plan.Bar(pixels(cover), cornerOf(side, padding), words, previous, next)
        }

        val padding = dp(R.dimen.widget_card_padding)
        val side = height - 2 * padding
        // A card shows the whole cover, as tall as itself, and needs room beside it for
        // play and forward at the least. Back joins them when there is room for three.
        val column = width - 2 * padding - side - CardGap
        if (side <= MaxCardCover && column >= CardColumn) {
            return Plan.Card(pixels(side), cornerOf(side, padding), previous = column >= CardColumn + StepWidth)
        }

        val pictureHeight = (height - dp(R.dimen.widget_band_height)).coerceAtLeast(MinCover)
        var room = width - BandStartPadding - BandEndPadding - ToggleWidth
        val words = room >= MinWords
        if (words) room -= MinWords
        val next = words && room >= StepWidth
        if (next) room -= StepWidth
        val previous = next && room >= StepWidth
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Square, and cropped by the widget to whatever space it really has.
            val square = pixels(max(width, pictureHeight))
            Plan.Cover(square, square, radius = 0f, words, previous, next)
        } else {
            // Made to the shape of the space, top corners and all, and stretched to fit it.
            val scale = min(1f, MaxPicture / (max(width, pictureHeight) * density))
            Plan.Cover(
                width = (width * density * scale).roundToInt().coerceAtLeast(1),
                height = (pictureHeight * density * scale).roundToInt().coerceAtLeast(1),
                radius = context.resources.getDimension(R.dimen.widget_radius) * scale,
                words, previous, next,
            )
        }
    }

    /**
     * How round to make a cover that sits [padding] dp inside the widget's edge, so that
     * its corners follow the widget's own: the same centre, a smaller circle. Given for
     * the picture that [pixels] sizes, in that picture's pixels.
     */
    private fun cornerOf(side: Int, padding: Int): Float {
        val outer = context.resources.getDimension(R.dimen.widget_radius) / density
        val inner = (outer - padding).coerceIn(MinCorner, side * MaxCornerShare)
        return inner * pixels(side) / side
    }

    // ── Describing ──────────────────────────────────────────────────────────

    /** A widget [size] dp large with nothing to play: it says so, and opens the app. */
    private fun nothing(size: Size): RemoteViews {
        // With more than a row to itself, the mark goes above the words and both sit in the middle.
        val stacked = size.height >= TallFrom
        return RemoteViews(context.packageName, if (stacked) Layout.EmptyStacked.inTone() else Layout.Empty.inTone()).apply {
            light(size)
            setOnClickPendingIntent(android.R.id.background, intents.app)
        }
    }

    private fun views(plan: Plan, shown: Shown, size: Size): RemoteViews = when (plan) {
        is Plan.Bar -> RemoteViews(context.packageName, Layout.Bar.inTone()).apply {
            light(size)
            if (plan.cover > 0) setImageViewBitmap(R.id.widget_cover, covers.picture(shown.artworkUri, plan.cover, plan.cover, plan.radius))
            setViewVisibility(R.id.widget_cover, visibleIf(plan.cover > 0))
            setViewVisibility(R.id.widget_gap, visibleIf(!plan.words))
            song(shown, plan.words)
            buttons(shown, plan.previous, plan.next)
        }

        is Plan.Card -> RemoteViews(context.packageName, Layout.Card.inTone()).apply {
            light(size)
            setImageViewBitmap(R.id.widget_cover, covers.picture(shown.artworkUri, plan.cover, plan.cover, plan.radius))
            song(shown, words = true)
            buttons(shown, plan.previous, next = true)
        }

        is Plan.Cover -> RemoteViews(context.packageName, Layout.Cover.inTone()).apply {
            light(size)
            setImageViewBitmap(R.id.widget_cover, covers.picture(shown.artworkUri, plan.width, plan.height, plan.radius, bottomRadius = 0f))
            song(shown, plan.words)
            buttons(shown, plan.previous, plan.next)
        }
    }

    /**
     * Lays the light of the glass over a widget [size] dp large: see [WidgetGlass]. It is
     * made to the widget's size, so that the line of light sits exactly on its rim, and
     * stretched over it, so that it still does if the launcher's idea of the size is a
     * little out.
     */
    private fun RemoteViews.light(size: Size) {
        val scale = min(1f, MaxLight / (max(size.width, size.height) * density))
        val scaled = density * scale
        setImageViewBitmap(
            R.id.widget_light,
            glass.light(
                width = (size.width * scaled).roundToInt().coerceAtLeast(1),
                height = (size.height * scaled).roundToInt().coerceAtLeast(1),
                radius = context.resources.getDimension(R.dimen.widget_radius) * scale,
                density = scaled,
            ),
        )
    }

    /**
     * Each layout, in its three looks. The first follows the phone's theme by itself, and
     * is what a widget is given while the app is set to do the same. The other two are
     * the same layout held to one look, for when the user has chosen it: the launcher
     * that draws a widget knows the phone's theme and nothing of this app's settings, so
     * a look that differs from the phone's has to be asked for by name.
     */
    private enum class Layout(val followsPhone: Int, val light: Int, val dark: Int) {
        Bar(R.layout.widget_bar, R.layout.widget_bar_light, R.layout.widget_bar_dark),
        Card(R.layout.widget_card, R.layout.widget_card_light, R.layout.widget_card_dark),
        Cover(R.layout.widget_cover, R.layout.widget_cover_light, R.layout.widget_cover_dark),
        Empty(R.layout.widget_empty, R.layout.widget_empty_light, R.layout.widget_empty_dark),
        EmptyStacked(R.layout.widget_empty_stacked, R.layout.widget_empty_stacked_light, R.layout.widget_empty_stacked_dark),
    }

    private fun Layout.inTone(): Int = when (tone) {
        Appearance.Automatic -> followsPhone
        Appearance.Light -> light
        Appearance.Dark -> dark
    }

    private fun RemoteViews.song(shown: Shown, words: Boolean) {
        setViewVisibility(R.id.widget_words, visibleIf(words))
        setTextViewText(R.id.widget_title, shown.title)
        setTextViewText(R.id.widget_artist, shown.artist)
        setViewVisibility(R.id.widget_artist, visibleIf(shown.artist.isNotBlank()))
    }

    private fun RemoteViews.buttons(shown: Shown, previous: Boolean, next: Boolean) {
        // The play button is a bead of glass of its own, lit the same way as the pane it sits on.
        val disc = (DiscSize * density).roundToInt()
        setImageViewBitmap(R.id.widget_toggle_glass, glass.light(disc, disc, disc / 2f, density, WidgetGlass.Recipe.Button))
        setImageViewResource(R.id.widget_toggle_icon, if (shown.isPlaying) R.drawable.widget_pause else R.drawable.widget_play)
        setContentDescription(R.id.widget_toggle, context.getString(if (shown.isPlaying) R.string.widget_pause else R.string.widget_play))
        setOnClickPendingIntent(R.id.widget_toggle, intents.toggle)

        // Back is never dimmed: at the first track it still starts the song again.
        setViewVisibility(R.id.widget_previous, visibleIf(previous))
        setOnClickPendingIntent(R.id.widget_previous, intents.previous)

        setViewVisibility(R.id.widget_next, visibleIf(next))
        setInt(R.id.widget_next, "setImageAlpha", if (shown.hasNext) Opaque else Dimmed)
        setBoolean(R.id.widget_next, "setEnabled", shown.hasNext)
        setOnClickPendingIntent(R.id.widget_next, intents.next)

        // Anywhere that is not a button opens the app, on the player.
        setOnClickPendingIntent(android.R.id.background, intents.player)
    }

    private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    private val density: Float get() = context.resources.displayMetrics.density

    private fun dp(dimension: Int): Int = (context.resources.getDimension(dimension) / density).roundToInt()

    /** How many pixels to make a picture that is shown [dp] across. Sharp, but never larger than a widget needs. */
    private fun pixels(dp: Int): Int = min((dp * density).roundToInt(), MaxPicture.toInt())

    /**
     * What each tap sends. Made once: a pending intent is a token the system keeps, and
     * asking again for the same one hands back the same token.
     */
    private inner class Intents {
        val toggle = control(WidgetControls.Toggle, 1)
        val next = control(WidgetControls.Next, 2)
        val previous = control(WidgetControls.Previous, 3)

        /** Opens the app as its icon would. */
        val app: PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            PendingIntent.getActivity(context, 0, launch, Flags)
        }

        /** Opens the app with the player up. Addressed to the same screen, under an action of its own so that it is told apart from a tap on the icon. */
        val player: PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.let { screen ->
            val show = Intent(MellowWidgets.ActionShowPlayer).setComponent(screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(context, 1, show, Flags)
        }

        private fun control(action: String, code: Int): PendingIntent {
            val press = Intent(context, WidgetControls::class.java)
                .setAction(action)
                // A finger is waiting on it.
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            return PendingIntent.getBroadcast(context, code, press, Flags)
        }
    }

    private companion object {
        const val Flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        // All in dp. The widths are those of the views in res/layout; keep the two in step.

        /** At this height and above a widget is no longer a bar. */
        const val TallFrom = 110

        const val MinCover = 24
        const val MaxBarCover = 72
        const val MaxCardCover = 220

        const val ToggleWidth = 52

        /** The play button's glass, inside the place to press. */
        const val DiscSize = 40
        const val StepWidth = 44
        const val BarEndPadding = 6
        const val WordsMargin = 16

        /** The least width worth giving to the song's name. */
        const val MinWords = 72

        /** The gap between a card's cover and what is beside it. */
        const val CardGap = 14

        /** The least a card needs beside its cover: play and forward, less the pull that brings forward's icon to the card's edge. */
        const val CardColumn = ToggleWidth + StepWidth - 11

        const val BandStartPadding = 14
        const val BandEndPadding = 2

        const val MinCorner = 6f

        /** A small cover is never rounded by more than this much of its side: beyond it, it starts to look like a disc. */
        const val MaxCornerShare = 0.26f

        /** No picture is made larger than this, in pixels, however large the widget. */
        const val MaxPicture = 720f

        /** The same for the light on the glass, which covers the whole widget and has a fine line in it to keep sharp. */
        const val MaxLight = 1400f

        const val Opaque = 255
        const val Dimmed = 82
    }
}
