package dev.remotectl.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import dev.remotectl.app.ui.RemotectlTheme
import dev.remotectl.app.ui.RootScreen

// FragmentActivity (not ComponentActivity) because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleInvite(intent)
        setContent {
            RemotectlTheme { RootScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    /** remotectl://p/... links open the pairing sheet pre-filled; the user still taps Pair. */
    private fun handleInvite(intent: Intent?) {
        intent?.data?.toString()?.takeIf { it.startsWith("remotectl://p/") }?.let { vm.pendingInvite.value = it }
    }
}
