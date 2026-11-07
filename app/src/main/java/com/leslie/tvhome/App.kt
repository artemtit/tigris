package com.leslie.tvhome

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.initialize(this)
        Updates.schedule(this)
        val sp = Updates.prefs(this)
        if (System.currentTimeMillis() - sp.getLong("lastLaunchCheck", 0) > 6 * 60 * 60 * 1000L) {
            sp.edit().putLong("lastLaunchCheck", System.currentTimeMillis()).apply()
            val check = Runnable { Updates.executor.execute { Updates.check(this) } }
            // Just after boot the network and CPU belong to the home screen for a minute or so.
            val wait = Device.settling()
            if (wait > 0) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(check, wait + 60_000L) else check.run()
        }
    }
}
