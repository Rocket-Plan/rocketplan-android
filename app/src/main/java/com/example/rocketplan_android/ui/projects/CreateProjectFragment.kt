package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.R

/**
 * Entry point for creating a project. This fragment immediately forwards
 * to the appropriate address entry flow based on build variant:
 * - Standard builds: Address search with Google Places autocomplete
 * - FLIR builds: Manual address entry (no Places SDK)
 */
class CreateProjectFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_create_project, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (findNavController().currentDestination?.id == R.id.createProjectFragment) {
            if (BuildConfig.HAS_FLIR_SUPPORT) {
                // FLIR builds go directly to manual address entry
                findNavController().navigate(R.id.action_createProjectFragment_to_manualAddressEntryFragment)
            } else {
                // Standard builds use Google Places address search
                findNavController().navigate(R.id.action_createProjectFragment_to_addressSearchFragment)
            }
        }
    }
}
