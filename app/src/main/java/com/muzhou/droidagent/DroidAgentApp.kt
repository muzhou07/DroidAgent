package com.muzhou.droidagent

import android.app.Application
import com.muzhou.droidagent.data.AppContainer

class DroidAgentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
