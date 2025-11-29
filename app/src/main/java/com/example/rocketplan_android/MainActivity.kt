package com.example.rocketplan_android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
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
import com.example.rocketplan_android.data.repository.ImageProcessingConfigurationRepository
import com.example.rocketplan_android.data.sync.SyncQueueManager
import kotlinx.coroutines.launch
import androidx.navigation.fragment.NavHostFragment
import com.example.rocketplan_android.ui.projects.ProjectDetailFragment

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository
    private lateinit var syncQueueManager: SyncQueueManager
    private lateinit var imageProcessingConfigurationRepository: ImageProcessingConfigurationRepository

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
        imageProcessingConfigurationRepository = rocketPlanApp.imageProcessingConfigurationRepository

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "AuthRepository initialized")
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

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

        // Get IMM for keyboard management
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        // Store the default window soft input mode so it can be restored after custom overrides
        val defaultSoftInputMode = window.attributes.softInputMode
        val defaultAdjustMode =
            defaultSoftInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        val hiddenSoftInputMode =
            (if (defaultAdjustMode == 0) 0 else defaultAdjustMode) or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN

        // Ensure keyboard stays hidden on initial launch until the user requests it
        window.setSoftInputMode(hiddenSoftInputMode)

        // Hide/show drawer and toolbar based on current destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Clear focus and hide keyboard on every navigation change
            currentFocus?.let { focused ->
                focused.clearFocus()
                imm.hideSoftInputFromWindow(focused.windowToken, 0)
            }

            when (destination.id) {
                R.id.emailCheckFragment, R.id.loginFragment, R.id.signUpFragment, R.id.forgotPasswordFragment, R.id.nav_projects -> {
                    // Hide toolbar and drawer on auth screens and projects screen
                    supportActionBar?.hide()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    window.setSoftInputMode(hiddenSoftInputMode)
                }
                R.id.roomDetailFragment -> {
                    // Show toolbar and drawer, but force keyboard hidden
                    supportActionBar?.show()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                    window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    )
                }
                else -> {
                    // Show toolbar and drawer on other screens
                    supportActionBar?.show()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                    window.setSoftInputMode(hiddenSoftInputMode)
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
            if (isLoggedIn) {
                preloadImageProcessorConfiguration()
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
        Log.d(TAG, "🔵 onOptionsItemSelected: ${item.itemId}")
        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "onOptionsItemSelected: ${item.itemId}")
        }
        return when (item.itemId) {
            R.id.action_profile -> {
                Log.d(TAG, "🟢 Profile button clicked, showing menu")
                if (BuildConfig.ENABLE_LOGGING) {
                    Log.d(TAG, "Profile button clicked, showing menu")
                }
                showProfileMenu()
                true
            }
            else -> {
                Log.d(TAG, "🔴 Item not handled: ${item.itemId}")
                super.onOptionsItemSelected(item)
            }
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

            val allowedSchemes = setOf(
                "rocketplan",
                "rocketplan-staging",
                "rocketplan-dev",
                "rocketplan-local"
            )

            val isOAuthCallback = uri.scheme in allowedSchemes &&
                uri.host == "oauth2" &&
                uri.path == "/redirect"

            if (!isOAuthCallback) {
                if (BuildConfig.ENABLE_LOGGING) {
                    Log.d(TAG, "Ignoring deep link (not OAuth callback): $uri")
                }
                return
            }

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

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun showProfileMenu() {
        Log.d(TAG, "🟣 showProfileMenu() called")
        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "showProfileMenu() called")
        }
        val toolbar = binding.appBarMain.toolbar
        val anchor = toolbar.findViewById<View>(R.id.action_profile) ?: toolbar
        Log.d(TAG, "🟣 Creating PopupMenu with anchor: $anchor")
        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "Creating PopupMenu with anchor: $anchor")
        }
        PopupMenu(this, anchor, Gravity.END).apply {
            menuInflater.inflate(R.menu.profile_menu, menu)
            val navController = findNavController(R.id.nav_host_fragment_content_main)
            val isProjectDetail = navController.currentDestination?.id == R.id.projectDetailFragment
            menu.findItem(R.id.action_delete_project)?.isVisible = isProjectDetail
            Log.d(TAG, "🟣 Profile menu inflated with ${menu.size()} items")
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "Profile menu inflated with ${menu.size()} items")
            }
            setOnMenuItemClickListener { menuItem ->
                Log.d(TAG, "🟡 Profile menu item clicked: ${menuItem.itemId}")
                if (BuildConfig.ENABLE_LOGGING) {
                    Log.d(TAG, "Profile menu item clicked: ${menuItem.itemId}")
                }
                when (menuItem.itemId) {
                    R.id.action_sync_status -> {
                        if (BuildConfig.ENABLE_LOGGING) {
                            Log.d(TAG, "Navigating to Sync Status")
                        }
                        findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.syncStatusFragment)
                        true
                    }
                    R.id.action_image_processor_assemblies -> {
                        if (BuildConfig.ENABLE_LOGGING) {
                            Log.d(TAG, "Navigating to Image Processor Assemblies")
                        }
                        findNavController(R.id.nav_host_fragment_content_main)
                            .navigate(R.id.imageProcessorAssembliesFragment)
                        true
                    }
                    R.id.action_reload_image_processor_config -> {
                        Log.d(TAG, "📸 Menu item clicked: action_reload_image_processor_config")
                        try {
                            findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.imageProcessorConfigFragment)
                            Log.d(TAG, "📸 Navigation to imageProcessorConfigFragment succeeded")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to navigate to imageProcessorConfigFragment", e)
                            Toast.makeText(this@MainActivity, "Navigation failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        true
                    }
                    R.id.action_company_info -> {
                        if (BuildConfig.ENABLE_LOGGING) {
                            Log.d(TAG, "Navigating to Company Info screen")
                        }
                        findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.companyInfoFragment)
                        true
                    }
                    R.id.action_delete_project -> handleDeleteProjectRequest()
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
                imageProcessingConfigurationRepository.clearCachedConfiguration()
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

    private fun preloadImageProcessorConfiguration(forceRefresh: Boolean = false) {
        lifecycleScope.launch {
            val result = imageProcessingConfigurationRepository.getConfiguration(forceRefresh)
            result.exceptionOrNull()?.let { error ->
                if (BuildConfig.ENABLE_LOGGING) {
                    Log.w(TAG, "Image processor config preload failed: ${error.message}")
                }
            }
        }
    }

    private fun reloadImageProcessorConfiguration() {
        lifecycleScope.launch {
            val result = imageProcessingConfigurationRepository.getConfiguration(forceRefresh = true)
            result.onSuccess { config ->
                Log.d(TAG, "📸 Image processor config from server: $config")
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_image_processor_config_loaded, config.service),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                Log.e(TAG, "❌ Failed to load image processor config", error)
                val reason = error.message ?: getString(R.string.toast_image_processor_config_unknown_error)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_image_processor_config_failed, reason),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleDeleteProjectRequest(): Boolean {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
        val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment
        return if (currentFragment is ProjectDetailFragment) {
            currentFragment.promptDeleteProject()
            true
        } else {
            Toast.makeText(this, R.string.delete_project_not_available, Toast.LENGTH_SHORT).show()
            false
        }
    }
}
