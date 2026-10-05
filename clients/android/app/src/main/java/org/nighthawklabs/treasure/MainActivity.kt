package org.nighthawklabs.treasure

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.ui.AppRoot
import org.nighthawklabs.treasure.ui.theme.TreasureTheme

class MainActivity : FragmentActivity() {
    private val lock get() = (application as TreasureApp).lock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DevMode.configure(intent.getStringExtra("dev_engine"))
        enableEdgeToEdge()
        // Whole-app foreground/background (not per-activity), so rotating the phone never locks it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { lock.leftForeground() }
            override fun onStart(owner: LifecycleOwner) { lifecycleScope.launch { lock.unlock(this@MainActivity) } }
        })
        // With the lock on, keep balances out of screenshots and the recent-apps thumbnail.
        lifecycleScope.launch {
            lock.enabled.collect { on ->
                if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent { TreasureTheme { AppRoot(this, lock) } }
    }
}
