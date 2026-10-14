package com.saraservice

import android.app.Application
import android.content.Context
import android.util.Log

class SaraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("SaraService", "Application started")
    }

    companion object {
        @Volatile private var instance: SaraApplication? = null
        fun getContext(): Context = instance ?: throw IllegalStateException("Application not initialized")
        
        fun setInstance(app: SaraApplication) {
            instance = app
        }
    }

    init {
        instance = this
    }
}