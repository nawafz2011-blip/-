package com.nawaf.mohammel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "التحميلات", NotificationManager.IMPORTANCE_LOW)
        )
        thread(name = "engine-init") {
            Engine.init(this)
            if (Engine.initError == null) Engine.updateIfNeeded(this)
        }
    }

    companion object {
        const val CHANNEL_ID = "downloads"
    }
}
