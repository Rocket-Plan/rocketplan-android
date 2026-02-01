package com.example.rocketplan_android.util

import androidx.fragment.app.Fragment
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController

/**
 * Safely navigate to a destination, preventing crashes when:
 * - Fragment is not attached to a fragment manager
 * - Navigation is triggered during configuration change
 * - Rapid double-taps trigger duplicate navigation
 *
 * @param action The navigation action to perform
 * @return true if navigation was performed, false if skipped due to invalid state
 */
fun Fragment.safeNavigate(action: NavDirections): Boolean {
    if (!isAdded) return false

    return try {
        findNavController().navigate(action)
        true
    } catch (e: IllegalStateException) {
        // Fragment not associated with NavController
        false
    } catch (e: IllegalArgumentException) {
        // Navigation action not valid from current destination (already navigated)
        false
    }
}
