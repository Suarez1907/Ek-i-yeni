package com.example.network

import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "AppCheckInit"

fun configureAppCheck(context: Context, intent: Intent?) {
    Log.d(TAG, "Initialized network security context for ${context.packageName} (intent=${intent?.action})")
}
