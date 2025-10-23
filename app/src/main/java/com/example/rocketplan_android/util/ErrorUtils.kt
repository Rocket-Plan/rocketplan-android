package com.example.rocketplan_android.util

import android.view.View
import com.google.android.material.snackbar.Snackbar

/**
 * Utility functions for displaying errors in a user-friendly way
 */
object ErrorUtils {

    /**
     * Show a styled error snackbar
     * @param view The view to attach the Snackbar to
     * @param message The error message to display
     * @param duration Duration of the Snackbar (default: LENGTH_LONG)
     * @param actionText Optional action button text (e.g., "Retry")
     * @param action Optional action to perform when action button is clicked
     */
    fun showErrorSnackbar(
        view: View,
        message: String,
        duration: Int = Snackbar.LENGTH_LONG,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ): Snackbar {
        val snackbar = Snackbar.make(view, message, duration)

        if (actionText != null && action != null) {
            snackbar.setAction(actionText) {
                action()
            }
        }

        snackbar.show()
        return snackbar
    }

    /**
     * Show a success snackbar
     * @param view The view to attach the Snackbar to
     * @param message The success message to display
     * @param duration Duration of the Snackbar (default: LENGTH_SHORT)
     */
    fun showSuccessSnackbar(
        view: View,
        message: String,
        duration: Int = Snackbar.LENGTH_SHORT
    ): Snackbar {
        val snackbar = Snackbar.make(view, message, duration)
        snackbar.show()
        return snackbar
    }

    /**
     * Show an info snackbar
     * @param view The view to attach the Snackbar to
     * @param message The info message to display
     * @param duration Duration of the Snackbar (default: LENGTH_SHORT)
     */
    fun showInfoSnackbar(
        view: View,
        message: String,
        duration: Int = Snackbar.LENGTH_SHORT
    ): Snackbar {
        val snackbar = Snackbar.make(view, message, duration)
        snackbar.show()
        return snackbar
    }

    /**
     * Check if an error is a network connectivity error
     */
    fun isNetworkError(errorMessage: String): Boolean {
        val networkKeywords = listOf(
            "network",
            "connection",
            "internet",
            "offline",
            "timeout",
            "unable to connect"
        )
        return networkKeywords.any { errorMessage.contains(it, ignoreCase = true) }
    }

    /**
     * Check if an error is an authentication error
     */
    fun isAuthError(errorMessage: String): Boolean {
        val authKeywords = listOf(
            "unauthorized",
            "authentication",
            "credentials",
            "password",
            "expired"
        )
        return authKeywords.any { errorMessage.contains(it, ignoreCase = true) }
    }
}
