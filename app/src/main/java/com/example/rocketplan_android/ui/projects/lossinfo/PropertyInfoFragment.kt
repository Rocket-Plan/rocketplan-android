package com.example.rocketplan_android.ui.projects.lossinfo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.rocketplan_android.R

class PropertyInfoFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_property_info_tab, container, false)

    companion object {
        private const val ARG_PROJECT_ID = "arg_project_id"

        fun newInstance(projectId: Long): PropertyInfoFragment {
            return PropertyInfoFragment().apply {
                arguments = Bundle().apply { putLong(ARG_PROJECT_ID, projectId) }
            }
        }
    }
}
