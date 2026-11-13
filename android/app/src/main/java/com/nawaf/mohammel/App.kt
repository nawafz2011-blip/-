package com.nawaf.mohammel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        History.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "التحميلات", NotificationManager.IMPORTANCE_LOW))
        // إشعار (خلص التحميل) بصوت عادي عشان تنتبه له
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "خلص التحميل", NotificationManager.IMPORTANCE_DEFAULT))
        thread(name = "engine-init") {
            Engine.init(this)
            if (Engine.initError == null) Engine.updateIfNeeded(this)
        }
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val CHANNEL_DONE = "done"
    }
}
