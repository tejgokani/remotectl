package dev.remotectl.app

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Asks for fingerprint/face or the phone's PIN before a sensitive action (terminal, force quit).
 * If the phone has no screen lock at all there is nothing to check against, so the action proceeds.
 */
fun FragmentActivity.requireAuth(title: String, onApproved: () -> Unit) {
    val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onApproved()
        return
    }
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onApproved()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}
