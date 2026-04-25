package com.example.rocketplan_android.realtime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether the app currently has any started activities.
 * This is sufficient for deciding whether we should keep long-lived realtime sockets alive.
 */
class AppVisibilityTracker private constructor(
    application: Application
) : Application.ActivityLifecycleCallbacks {

    private val startedActivities = AtomicInteger(0)
    private val isForeground = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun isAppForeground(): Boolean = isForeground.get()

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    override fun onActivityStarted(activity: Activity) {
        val count = startedActivities.incrementAndGet()
        if (count == 1 && isForeground.compareAndSet(false, true)) {
            notifyListeners(true)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val count = startedActivities.decrementAndGet().coerceAtLeast(0)
        startedActivities.set(count)
        if (count == 0 && isForeground.compareAndSet(true, false)) {
            notifyListeners(false)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun notifyListeners(foreground: Boolean) {
        listeners.forEach { it(foreground) }
    }

    companion object {
        @Volatile
        private var instance: AppVisibilityTracker? = null

        fun getInstance(context: Context): AppVisibilityTracker {
            return instance ?: synchronized(this) {
                instance ?: AppVisibilityTracker(
                    context.applicationContext as Application
                ).also { instance = it }
            }
        }
    }
}
