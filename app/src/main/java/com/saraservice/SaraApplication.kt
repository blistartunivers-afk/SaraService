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
        @Suppress("UNUSED_PARAMETER")
        fun getContext(): Context = SaraApplication.instance!!
        private var instance: SaraApplication? = null
        init {
            instance = this
        }
    }
}
