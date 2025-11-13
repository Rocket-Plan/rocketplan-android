package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.google.android.material.appbar.MaterialToolbar

/**
 * Initial screen in the Android create-project flow. Mirrors the iOS UI so users
 * can start by providing the loss address before moving deeper into project setup.
 */
class CreateProjectFragment : Fragment() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var manualEntryLink: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_create_project, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.createProjectToolbar)
        manualEntryLink = view.findViewById(R.id.manualEntryLink)

        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        manualEntryLink.setOnClickListener {
            Toast.makeText(
                requireContext(),
                R.string.create_project_manual_entry_toast,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
