package com.thrive.app

import android.app.Application
import android.content.Context
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.update.UpdateNotifier
import com.thrive.app.update.UpdateScheduler

class ThriveApp : Application() {

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(getSharedPreferences("thrive_settings", Context.MODE_PRIVATE))
        UpdateNotifier.ensureChannel(this)
        UpdateScheduler.schedule(this)
    }
}
