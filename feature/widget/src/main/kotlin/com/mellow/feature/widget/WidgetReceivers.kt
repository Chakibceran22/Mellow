package com.mellow.feature.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * What the system talks to about a widget. It says when one has been placed, resized, or
 * needs drawing because the phone has just started; the answer to all three is the same,
 * so all three ask [MellowWidgets] to draw.
 *
 * The broadcast is held open until the drawing has been sent. A process woken only for
 * this has nothing else keeping it, and could be put to sleep half way through.
 */
abstract class MellowWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = redraw(context)

    private fun redraw(context: Context) {
        val widgets = (context.applicationContext as? WidgetHost)?.widgets ?: return
        val held = goAsync()
        widgets.redraw(held::finish)
    }
}

/** The entry that starts as a bar: four cells wide, one high. */
class PlayerWidget : MellowWidget()

/** The entry that starts as a cover: two cells by two. */
class CoverWidget : MellowWidget()

/**
 * Where a tap on one of a widget's buttons arrives. The tap may be what started the app,
 * so this is held open until the press has landed; see [MellowWidgets.press].
 */
class WidgetControls : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val widgets = (context.applicationContext as? WidgetHost)?.widgets ?: return
        val held = goAsync()
        widgets.press(action, held::finish)
    }

    internal companion object {
        const val Toggle = "com.mellow.feature.widget.TOGGLE"
        const val Next = "com.mellow.feature.widget.NEXT"
        const val Previous = "com.mellow.feature.widget.PREVIOUS"
    }
}
