package com.example.rocketplan_android.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.rocketplan_android.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Result of a Google Sign-In attempt via Credential Manager.
 */
sealed class GoogleSignInResult {
    /** Successful sign-in with Google ID token */
    data class Success(val idToken: String) : GoogleSignInResult()

    /** User cancelled the sign-in flow */
    data object Cancelled : GoogleSignInResult()

    /** No Google account available on device - should fallback to WebView */
    data object NoCredentials : GoogleSignInResult()

    /** Credential Manager not available - should fallback to WebView */
    data object Unavailable : GoogleSignInResult()

    /** Other error occurred */
    data class Error(val message: String, val exception: Exception? = null) : GoogleSignInResult()
}

/**
 * Manages Google Sign-In using Android Credential Manager API.
 * Provides native account picker experience instead of WebView-based OAuth.
 */
class GoogleAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "GoogleAuthManager"
    }

    private val credentialManager: CredentialManager by lazy {
        CredentialManager.create(context)
    }

    /**
     * Check if Credential Manager is available and configured.
     * Returns false if Web Client ID is not configured.
     */
    fun isAvailable(): Boolean {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        val available = clientId.isNotBlank()
        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "isAvailable: $available (clientId configured: ${clientId.isNotBlank()})")
        }
        return available
    }

    /**
     * Initiate Google Sign-In flow using Credential Manager.
     * Shows native bottom sheet account picker.
     *
     * @return GoogleSignInResult indicating success, cancellation, or error
     */
    suspend fun signIn(): GoogleSignInResult {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isBlank()) {
            Log.w(TAG, "Google Web Client ID not configured")
            return GoogleSignInResult.Unavailable
        }

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "Starting Google Sign-In with clientId: ${clientId.take(20)}...")
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val result = credentialManager.getCredential(context, request)
            handleSignInResult(result)
        } catch (e: GetCredentialCancellationException) {
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "User cancelled Google Sign-In")
            }
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google credentials available on device: ${e.type} - ${e.message}", e)
            GoogleSignInResult.NoCredentials
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager error: type=${e.type}, message=${e.message}", e)
            // Check if this is an unavailability issue
            if (e.type.contains("unavailable", ignoreCase = true) ||
                e.type.contains("not supported", ignoreCase = true)) {
                GoogleSignInResult.Unavailable
            } else {
                GoogleSignInResult.Error(e.message ?: "Failed to get credentials", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Google Sign-In", e)
            GoogleSignInResult.Error(e.message ?: "Unexpected error", e)
        }
    }

    private fun handleSignInResult(result: GetCredentialResponse): GoogleSignInResult {
        val credential = result.credential

        return when (credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val idToken = googleIdTokenCredential.idToken

                        if (BuildConfig.ENABLE_LOGGING) {
                            Log.d(TAG, "Google Sign-In successful, received ID token")
                            Log.d(TAG, "User: ${googleIdTokenCredential.displayName}, Email: ${googleIdTokenCredential.id}")
                        }

                        GoogleSignInResult.Success(idToken)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Google ID token credential", e)
                        GoogleSignInResult.Error("Failed to parse credential", e)
                    }
                } else {
                    Log.w(TAG, "Unexpected credential type: ${credential.type}")
                    GoogleSignInResult.Error("Unexpected credential type: ${credential.type}")
                }
            }
            else -> {
                Log.w(TAG, "Unexpected credential class: ${credential.javaClass.name}")
                GoogleSignInResult.Error("Unexpected credential type")
            }
        }
    }
}
