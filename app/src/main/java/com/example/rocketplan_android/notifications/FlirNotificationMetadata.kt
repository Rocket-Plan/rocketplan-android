package com.example.rocketplan_android.notifications

import android.os.Bundle
import android.view.Gravity
import androidx.core.app.NotificationCompat
import com.example.rocketplan_android.BuildConfig

/**
 * FLIR ACE devices require extra notification metadata to render buttons/placement.
 *
 * Required keys:
 * - GRAVITY: Center/Top/Filled positions (use Android Gravity constants).
 * - BUTTON_TEXT: Primary/dismiss button label.
 * - SECONDARY_BUTTON_TEXT: Secondary button label that redirects the user.
 * - REDIRECT_PACKAGE_NAME: Package name the secondary button should open.
 * - CLOSE_OPTION: Whether a dismiss option is shown.
 */
object FlirNotificationMetadata {
    const val KEY_GRAVITY = "GRAVITY"
    const val KEY_BUTTON_TEXT = "BUTTON_TEXT"
    const val KEY_SECONDARY_BUTTON_TEXT = "SECONDARY_BUTTON_TEXT"
    const val KEY_REDIRECT_PACKAGE_NAME = "REDIRECT_PACKAGE_NAME"
    const val KEY_CLOSE_OPTION = "CLOSE_OPTION"

    fun build(
        buttonText: String = "Close",
        secondaryButtonText: String = "Redirect",
        redirectPackageName: String = BuildConfig.APPLICATION_ID,
        gravity: Int = Gravity.TOP,
        closeOption: Boolean = true
    ): Bundle = Bundle().apply {
        putString(KEY_BUTTON_TEXT, buttonText)
        putString(KEY_SECONDARY_BUTTON_TEXT, secondaryButtonText)
        putString(KEY_REDIRECT_PACKAGE_NAME, redirectPackageName)
        putInt(KEY_GRAVITY, gravity)
        putBoolean(KEY_CLOSE_OPTION, closeOption)
    }
}

/**
 * Convenience extension to attach FLIR-required extras to any notification.
 */
fun NotificationCompat.Builder.addFlirAceMetadata(
    buttonText: String = "Close",
    secondaryButtonText: String = "Redirect",
    redirectPackageName: String = BuildConfig.APPLICATION_ID,
    gravity: Int = Gravity.TOP,
    closeOption: Boolean = true
): NotificationCompat.Builder = apply {
    addExtras(
        FlirNotificationMetadata.build(
            buttonText = buttonText,
            secondaryButtonText = secondaryButtonText,
            redirectPackageName = redirectPackageName,
            gravity = gravity,
            closeOption = closeOption
        )
    )
}
