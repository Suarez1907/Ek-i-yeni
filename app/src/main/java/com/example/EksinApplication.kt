package com.example

import android.app.Application
import com.example.di.AppModule

class EksinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppModule.getContainer(this)
    }
}
