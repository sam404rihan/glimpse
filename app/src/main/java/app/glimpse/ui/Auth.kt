package app.glimpse.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The phone's own lock (fingerprint, face unlock, or the PIN/pattern/password as a fallback) guards
 * destructive actions, so someone holding an unlocked phone can't wipe your people.
 */
object Auth {
    private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** False when the phone has no screen lock at all: then there is nothing to check against. */
    fun available(context: Context) = BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    fun confirm(activity: FragmentActivity, title: String, subtitle: String, onOk: () -> Unit, onError: (String) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                // Backing out isn't an error worth showing.
                if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_CANCELED &&
                    code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) onError(message.toString())
            }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(ALLOWED).build())
    }
}
