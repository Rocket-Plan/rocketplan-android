package com.example.rocketplan_android.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentMapBinding
import com.example.rocketplan_android.ui.projects.ProjectListItem
import com.example.rocketplan_android.ui.projects.ProjectsAdapter
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import android.util.Log

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()
    private lateinit var adapter: ProjectsAdapter

    private var googleMap: GoogleMap? = null
    private var latestMarkers: List<MapMarker> = emptyList()
    private var selectedTab: MapTab = MapTab.MY_PROJECTS
    private var lastKnownLocation: Location? = null
    private var hasAppliedInitialCamera = false
    private var userHasMovedMap = false

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(requireContext())
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableMyLocation()
            moveToUserLocation()
        } else {
            Toast.makeText(
                requireContext(),
                R.string.map_location_permission_denied,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMap()
        setupTabs()
        setupList()
        setupButtons()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        googleMap = null
        hasAppliedInitialCamera = false
        userHasMovedMap = false
        _binding = null
    }

    private fun setupMap() {
        val fragment = childFragmentManager.findFragmentById(R.id.mapFragmentContainer) as? SupportMapFragment
            ?: SupportMapFragment.newInstance().also {
                childFragmentManager.beginTransaction()
                    .replace(R.id.mapFragmentContainer, it)
                    .commit()
            }

        fragment.getMapAsync { map ->
            googleMap = map
            map.uiSettings.isMapToolbarEnabled = false
            map.uiSettings.isMyLocationButtonEnabled = false
            map.setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    userHasMovedMap = true
                }
            }
            applyMapStyle(map)
            enableMyLocation()
            updateMarkers(latestMarkers)
            Log.d(TAG, "Map ready, markers=${latestMarkers.size}")
        }
    }

    private fun setupTabs() {
        binding.mapTabs.apply {
            addTab(newTab().setText(R.string.projects_tab_my_projects))
            addTab(newTab().setText(R.string.project_status_wip))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    selectedTab = if (tab?.position == 1) MapTab.WIP else MapTab.MY_PROJECTS
                    Log.d(TAG, "Tab selected: $selectedTab")
                    renderLatestState()
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
                override fun onTabReselected(tab: TabLayout.Tab?) {
                    // reselect resets camera to markers for convenience
                    userHasMovedMap = false
                    renderLatestState()
                }
            })
        }
    }

    private fun setupList() {
        adapter = ProjectsAdapter { onProjectSelected(it) }
        binding.projectsRecyclerView.adapter = adapter
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.refreshProjects() }
    }

    private fun setupButtons() {
        binding.fabNewProject.setOnClickListener {
            findNavController().navigate(R.id.createProjectFragment)
        }

        binding.viewAllButton.setOnClickListener { openProjectsTab() }

        binding.currentLocationButton.setOnClickListener {
            moveToUserLocation()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { renderState(it) }
                }
                launch {
                    viewModel.isRefreshing.collect { isRefreshing ->
                        binding.swipeRefreshLayout.isRefreshing = isRefreshing
                    }
                }
            }
        }
    }

    private fun renderState(state: MapUiState) {
        when (state) {
            MapUiState.Loading -> {
                binding.progressBar.isVisible = true
                binding.emptyState.isVisible = false
                binding.swipeRefreshLayout.isVisible = false
            }
            is MapUiState.Error -> {
                binding.progressBar.isVisible = false
                binding.swipeRefreshLayout.isVisible = false
                binding.swipeRefreshLayout.isRefreshing = false
                binding.emptyState.isVisible = true
                binding.emptyState.text = state.message
            }
            is MapUiState.Ready -> {
                binding.progressBar.isVisible = false
                binding.swipeRefreshLayout.isVisible = true
                updateMarkers(state.markers)
                renderList(state)
            }
        }
    }

    private fun renderList(state: MapUiState.Ready) {
        val items = when (selectedTab) {
            MapTab.MY_PROJECTS -> state.myProjects
            MapTab.WIP -> state.wipProjects
        }
        adapter.submitList(items)

        binding.emptyState.isVisible = items.isEmpty()
        binding.emptyState.setText(
            if (selectedTab == MapTab.MY_PROJECTS) {
                R.string.map_empty_my_projects
            } else {
                R.string.map_empty_wip
            }
        )
    }

    private fun renderLatestState() {
        (viewModel.uiState.value as? MapUiState.Ready)?.let { renderList(it) }
    }

    private fun updateMarkers(markers: List<MapMarker>) {
        latestMarkers = markers
        val map = googleMap ?: return
        map.clear()

        val sample = markers.take(3).joinToString { "[${it.projectId}] ${it.title}" }
        Log.d(TAG, "Updating markers: count=${markers.size}, sample=$sample")

        markers.forEach { marker ->
            val position = LatLng(marker.latitude, marker.longitude)
            map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title(marker.title)
                    .snippet(marker.projectCode)
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.icon_pin_project))
            )
        }

        val target = when {
            markers.isNotEmpty() -> LatLng(markers.first().latitude, markers.first().longitude)
            lastKnownLocation != null -> LatLng(
                lastKnownLocation!!.latitude,
                lastKnownLocation!!.longitude
            )
            else -> DEFAULT_LOCATION
        }

        if (!hasAppliedInitialCamera || !userHasMovedMap) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, DEFAULT_ZOOM))
            hasAppliedInitialCamera = true
        }
    }

    private fun onProjectSelected(project: ProjectListItem) {
        val args = bundleOf("projectId" to project.projectId)
        findNavController().navigate(R.id.projectLandingFragment, args)
    }

    private fun openProjectsTab() {
        val tabKey = if (selectedTab == MapTab.WIP) {
            MapTab.WIP.key
        } else {
            MapTab.MY_PROJECTS.key
        }
        val args = bundleOf("initialTab" to tabKey)
        findNavController().navigate(R.id.nav_projects, args)
    }

    private fun applyMapStyle(map: GoogleMap) {
        // TODO: Re-enable map styling once MapStyleOptions import is resolved
        // try {
        //     map.setMapStyle(
        //         MapStyleOptions.loadRawResourceStyle(
        //             requireContext(),
        //             R.raw.google_maps_style
        //         )
        //     )
        // } catch (_: Exception) {
        //     // If style fails to load, fall back to default map styling
        // }
    }

    private fun moveToUserLocation() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        val cancellationTokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            cancellationTokenSource.token
        ).addOnSuccessListener { location ->
            location?.let {
                lastKnownLocation = it
                userHasMovedMap = false
                Log.d(TAG, "Moving to user location: ${it.latitude}, ${it.longitude}")
                googleMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(it.latitude, it.longitude),
                        13f
                    )
                )
            }
        }.addOnFailureListener {
            Log.w(TAG, "getCurrentLocation failed: ${it.localizedMessage}")
            Toast.makeText(
                requireContext(),
                R.string.map_location_unavailable,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun enableMyLocation() {
        if (hasLocationPermission()) {
            googleMap?.isMyLocationEnabled = true
            fetchLastKnownLocation()
        } else {
            Log.d(TAG, "Requesting location permission for My Location")
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun fetchLastKnownLocation() {
        if (!hasLocationPermission()) return
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                lastKnownLocation = location
                Log.d(TAG, "Last known location: ${location.latitude}, ${location.longitude}")
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private companion object {
        private const val TAG = "MapFragment"
        private val DEFAULT_LOCATION = LatLng(49.283884, -123.077592)
        private const val DEFAULT_ZOOM = 10f
    }
}

private enum class MapTab(val key: String) {
    MY_PROJECTS("my_projects"),
    WIP("wip")
}
