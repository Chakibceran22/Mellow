package com.mellow.app

import android.app.Application

class MellowApplication : Application() {

    /** The app's object graph. Created once, with the process. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
