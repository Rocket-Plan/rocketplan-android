package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R

/**
 * Legacy entry point for creating a project. This fragment now immediately forwards
 * to the manual address entry flow to match the simplified UX.
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
            findNavController().navigate(R.id.action_createProjectFragment_to_manualAddressEntryFragment)
        }
    }
}
