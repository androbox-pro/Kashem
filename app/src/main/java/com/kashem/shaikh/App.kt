package com.kashem.shaikh

import android.app.Application
import android.os.Bundle

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        AppLogs.init(this)

        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {
                AppLogs.logActivityLifecycle(activity.javaClass.simpleName, "onCreate")
            }

            override fun onActivityStarted(activity: android.app.Activity) {
                AppLogs.logActivityLifecycle(activity.javaClass.simpleName, "onStart")
            }

            override fun onActivityResumed(activity: android.app.Activity) {
                AppLogs.logActivityLifecycle(activity.javaClass.simpleName, "onResume")
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                AppLogs.logActivityLifecycle(activity.javaClass.simpleName, "onPause")
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                AppLogs.logActivityLifecycle(activity.javaClass.simpleName, "onStop")
            }

            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {
                // প্রয়োজন হলে লগ করুন
            }

            override fun onActivityDestroyed(activity: android.app.Activity) {
                AppLogs.logActivityLifecycle(activity.javaClass.simpleName, "onDestroy")
            }
        })
    }
}