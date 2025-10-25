package com.example.rocketplan_android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
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
import androidx.appcompat.widget.PopupMenu
import androidx.navigation.NavOptions
import com.example.rocketplan_android.databinding.ActivityMainBinding
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.sync.SyncQueueManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository
    private lateinit var syncQueueManager: SyncQueueManager

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

        val rocketPlanApp = application as RocketPlanApplication
        authRepository = rocketPlanApp.authRepository
        syncQueueManager = rocketPlanApp.syncQueueManager

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
                R.id.nav_projects, R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        // Hide/show drawer and toolbar based on current destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.emailCheckFragment, R.id.loginFragment, R.id.signUpFragment, R.id.forgotPasswordFragment, R.id.nav_projects -> {
                    // Hide toolbar, drawer, and FAB on auth screens and projects screen
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
                if (isLoggedIn && when (destination.id) {
                        R.id.emailCheckFragment,
                        R.id.loginFragment,
                        R.id.signUpFragment -> true
                        else -> false
                    }
                ) {
                    if (BuildConfig.ENABLE_LOGGING) {
                        Log.d(TAG, "User authenticated, navigating to projects")
                    }
                    val navOptions = androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.emailCheckFragment, true)
                        .build()
                    controller.navigate(R.id.nav_projects, null, navOptions)
                    lifecycleScope.launch {
                        syncQueueManager.ensureInitialSync()
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_profile -> {
                showProfileMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
     * Expected format: rocketplan-dev://?token={JWT_TOKEN}&status=200
     */
    private fun handleOAuthCallback(intent: Intent?) {
        intent?.data?.let { uri ->
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "Deep link received: $uri")
            }

            // Check if this is an OAuth callback (scheme starts with "rocketplan")
            if (uri.scheme?.startsWith("rocketplan") == true) {

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
                            authRepository.refreshUserContext().onFailure { error ->
                                Log.w(TAG, "Failed to refresh user context after OAuth", error)
                            }
                            syncQueueManager.clear()
                            syncQueueManager.ensureInitialSync()

                            // Navigate to projects screen
                            val navController = findNavController(R.id.nav_host_fragment_content_main)
                            navController.navigate(R.id.nav_projects)

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

    private fun showProfileMenu() {
        val toolbar = binding.appBarMain.toolbar
        val anchor = toolbar.findViewById<View>(R.id.action_profile) ?: toolbar
        PopupMenu(this, anchor, Gravity.END).apply {
            menuInflater.inflate(R.menu.profile_menu, menu)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_sign_out -> {
                        performSignOut()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun performSignOut() {
        lifecycleScope.launch {
            try {
                authRepository.logout()
                syncQueueManager.clear()
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                navController.navigate(
                    R.id.emailCheckFragment,
                    null,
                    NavOptions.Builder()
                        .setPopUpTo(navController.graph.startDestinationId, inclusive = true)
                        .build()
                )
                Toast.makeText(this@MainActivity, R.string.action_sign_out, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error during logout", e)
                Toast.makeText(
                    this@MainActivity,
                    "Failed to sign out: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
