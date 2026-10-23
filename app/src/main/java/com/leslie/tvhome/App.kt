package com.leslie.tvhome

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Updates.schedule(this)
        val sp = Updates.prefs(this)
        if (System.currentTimeMillis() - sp.getLong("lastLaunchCheck", 0) > 6 * 60 * 60 * 1000L) {
            sp.edit().putLong("lastLaunchCheck", System.currentTimeMillis()).apply()
            Updates.executor.execute { Updates.check(this) }
        }
    }
}
