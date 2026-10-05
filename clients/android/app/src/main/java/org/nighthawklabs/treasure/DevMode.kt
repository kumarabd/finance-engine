package org.nighthawklabs.treasure

/**
 * DEBUG builds only: launching the app with the intent extra `dev_engine` (for the emulator, http://10.0.2.2:18091) points it
 * straight at a local finance-engine run with DEV_VERIFIED_USER, skipping Clerk and the router. Release builds ignore it.
 */
object DevMode {
    @Volatile var engineUrl: String? = null
        private set

    fun configure(extra: String?) { engineUrl = if (BuildConfig.DEBUG) extra?.takeIf { it.isNotBlank() } else null }

    const val USER = "dev_user"
}
