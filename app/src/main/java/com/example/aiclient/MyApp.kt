package com.example.aiclient

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager


class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channelId = "audio_service_channel"
        val channelName = "Audio Service Channel"
        val notificationChannel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(notificationChannel)
    }
}
