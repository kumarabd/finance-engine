package org.nighthawklabs.treasure.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Optional fingerprint / face / device-credential gate. Locks whenever the app leaves the foreground. */
class AppLock(context: Context) {
    private val prefs = context.getSharedPreferences("treasure-lock", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    private val _locked = MutableStateFlow(_enabled.value)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var prompting = false

    fun leftForeground() { if (_enabled.value) _locked.value = true }

    suspend fun unlock(activity: FragmentActivity) {
        if (!_locked.value || prompting) return
        prompting = true
        try { if (authenticate(activity, "Unlock Treasure")) _locked.value = false } finally { prompting = false }
    }

    /**
     * Turning it on is confirmed with the device's own prompt first, so you can't lock yourself out by mistake.
     * Returns an explanation when it couldn't be enabled.
     */
    suspend fun setEnabled(activity: FragmentActivity, on: Boolean): String? {
        if (on) {
            if (BiometricManager.from(activity).canAuthenticate(AUTH) != BiometricManager.BIOMETRIC_SUCCESS) return "Set a screen lock on this device first."
            if (!authenticate(activity, "Turn on Treasure lock")) return null
        }
        _enabled.value = on
        prefs.edit().putBoolean("enabled", on).apply()
        return null
    }

    private suspend fun authenticate(activity: FragmentActivity, title: String): Boolean = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTH).build())
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    private companion object { const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL }
}
