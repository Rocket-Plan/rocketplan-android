package com.example.rocketplan_android.ui.projects

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.rocketplan_android.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * Allows the user to manually type the full project address when search/lookup
 * is not available or does not return the desired property.
 */
class ManualAddressEntryFragment : Fragment() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var saveButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_manual_address, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.manualAddressToolbar)
        saveButton = view.findViewById(R.id.manualAddressSaveButton)

        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        saveButton.setOnClickListener {
            Toast.makeText(
                requireContext(),
                R.string.manual_address_toast,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
