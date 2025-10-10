package com.example.rocketplan_android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.navigation.NavigationView
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.rocketplan_android.databinding.ActivityMainBinding
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Debug logging for app launch (only when ENABLE_LOGGING is true)
        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "MainActivity onCreate started")
            Log.d(TAG, "Environment: ${BuildConfig.ENVIRONMENT}")
            Log.d(TAG, "API Base URL: ${BuildConfig.API_BASE_URL}")
            Log.d(TAG, "Build Type: ${BuildConfig.BUILD_TYPE}")
            Log.d(TAG, "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }

        // Initialize auth repository
        val secureStorage = SecureStorage.getInstance(applicationContext)
        authRepository = AuthRepository(secureStorage)

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "AuthRepository initialized")
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        binding.appBarMain.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null)
                .setAnchorView(R.id.fab).show()
        }
        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        // Check if this was launched from OAuth callback deep link
        handleOAuthCallback(intent)

        // Check authentication status and navigate accordingly
        checkAuthenticationStatus(navController)

        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        // Hide/show drawer and toolbar based on current destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.loginFragment, R.id.forgotPasswordFragment -> {
                    // Hide toolbar, drawer, and FAB on auth screens
                    supportActionBar?.hide()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    binding.appBarMain.fab.visibility = View.GONE
                }
                else -> {
                    // Show toolbar, drawer, and FAB on other screens
                    supportActionBar?.show()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                    binding.appBarMain.fab.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Check if user is logged in and navigate to appropriate screen
     */
    private fun checkAuthenticationStatus(navController: androidx.navigation.NavController) {
        lifecycleScope.launch {
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "Checking authentication status...")
            }
            val isLoggedIn = authRepository.isLoggedIn()
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "User logged in: $isLoggedIn")
            }

            // Wait for navigation graph to be ready
            navController.addOnDestinationChangedListener { controller, destination, _ ->
                if (BuildConfig.ENABLE_LOGGING) {
                    Log.d(TAG, "Navigation destination changed: ${destination.label}")
                }
                // Only navigate once, on first destination
                if (destination.id == R.id.loginFragment && isLoggedIn) {
                    // User is logged in, navigate to home
                    if (BuildConfig.ENABLE_LOGGING) {
                        Log.d(TAG, "User authenticated, navigating to home")
                    }
                    controller.navigate(R.id.action_loginFragment_to_nav_home)
                }
                // If user is not logged in, loginFragment is already the start destination
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "onNewIntent called")
        }
        // Handle OAuth callback when app is already running
        handleOAuthCallback(intent)
    }

    /**
     * Handle OAuth callback from deep link
     * Expected format: rocketplan://oauth2/redirect?token={JWT_TOKEN}&status=200
     */
    private fun handleOAuthCallback(intent: Intent?) {
        intent?.data?.let { uri ->
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "Deep link received: $uri")
            }

            // Check if this is an OAuth callback
            if (uri.scheme?.startsWith("rocketplan") == true &&
                uri.host == "oauth2" &&
                uri.path == "/redirect") {

                val token = uri.getQueryParameter("token")
                val status = uri.getQueryParameter("status")?.toIntOrNull()

                if (BuildConfig.ENABLE_LOGGING) {
                    Log.d(TAG, "OAuth callback - Status: $status, Token present: ${token != null}")
                }

                if (status == 200 && !token.isNullOrEmpty()) {
                    // Save token and navigate to home
                    lifecycleScope.launch {
                        try {
                            authRepository.saveAuthToken(token)
                            if (BuildConfig.ENABLE_LOGGING) {
                                Log.d(TAG, "OAuth token saved successfully")
                            }

                            // Navigate to home screen
                            val navController = findNavController(R.id.nav_host_fragment_content_main)
                            navController.navigate(R.id.nav_home)

                            Toast.makeText(
                                this@MainActivity,
                                "Sign in successful!",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving OAuth token", e)
                            Toast.makeText(
                                this@MainActivity,
                                "Sign in failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    Log.e(TAG, "OAuth callback failed - Status: $status")
                    Toast.makeText(
                        this,
                        "Sign in failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
