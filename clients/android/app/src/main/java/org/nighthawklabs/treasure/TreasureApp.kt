package org.nighthawklabs.treasure

import android.app.Application
import org.nighthawklabs.treasure.auth.Auth
import org.nighthawklabs.treasure.auth.AppLock

class TreasureApp : Application() {
    lateinit var lock: AppLock
        private set

    override fun onCreate() {
        super.onCreate()
        Auth.init(this)
        lock = AppLock(this)
    }
}
