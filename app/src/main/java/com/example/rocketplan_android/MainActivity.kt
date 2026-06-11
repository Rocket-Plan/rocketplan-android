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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
import com.example.rocketplan_android.data.api.CrmUserApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.ImageProcessingConfigurationRepository
import com.example.rocketplan_android.data.repository.RoomTypeRepository
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.ui.syncstatus.SyncStatusBannerManager
import com.example.rocketplan_android.ui.syncstatus.SyncStatusBannerState
import com.example.rocketplan_android.util.InviteLink
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val bottomNavDestinations: Set<Int> = if (BuildConfig.HAS_FLIR_SUPPORT) {
            // FLIR builds don't have map feature
            setOf(
                R.id.nav_projects,
                R.id.nav_notifications,
                R.id.nav_people
            )
        } else {
            setOf(
                R.id.nav_map,
                R.id.nav_projects,
                R.id.nav_notifications,
                R.id.nav_people
            )
        }
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
    private var isForceSigningOut = false
    private var isProcessingOAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK)
        )
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
            syncQueueManager = rocketPlanApp.syncQueueManager,
            offlineSyncRepository = rocketPlanApp.offlineSyncRepository
        )

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "AuthRepository initialized")
        }

        // Check app version and flavor status on launch
        checkAppVersionAndFlavor()

        // Force sign-out when server rejects the token (401 on authenticated requests)
        RetrofitClient.onUnauthorized = {
            runOnUiThread {
                if (!isForceSigningOut) {
                    isForceSigningOut = true
                    Log.w(TAG, "Token rejected by server — forcing sign out")
                    performSignOut()
                    Toast.makeText(this, "Your session has expired. Please sign in again.", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on to prevent dimming during use
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Keep screen on to prevent dimming during use
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        // Check if this was launched from invite deep link
        handleInviteDeepLink(intent)

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
                destination.id == R.id.accountTypeFragment ||
                destination.id == R.id.joinCompanyFragment ||
                destination.id == R.id.phoneVerificationFragment ||
                destination.id == R.id.smsCodeVerifyFragment ||
                destination.id == R.id.finalDetailsFragment ||
                destination.id == R.id.scopePickerFragment ||
                destination.id == R.id.batchCaptureFragment ||
                destination.id == R.id.flirCaptureFragment
            binding.appBarMain.appBarLayout.isVisible = !shouldHideAppBar
            updateContentLayoutForAppBar(shouldHideAppBar)
            setFullscreen(FULLSCREEN_DESTINATIONS.contains(destination.id))

            // Hide sync status banner on auth, onboarding, and fullscreen camera screens
            val shouldHideSyncBanner = destination.id == R.id.emailCheckFragment ||
                destination.id == R.id.loginFragment ||
                destination.id == R.id.signUpFragment ||
                destination.id == R.id.forgotPasswordFragment ||
                destination.id == R.id.oauthWebViewFragment ||
                destination.id == R.id.accountTypeFragment ||
                destination.id == R.id.joinCompanyFragment ||
                destination.id == R.id.phoneVerificationFragment ||
                destination.id == R.id.smsCodeVerifyFragment ||
                destination.id == R.id.finalDetailsFragment ||
                destination.id == R.id.batchCaptureFragment ||
                destination.id == R.id.flirCaptureFragment
            updateSyncBannerVisibility(!shouldHideSyncBanner)

            when {
                destination.id == R.id.emailCheckFragment ||
                    destination.id == R.id.loginFragment ||
                    destination.id == R.id.signUpFragment ||
                    destination.id == R.id.forgotPasswordFragment ||
                    destination.id == R.id.oauthWebViewFragment ||
                    destination.id == R.id.accountTypeFragment ||
                    destination.id == R.id.joinCompanyFragment ||
                    destination.id == R.id.phoneVerificationFragment ||
                    destination.id == R.id.smsCodeVerifyFragment ||
                    destination.id == R.id.finalDetailsFragment -> {
                    // Hide toolbar and drawer on auth/onboarding screens
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
        // Skip onResume sync work if an OAuth flow is in progress — the token
        // hasn't been saved yet and API calls would race with the OAuth handler.
        if (isProcessingOAuth) return

        lifecycleScope.launch {
            if (authRepository.isLoggedIn()) {
                imageProcessorQueueManager.reconcileProcessingAssemblies(source = "foreground")
                syncQueueManager.syncOnForeground()
                // Abandon stale server assemblies after Pusher stabilizes (matches iOS).
                // Launched separately so it doesn't block sync.
                launch {
                    kotlinx.coroutines.delay(1000)
                    imageProcessorQueueManager.abandonStaleServerAssemblies()
                }
            }
        }
    }

    /**
     * Check if user is logged in and navigate to appropriate screen
     * RP-BUG-269: also checks SMS verification status and routes unverified users
     * to the verification screen.
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

            // RP-BUG-269: call refreshUserContext to get SMS verification status
            val userContextResult = authRepository.refreshUserContext()
            val currentUser = userContextResult.getOrNull()
            // RP-BUG-269: fall back to cached value on offline/transient failure
            val isSmsVerified = currentUser?.isSmsVerified
                ?: authRepository.getCachedSmsVerified()

            preloadImageProcessorConfiguration()
            checkCrmAccess()
            runCatching { roomTypeRepository.prefetchOfflineCatalog(forceRefresh = false) }
                .onFailure { Log.w(TAG, "⚠️ Prefetch offline room type catalog failed", it) }

            // Check if user needs onboarding (has token but no company)
            val companyId = authRepository.getStoredCompanyId()

            // RP-BUG-042: seed the company-scoped WorkScope catalog up front (best-effort, non-blocking)
            // so the work-scope picker is populated offline before the user first opens it. Room/property/
            // level types are already prefetched above; damage types/causes/claims are project-scoped on
            // the backend and arrive with each project's metadata, so they can't be seeded globally.
            companyId?.let { cid ->
                val syncRepo = (application as RocketPlanApplication).offlineSyncRepository
                lifecycleScope.launch {
                    runCatching { syncRepo.fetchWorkScopeCatalog(cid) }
                        .onFailure { Log.w(TAG, "⚠️ Prefetch work-scope catalog failed", it) }
                }
            }

            val userId = authRepository.getStoredUserId() ?: 0L
            val needsOnboarding = companyId == null && userId > 0L
            val cachedEmail = if (needsOnboarding) authRepository.getSavedEmail() ?: "" else ""

            // Navigate directly — no listener needed since we already know auth state
            withContext(Dispatchers.Main.immediate) {
                // RP-BUG-269: route unverified users to phone verification regardless of company status
                if (!isSmsVerified) {
                    Log.d(TAG, "User authenticated but not SMS-verified, redirecting to phone verification")
                    remoteLogger.log(
                        LogLevel.INFO,
                        "auth_sms",
                        "Routing gate: SMS not verified",
                        mapOf("userId" to userId.toString())
                    )
                    val bundle = Bundle().apply {
                        putLong("userId", userId)
                        putString("email", cachedEmail)
                    }
                    val navOptions = NavOptions.Builder()
                        .setPopUpTo(R.id.emailCheckFragment, true)
                        .build()
                    navController.navigate(R.id.phoneVerificationFragment, bundle, navOptions)
                    return@withContext
                }

                // RP-BUG-270: check for pending invite and auto-join if user is authenticated
                // RP-BUG-275: check addCompanyUser/setActiveCompany Results; only clear pending
                // invite on full success, retain+retry on failure, don't navigate as if joined
                val pendingInviteUuid = authRepository.getPendingInviteCompanyUuid()
                if (pendingInviteUuid != null) {
                    remoteLogger.log(
                        LogLevel.INFO,
                        "auth_invite_join",
                        "Routing gate: processing pending invite",
                        mapOf("userId" to userId.toString(), "companyUuid" to pendingInviteUuid)
                    )
                    Log.d(TAG, "Processing pending invite for company UUID: $pendingInviteUuid")
                    val joinResult = authRepository.resolveCompanyByUuid(pendingInviteUuid)
                    if (joinResult.isSuccess) {
                        val company = joinResult.getOrNull()
                        if (company != null) {
                            val addUserResult = authRepository.addCompanyUser(company.id, userId)
                            if (addUserResult.isFailure) {
                                remoteLogger.log(
                                    LogLevel.WARN,
                                    "auth_invite_join",
                                    "Routing gate: invite join failed - addCompanyUser",
                                    mapOf("userId" to userId.toString(), "companyId" to company.id.toString(), "error" to (addUserResult.exceptionOrNull()?.message ?: "unknown"))
                                )
                                Log.w(TAG, "Failed to add user to company: ${addUserResult.exceptionOrNull()?.message}")
                                // Retain pending invite for retry, don't navigate to projects
                            } else {
                                val setCompanyResult = authRepository.setActiveCompany(company.id)
                                if (setCompanyResult.isSuccess) {
                                    authRepository.clearPendingInviteCompanyUuid()
                                    remoteLogger.log(
                                        LogLevel.INFO,
                                        "auth_invite_join",
                                        "Routing gate: invite join success",
                                        mapOf("userId" to userId.toString(), "companyId" to company.id.toString())
                                    )
                                    Log.d(TAG, "Successfully joined company via invite: ${company.id}")
                                    val navOptions = NavOptions.Builder()
                                        .setPopUpTo(R.id.emailCheckFragment, true)
                                        .build()
                                    navController.navigate(R.id.nav_projects, null, navOptions)
                                    lifecycleScope.launch { syncQueueManager.ensureInitialSync() }
                                    return@withContext
                                } else {
                                    remoteLogger.log(
                                        LogLevel.WARN,
                                        "auth_invite_join",
                                        "Routing gate: invite join failed - setActiveCompany",
                                        mapOf("userId" to userId.toString(), "companyId" to company.id.toString(), "error" to (setCompanyResult.exceptionOrNull()?.message ?: "unknown"))
                                    )
                                    Log.w(TAG, "Failed to set active company: ${setCompanyResult.exceptionOrNull()?.message}")
                                    // Retain pending invite for retry, don't navigate to projects
                                }
                            }
                        }
                    } else {
                        remoteLogger.log(
                            LogLevel.WARN,
                            "auth_invite_join",
                            "Routing gate: invite join failed - resolveCompanyByUuid",
                            mapOf("userId" to userId.toString(), "companyUuid" to pendingInviteUuid, "error" to (joinResult.exceptionOrNull()?.message ?: "unknown"))
                        )
                        Log.w(TAG, "Failed to resolve invite company, clearing pending invite")
                        authRepository.clearPendingInviteCompanyUuid()
                    }
                }

                if (needsOnboarding) {
                    remoteLogger.log(
                        LogLevel.INFO,
                        "auth_company",
                        "Routing gate: needs onboarding",
                        mapOf("userId" to userId.toString())
                    )
                    Log.d(TAG, "User authenticated but no company, redirecting to onboarding")
                    val bundle = Bundle().apply {
                        putLong("userId", userId)
                        putString("email", cachedEmail)
                    }
                    val navOptions = NavOptions.Builder()
                        .setPopUpTo(R.id.emailCheckFragment, true)
                        .build()
                    navController.navigate(R.id.phoneVerificationFragment, bundle, navOptions)
                    return@withContext
                }

                remoteLogger.log(
                    LogLevel.INFO,
                    "auth_sms",
                    "Routing gate: fully authenticated, navigating to projects",
                    mapOf("userId" to userId.toString(), "companyId" to (companyId?.toString() ?: "null"))
                )
                if (BuildConfig.ENABLE_LOGGING) {
                    Log.d(TAG, "User authenticated, navigating to projects")
                }

                val navOptions = NavOptions.Builder()
                    .setPopUpTo(R.id.emailCheckFragment, true)
                    .build()
                navController.navigate(R.id.nav_projects, null, navOptions)
                lifecycleScope.launch {
                    syncQueueManager.ensureInitialSync()
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
        // Handle invite deep link
        handleInviteDeepLink(intent)
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
        isProcessingOAuth = true
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
            isProcessingOAuth = false
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "Ignoring deep link (not OAuth callback): $uri")
            }
            return false
        }

        val token = uri.getQueryParameter("token")
        val status = uri.getQueryParameter("status")?.toIntOrNull()
        val incomingState = uri.getQueryParameter("state")
        val expectedState = authRepository.getStoredOAuthState()
        // Always clear OAuth state immediately to prevent reuse
        authRepository.clearOAuthState()

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "OAuth callback - Status: $status, Token present: ${token != null}, State present: ${incomingState != null}")
        }

        if (expectedState.isNullOrBlank()) {
            Log.w(TAG, "OAuth callback received with no pending state; ignoring")
            isProcessingOAuth = false
            Toast.makeText(this, "Sign in failed: invalid session", Toast.LENGTH_LONG).show()
            return true
        }

        if (incomingState.isNullOrBlank() || incomingState != expectedState) {
            Log.w(TAG, "OAuth state mismatch; expected=$expectedState, received=$incomingState")
            isProcessingOAuth = false
            Toast.makeText(this, "Sign in failed: invalid session", Toast.LENGTH_LONG).show()
            return true
        }

        if (status == 200 && !token.isNullOrEmpty()) {
            // RP-BUG-278: route OAuth through shared gate check instead of hard-coding nav_home
            lifecycleScope.launch {
                try {
                    authRepository.saveAuthToken(token)
                    if (BuildConfig.ENABLE_LOGGING) {
                        Log.d(TAG, "OAuth token saved successfully")
                    }
                    authRepository.refreshUserContext().onFailure { error ->
                        Log.e(TAG, "Failed to refresh user context after OAuth", error)
                        authRepository.clearAuthToken()
                        Toast.makeText(
                            this@MainActivity,
                            "Sign in failed: unable to load account. Please try again.",
                            Toast.LENGTH_LONG
                        ).show()
                        isProcessingOAuth = false
                        return@launch
                    }
                    syncQueueManager.clear()
                    (application as RocketPlanApplication).imageProcessorQueueManager.abandonStaleServerAssemblies()

                    // Route through shared gate check (SMS/company gates)
                    val navController = findNavController(R.id.nav_host_fragment_content_main)
                    checkAuthenticationStatus(navController)

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
                } finally {
                    isProcessingOAuth = false
                }
            }
        } else {
            isProcessingOAuth = false
            Log.e(TAG, "OAuth callback failed - Status: $status")
            Toast.makeText(
                this,
                "Sign in failed",
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }

    /**
     * Handle invite deep link.
     * Parses the company UUID from the invite link and stores it for later processing
     * during auth state resolution (RP-BUG-270).
     */
    private fun handleInviteDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val inviteLink = InviteLink.parse(uri) ?: return

        if (BuildConfig.ENABLE_LOGGING) {
            Log.d(TAG, "Invite deep link received: companyUuid=${inviteLink.companyUuid}")
        }

        // Store the pending invite company UUID durably
        // RP-BUG-328: stored durably, not tied to fragment lifecycle
        authRepository.savePendingInviteCompanyUuid(inviteLink.companyUuid)
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
            menu.findItem(R.id.action_test_sentry)?.isVisible = BuildConfig.ENVIRONMENT == "DEV"
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
                    R.id.action_test_sentry -> {
                        Sentry.captureException(RuntimeException("Test Sentry error from Android"))
                        Toast.makeText(this@MainActivity, "Test error sent to Sentry", Toast.LENGTH_SHORT).show()
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

                // Clean up realtime subscriptions and Pusher connection
                val rocketPlanApp = application as RocketPlanApplication
                rocketPlanApp.photoSyncRealtimeManager.unsubscribe()
                rocketPlanApp.notesRealtimeManager.clear()
                rocketPlanApp.imageProcessorRealtimeManager.clear()
                rocketPlanApp.pusherService.disconnect()

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
            } finally {
                isForceSigningOut = false
            }
        }
    }

    private suspend fun checkCrmAccess() {
        try {
            val companyId = authRepository.getStoredCompanyId()
            if (companyId == null) {
                val bottomNav = binding.appBarMain.contentMain.bottomNavigation
                bottomNav.menu.findItem(R.id.nav_people)?.isVisible = false
                return
            }
            val api = RetrofitClient.createService<CrmUserApi>()
            val response = api.getGhlMe(companyId)
            val connected = response.body()?.connected == true
            if (BuildConfig.ENABLE_LOGGING) {
                Log.d(TAG, "CRM access check: connected=$connected")
            }
            val bottomNav = binding.appBarMain.contentMain.bottomNavigation
            bottomNav.menu.findItem(R.id.nav_people)?.isVisible = connected
        } catch (e: Exception) {
            Log.w(TAG, "CRM access check failed, hiding CRM tab", e)
            val bottomNav = binding.appBarMain.contentMain.bottomNavigation
            bottomNav.menu.findItem(R.id.nav_people)?.isVisible = false
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

    private fun setFullscreen(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (enabled) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
            )
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.BLACK),
                navigationBarStyle = SystemBarStyle.dark(Color.BLACK)
            )
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
                        // Check if this is an incoming (download) sync
                        val incomingItem = state.items.firstOrNull { it.entityType == "project_incoming" }
                        if (incomingItem != null) {
                            // For incoming sync, show step name in title and progress in subtitle
                            titleView.text = incomingItem.displayName // "Syncing: Rooms"
                            subtitleView.text = incomingItem.projectName ?: "" // "2 of 4"
                        } else {
                            // For outgoing sync, show generic title and items in subtitle
                            titleView.text = getString(R.string.sync_banner_syncing_title)
                            subtitleView.text = formatSyncingItems(state.items)
                        }
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

    /**
     * Check app version and flavor status on launch.
     * Shows a blocking dialog if the flavor is disabled or a mandatory update is required.
     */
    private fun checkAppVersionAndFlavor() {
        lifecycleScope.launch {
            try {
                val authService = RetrofitClient.createService<com.example.rocketplan_android.data.api.AuthService>()
                val flavor = if (BuildConfig.HAS_FLIR_SUPPORT) "flir" else "standard"
                // Send semantic version only (strip build number parens)
                val version = BuildConfig.VERSION_NAME.replace(Regex("\\s*\\(.*\\)"), "")
                val response = authService.checkAppVersion(
                    version = version,
                    flavor = flavor
                )
                val body = response.body() ?: return@launch

                when {
                    body.flavorDisabled -> {
                        val message = body.flavorMessage ?: "This version of the app is no longer available."
                        remoteLogger.log(
                            LogLevel.WARN,
                            TAG,
                            "Kill-switch: flavor disabled",
                            mapOf("flavor" to flavor, "message" to message)
                        )
                        remoteLogger.flush()
                        if (authRepository.isLoggedIn()) {
                            performSignOut()
                        }
                        showBlockingDialog("App Unavailable", message) {
                            finish()
                        }
                    }
                    body.mustUpdate -> {
                        remoteLogger.log(
                            LogLevel.WARN,
                            TAG,
                            "Kill-switch: mandatory update required",
                            mapOf("flavor" to flavor)
                        )
                        remoteLogger.flush()
                        showBlockingDialog("Update Required",
                            "A required update is available. Please update the app to continue."
                        ) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                            } catch (_: Exception) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                            }
                            finish()
                        }
                    }
                }
            } catch (e: Exception) {
                // Network error — don't block the app, just log
                Log.w(TAG, "App version check failed: ${e.message}")
            }
        }
    }

    private fun showBlockingDialog(title: String, message: String, onAction: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> onAction() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        syncStatusBannerManager?.destroy()
        syncStatusBannerManager = null
    }

}
