package com.example.di

import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets the process drop in-memory UI caches without the singleton retaining the ViewModel.
 * The ViewModel keeps the lambda alive; this object only holds a [WeakReference].
 */
@Singleton
class ViewModelCacheCleaner @Inject constructor() {
    private var actionRef: WeakReference<(() -> Unit)>? = null

    fun register(cleaner: () -> Unit) {
        actionRef = WeakReference(cleaner)
    }

    fun unregister(cleaner: () -> Unit) {
        if (actionRef?.get() === cleaner) {
            actionRef = null
        }
    }

    fun clear() {
        actionRef?.get()?.invoke()
    }
}
