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
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Color
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.navigation.NavOptions
import androidx.appcompat.app.AppCompatDelegate
import com.example.rocketplan_android.databinding.ActivityMainBinding
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.ImageProcessingConfigurationRepository
import com.example.rocketplan_android.data.repository.RoomTypeRepository
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.ui.syncstatus.SyncStatusBannerManager
import com.example.rocketplan_android.ui.syncstatus.SyncStatusBannerState
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val bottomNavDestinations: Set<Int> = setOf(
            R.id.nav_map,
            R.id.nav_projects,
            R.id.nav_notifications,
            R.id.nav_people
        )
        private val FULLSCREEN_DESTINATIONS = setOf(
            R.id.batchCaptureFragment,
            R.id.flirCaptureFragment
        )
    }

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository
    private lateinit var syncQueueManager: SyncQueueManager
    private lateinit var imageProcessingConfigurationRepository: ImageProcessingConfigurationRepository
    private lateinit var imageProcessorQueueManager: ImageProcessorQueueManager
    private lateinit var roomTypeRepository: RoomTypeRepository
    private lateinit var remoteLogger: RemoteLogger
    private lateinit var contentLayoutParams: CoordinatorLayout.LayoutParams
    private lateinit var scrollingContentBehavior: AppBarLayout.ScrollingViewBehavior
    private var syncStatusBannerManager: SyncStatusBannerManager? = null
    private var syncBannerEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
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
        imageProcessorQueueManager = rocketPlanApp.imageProcessorQueueManager
        roomTypeRepository = rocketPlanApp.roomTypeRepository
        remoteLogger = rocketPlanApp.remoteLogger

        // Initialize sync status banner manager
        syncStatusBannerManager = SyncStatusBannerManager(
            syncNetworkMonitor = rocketPlanApp.syncNetworkMonitor,
            localDataService = rocketPlanApp.localDataService,
            syncQueueManager = rocketPlanApp.syncQueueManager
        )

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "AuthRepository initialized")
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        contentLayoutParams =
            binding.appBarMain.contentMain.root.layoutParams as CoordinatorLayout.LayoutParams
        scrollingContentBehavior =
            (contentLayoutParams.behavior as? AppBarLayout.ScrollingViewBehavior) ?:
            AppBarLayout.ScrollingViewBehavior()
        contentLayoutParams.behavior = scrollingContentBehavior
        binding.appBarMain.contentMain.root.layoutParams = contentLayoutParams

        // Set up sync status banner observation
        setupSyncStatusBanner()

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val bottomNavigation = binding.appBarMain.contentMain.bottomNavigation
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        // Check if this was launched from OAuth callback deep link
        handleOAuthCallback(intent)

        // Check authentication status and navigate accordingly
        checkAuthenticationStatus(navController)

        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            bottomNavDestinations,
            drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
        bottomNavigation.setupWithNavController(navController)

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

            // Log navigation event to remote logger
            val screenName = destination.label?.toString() ?: destination.displayName
            remoteLogger.log(
                level = LogLevel.INFO,
                tag = "Navigation",
                message = "Navigated to $screenName",
                metadata = mapOf(
                    "screen_name" to screenName,
                    "destination_id" to destination.id.toString()
                )
            )

            val isBottomNavDestination = bottomNavDestinations.contains(destination.id)
            bottomNavigation.isVisible = isBottomNavDestination
            val shouldHideAppBar = isBottomNavDestination ||
                destination.id == R.id.emailCheckFragment ||
                destination.id == R.id.loginFragment ||
                destination.id == R.id.signUpFragment ||
                destination.id == R.id.forgotPasswordFragment ||
                destination.id == R.id.oauthWebViewFragment ||
                destination.id == R.id.scopePickerFragment ||
                destination.id == R.id.batchCaptureFragment ||
                destination.id == R.id.flirCaptureFragment
            binding.appBarMain.appBarLayout.isVisible = !shouldHideAppBar
            updateContentLayoutForAppBar(shouldHideAppBar)
            setFullscreen(FULLSCREEN_DESTINATIONS.contains(destination.id))

            // Hide sync status banner on auth and fullscreen camera screens
            val shouldHideSyncBanner = destination.id == R.id.emailCheckFragment ||
                destination.id == R.id.loginFragment ||
                destination.id == R.id.signUpFragment ||
                destination.id == R.id.forgotPasswordFragment ||
                destination.id == R.id.oauthWebViewFragment ||
                destination.id == R.id.batchCaptureFragment ||
                destination.id == R.id.flirCaptureFragment
            updateSyncBannerVisibility(!shouldHideSyncBanner)

            when {
                destination.id == R.id.emailCheckFragment ||
                    destination.id == R.id.loginFragment ||
                    destination.id == R.id.signUpFragment ||
                    destination.id == R.id.forgotPasswordFragment ||
                destination.id == R.id.oauthWebViewFragment -> {
                    // Hide toolbar and drawer on auth screens
                    bottomNavigation.isVisible = false
                    supportActionBar?.hide()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    window.setSoftInputMode(hiddenSoftInputMode)
                }
                destination.id == R.id.scopePickerFragment -> {
                    bottomNavigation.isVisible = false
                    supportActionBar?.hide()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    window.setSoftInputMode(hiddenSoftInputMode)
                }
                destination.id == R.id.batchCaptureFragment ||
                    destination.id == R.id.flirCaptureFragment -> {
                    bottomNavigation.isVisible = false
                    supportActionBar?.hide()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    window.setSoftInputMode(hiddenSoftInputMode)
                }
                isBottomNavDestination -> {
                    // Hide toolbar/drawer on main tabs to mirror iOS layout
                    supportActionBar?.hide()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    window.setSoftInputMode(hiddenSoftInputMode)
                }
                destination.id == R.id.roomDetailFragment -> {
                    // Show toolbar and drawer, but force keyboard hidden
                    supportActionBar?.show()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                    @Suppress("DEPRECATION")
                    window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    )
                }
                else -> {
                    // Show toolbar and drawer on other screens
                    supportActionBar?.show()
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                    bottomNavigation.isVisible = false
                    window.setSoftInputMode(hiddenSoftInputMode)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (authRepository.isLoggedIn()) {
                imageProcessorQueueManager.reconcileProcessingAssemblies(source = "foreground")
                syncQueueManager.syncOnForeground()
            }
        }
    }

    /**
     * Check if user is logged in and navigate to appropriate screen
     */
    private fun checkAuthenticationStatus(navController: NavController) {
        lifecycleScope.launch {
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "Checking authentication status...")
            }
            val isLoggedIn = authRepository.isLoggedIn()
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "User logged in: $isLoggedIn")
            }
            if (!isLoggedIn) return@launch

            preloadImageProcessorConfiguration()
            runCatching { roomTypeRepository.prefetchOfflineCatalog(forceRefresh = false) }
                .onFailure { Log.w(TAG, "⚠️ Prefetch offline room type catalog failed", it) }

            val authDestinations = setOf(
                R.id.emailCheckFragment,
                R.id.loginFragment,
                R.id.signUpFragment
            )

            // Wait for navigation graph to be ready, then redirect once
            val authRedirectListener = object : NavController.OnDestinationChangedListener {
                override fun onDestinationChanged(
                    controller: NavController,
                    destination: NavDestination,
                    arguments: Bundle?
                ) {
                    if (!authDestinations.contains(destination.id)) return

                    if (BuildConfig.ENABLE_LOGGING) {
                        Log.d(TAG, "Navigation destination changed: ${destination.label}")
                    }

                    controller.removeOnDestinationChangedListener(this)

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

            navController.addOnDestinationChangedListener(authRedirectListener)
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
            R.id.action_support -> {
                Log.d(TAG, "🟢 Support button clicked, navigating to support")
                findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.supportFragment)
                true
            }
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
        intent?.data?.let { handleOAuthRedirect(it) }
    }

    /**
     * Processes OAuth redirect URIs coming from WebView or deep links.
     * Returns true if the URI was handled.
     */
    fun handleOAuthRedirect(uri: Uri): Boolean {
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
            return false
        }

        val token = uri.getQueryParameter("token")
        val status = uri.getQueryParameter("status")?.toIntOrNull()
        val incomingState = uri.getQueryParameter("state")
        val expectedState = authRepository.getStoredOAuthState()

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "OAuth callback - Status: $status, Token present: ${token != null}, State present: ${incomingState != null}")
        }

        if (expectedState.isNullOrBlank()) {
            Log.w(TAG, "OAuth callback received with no pending state; ignoring")
            Toast.makeText(this, "Sign in failed: invalid session", Toast.LENGTH_LONG).show()
            return true
        }

        if (incomingState.isNullOrBlank() || incomingState != expectedState) {
            Log.w(TAG, "OAuth state mismatch; expected=$expectedState, received=$incomingState")
            authRepository.clearOAuthState()
            Toast.makeText(this, "Sign in failed: invalid session", Toast.LENGTH_LONG).show()
            return true
        }

        if (status == 200 && !token.isNullOrEmpty()) {
            // Save token and navigate to home
            lifecycleScope.launch {
                try {
                    authRepository.clearOAuthState()
                    authRepository.saveAuthToken(token)
                    if (BuildConfig.ENABLE_LOGGING) {
                        Log.d(TAG, "OAuth token saved successfully")
                    }
                    authRepository.refreshUserContext().onFailure { error ->
                        Log.w(TAG, "Failed to refresh user context after OAuth", error)
                    }
                    syncQueueManager.clear()
                    syncQueueManager.ensureInitialSync()

                    // Navigate to projects screen and clear auth stack
                    val navController = findNavController(R.id.nav_host_fragment_content_main)
                    val navOptions = NavOptions.Builder()
                        .setPopUpTo(R.id.emailCheckFragment, true)
                        .build()
                    navController.navigate(R.id.nav_projects, null, navOptions)

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
            authRepository.clearOAuthState()
            Toast.makeText(
                this,
                "Sign in failed",
                Toast.LENGTH_LONG
            ).show()
        }
        return true
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
            menu.findItem(R.id.action_test_flir)?.isVisible = BuildConfig.HAS_FLIR_SUPPORT
            menu.findItem(R.id.action_test_crash)?.isVisible = BuildConfig.ENVIRONMENT == "DEV"
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
                    R.id.action_terms_and_conditions -> {
                        if (BuildConfig.ENABLE_LOGGING) {
                            Log.d(TAG, "Navigating to Terms and Conditions")
                        }
                        findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.termsAndConditionsFragment)
                        true
                    }
                    R.id.action_about -> {
                        if (BuildConfig.ENABLE_LOGGING) {
                            Log.d(TAG, "Navigating to About screen")
                        }
                        findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.aboutFragment)
                        true
                    }
                    R.id.action_sign_out -> {
                        performSignOut()
                        true
                    }
                    R.id.action_test_crash -> {
                        Log.d(TAG, "🔥 Test Sentry crash triggered")
                        Toast.makeText(this@MainActivity, "Triggering test crash for Sentry...", Toast.LENGTH_SHORT).show()
                        throw RuntimeException("Test crash from RocketPlan Dev")
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

    @Suppress("DEPRECATION")
    private fun setFullscreen(enabled: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (enabled) {
            window.statusBarColor = Color.TRANSPARENT
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.statusBarColor = getColor(R.color.black)
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun updateContentLayoutForAppBar(shouldHideAppBar: Boolean) {
        if (shouldHideAppBar) {
            binding.appBarMain.appBarLayout.setExpanded(true, false)
        }

        val desiredBehavior = if (shouldHideAppBar) null else scrollingContentBehavior
        if (contentLayoutParams.behavior !== desiredBehavior) {
            contentLayoutParams.behavior = desiredBehavior
            binding.appBarMain.contentMain.root.layoutParams = contentLayoutParams
            binding.appBarMain.contentMain.root.requestLayout()
        }
    }

    private fun setupSyncStatusBanner() {
        val bannerBinding = binding.appBarMain.syncStatusBannerInclude
        val bannerCard = bannerBinding.syncStatusBanner
        val iconView = bannerBinding.syncStatusIcon
        val titleView = bannerBinding.syncStatusTitle
        val subtitleView = bannerBinding.syncStatusSubtitle

        lifecycleScope.launch {
            syncStatusBannerManager?.bannerState?.collectLatest { state ->
                // Don't show banner if disabled for current screen
                if (!syncBannerEnabled) {
                    bannerCard.visibility = View.GONE
                    return@collectLatest
                }

                when (state) {
                    is SyncStatusBannerState.Hidden -> {
                        bannerCard.visibility = View.GONE
                    }
                    is SyncStatusBannerState.Offline -> {
                        bannerCard.visibility = View.VISIBLE
                        bannerCard.setCardBackgroundColor(getColor(R.color.sync_banner_offline_bg))
                        bannerCard.strokeColor = getColor(R.color.sync_banner_offline_stroke)
                        iconView.setBackgroundResource(R.drawable.bg_icon_circle_red)
                        iconView.setImageResource(R.drawable.ic_cloud_off)
                        iconView.setColorFilter(getColor(R.color.sync_banner_offline_stroke))
                        titleView.text = getString(R.string.sync_banner_offline_title)
                        subtitleView.text = getString(R.string.sync_banner_offline_subtitle)
                    }
                    is SyncStatusBannerState.Syncing -> {
                        bannerCard.visibility = View.VISIBLE
                        bannerCard.setCardBackgroundColor(getColor(R.color.sync_banner_syncing_bg))
                        bannerCard.strokeColor = getColor(R.color.sync_banner_syncing_stroke)
                        iconView.setBackgroundResource(R.drawable.bg_icon_circle_green)
                        iconView.setImageResource(R.drawable.ic_sync)
                        iconView.setColorFilter(getColor(R.color.sync_banner_syncing_stroke))
                        titleView.text = getString(R.string.sync_banner_syncing_title)
                        subtitleView.text = formatSyncingItems(state.items)
                    }
                    is SyncStatusBannerState.Refreshing -> {
                        bannerCard.visibility = View.VISIBLE
                        bannerCard.setCardBackgroundColor(getColor(R.color.sync_banner_syncing_bg))
                        bannerCard.strokeColor = getColor(R.color.sync_banner_syncing_stroke)
                        iconView.setBackgroundResource(R.drawable.bg_icon_circle_green)
                        iconView.setImageResource(R.drawable.ic_sync)
                        iconView.setColorFilter(getColor(R.color.sync_banner_syncing_stroke))
                        titleView.text = getString(R.string.sync_banner_syncing_title)
                        subtitleView.text = state.description
                    }
                }
            }
        }
    }

    /**
     * Controls whether the sync status banner can be shown.
     * Used to hide banner on auth screens and fullscreen camera screens.
     */
    private fun updateSyncBannerVisibility(enabled: Boolean) {
        syncBannerEnabled = enabled
        if (!enabled) {
            binding.appBarMain.syncStatusBannerInclude.syncStatusBanner.visibility = View.GONE
        }
        // If enabling, the banner visibility will be updated by the state flow
    }

    private fun formatSyncingItems(items: List<com.example.rocketplan_android.ui.syncstatus.SyncProgressItem>): String {
        if (items.isEmpty()) return ""

        val parts = items.take(3).map { item ->
            if (item.projectName != null) {
                "${item.displayName} (${item.projectName})"
            } else {
                item.displayName
            }
        }

        return parts.joinToString(", ")
    }

    override fun onDestroy() {
        super.onDestroy()
        syncStatusBannerManager?.destroy()
        syncStatusBannerManager = null
    }

}
