package com.example.network

import android.content.Context
import android.content.Intent

/**
 * Firebase / Google Services bağımlılığı kaldırıldığı için no-op yapılandırma.
 * Uygulama doğrudan Google AI Studio Gemini API Key (OkHttp REST / SharedPreferences) kullanır.
 */
@Suppress("UNUSED_PARAMETER")
fun configureAppCheck(context: Context, intent: Intent?) {
    // No-op: Firebase ve google-services.json kullanılmamaktadır.
}
