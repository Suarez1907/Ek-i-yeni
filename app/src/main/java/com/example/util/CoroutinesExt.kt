package com.example.util

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] swallows [CancellationException] and turns coroutine cancellation into a
 * normal failure. Use this around suspend work so cancellation still propagates.
 */
inline fun <R> runCatchingUnlessCancelled(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

/**
 * Converts a thrown error into [Result.failure] unless it is cancellation, which must
 * keep propagating to the caller.
 */
fun <T> failureUnlessCancelled(error: Throwable): Result<T> {
    if (error is CancellationException) throw error
    return Result.failure(error)
}
