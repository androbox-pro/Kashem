package org.fossify.commons.views

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.provider.Settings
import android.util.AttributeSet
import android.widget.RelativeLayout
import androidx.biometric.auth.AuthPromptHost
import androidx.biometric.BiometricManager
import org.fossify.commons.R
import org.fossify.commons.databinding.TabFingerprintBinding
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.interfaces.HashListener
import org.fossify.commons.interfaces.SecurityTab

class FingerprintTab(context: Context, attrs: AttributeSet) : RelativeLayout(context, attrs), SecurityTab {
    private val RECHECK_PERIOD = 3000L
    private val registerHandler = Handler(android.os.Looper.getMainLooper())

    lateinit var hashListener: HashListener
    private lateinit var biometricPromptHost: AuthPromptHost

    private lateinit var binding: TabFingerprintBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = TabFingerprintBinding.bind(this)
        val textColor = context.getProperTextColor()
        context.updateTextColors(binding.fingerprintLockHolder)
        binding.fingerprintImage.applyColorFilter(textColor)

        binding.fingerprintSettings.setOnClickListener {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun initTab(
        requiredHash: String,
        listener: HashListener,
        scrollView: MyScrollView?,
        biometricPromptHost: AuthPromptHost,
        showBiometricAuthentication: Boolean
    ) {
        hashListener = listener
        this.biometricPromptHost = biometricPromptHost
    }

    override fun visibilityChanged(isVisible: Boolean) {
        if (isVisible) {
            checkRegisteredFingerprints()
        }
    }

    private fun checkRegisteredFingerprints() {
        val activity = biometricPromptHost.activity ?: return
        val canAuthenticate = BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
        binding.fingerprintSettings.beGoneIf(canAuthenticate)
        binding.fingerprintLabel.text = context.getString(
            if (canAuthenticate) R.string.place_finger else R.string.no_fingerprints_registered
        )

        if (canAuthenticate) {
            activity.showBiometricPrompt(
                successCallback = { _, _ -> hashListener.receivedHash("", PROTECTION_FINGERPRINT) },
                failureCallback = null
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        registerHandler.removeCallbacksAndMessages(null)
    }
}
