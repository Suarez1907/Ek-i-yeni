package com.example.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Awaits an OkHttp [Call] without blocking a thread inside [kotlinx.coroutines.withTimeout].
 * Cancelling the coroutine cancels the HTTP call, which unblocks a stuck `execute()` or
 * `readLine()` on the response body.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isActive) return
            if (call.isCanceled()) {
                continuation.resumeWithException(CancellationException("OkHttp call cancelled", e))
            } else {
                continuation.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}
