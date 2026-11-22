package com.mellow.app

import android.app.Application
import com.mellow.feature.widget.MellowWidgets
import com.mellow.feature.widget.WidgetHost

class MellowApplication : Application(), WidgetHost {

    /** The app's object graph. Created once, with the process. */
    lateinit var container: AppContainer
        private set

    /**
     * The widgets on the home screen. They belong to the process, not to a screen: the
     * system can start the app for a tap on one with no screen open at all.
     */
    override lateinit var widgets: MellowWidgets
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        widgets = MellowWidgets(this, container.playback).also(MellowWidgets::start)
    }
}
