package com.example.screentranslator

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TranslationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}
