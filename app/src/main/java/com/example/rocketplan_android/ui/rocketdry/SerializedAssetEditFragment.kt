package com.example.rocketplan_android.ui.rocketdry

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentSerializedAssetEditBinding
import kotlinx.coroutines.launch
import java.util.Calendar

class SerializedAssetEditFragment : Fragment() {

    private val args: SerializedAssetEditFragmentArgs by navArgs()
    private val viewModel: SerializedAssetEditViewModel by viewModels {
        SerializedAssetEditViewModel.provideFactory(requireActivity().application, args.assetLocalId)
    }

    private var _binding: FragmentSerializedAssetEditBinding? = null
    private val binding get() = _binding!!

    // Prefill fires exactly once per asset load. Persisted across configuration changes AND
    // process death so a rotation (or a low-memory restore) mid-edit does not re-run prefill and
    // overwrite the user's in-progress edits — the TextInputEditText / toggle values are restored
    // by the framework's own view-state saving, so we must NOT clobber them.
    private var hasPrefilled = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSerializedAssetEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasPrefilled = savedInstanceState?.getBoolean(KEY_HAS_PREFILLED) ?: false

        // Dates are click-to-pick; the TextInputEditText text is the single source of truth
        // (and is restored by the framework across rotation/process death) — no separate field.
        binding.editPurchaseDate.setOnClickListener { showDatePicker { binding.editPurchaseDate.setText(it) } }
        binding.editWarrantyExpires.setOnClickListener { showDatePicker { binding.editWarrantyExpires.setText(it) } }

        binding.editSaveButton.setOnClickListener { save() }

        // Keyboard handling matches the app's other full-screen forms (e.g. SetLatestAverageFragment):
        // put the window in ADJUST_RESIZE so it shrinks for the IME, which lets the weighted
        // NestedScrollView compress and keeps the pinned Save button visible above the keyboard.
        // The window softInputMode is app-wide and other screens leave it in ADJUST_PAN, so we set it
        // here and restore it in onDestroyView.
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        observe()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HAS_PREFILLED, hasPrefilled)
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        render(state)
                    }
                }
                launch {
                    viewModel.events.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }
                }
                launch {
                    viewModel.finished.collect { findNavController().popBackStack() }
                }
            }
        }
    }

    private fun render(state: AssetEditUiState) {
        val ready = state as? AssetEditUiState.Ready
        binding.editLoading.isVisible = state is AssetEditUiState.Loading || ready?.saving == true
        binding.editSaveButton.isEnabled = ready != null && !ready.saving

        ready?.let { readyState ->
            val asset = readyState.asset
            val statusEditable = asset.status in EDITABLE_STATUSES

            // Prefill the editable field values exactly once. On a config-change / process-death
            // restore the framework has already re-populated the TextInputEditText + toggle, so we
            // skip this block and leave the user's in-progress edits intact.
            if (!hasPrefilled) {
                binding.editSerialNumber.setText(asset.serialNumber ?: "")
                binding.editAssetTag.setText(asset.assetTag ?: "")
                binding.editNote.setText(asset.note ?: "")
                binding.editManufacturer.setText(asset.manufacturer ?: "")
                binding.editModel.setText(asset.model ?: "")
                binding.editVendor.setText(asset.vendor ?: "")
                // Show date-only in the field (API sends UTC-midnight ISO timestamps); the picker
                // also writes yyyy-MM-dd, so an untouched value round-trips in the same format.
                binding.editPurchaseDate.setText(asset.purchaseDate?.substringBefore('T') ?: "")
                binding.editPurchasePrice.setText(asset.purchasePrice ?: "")
                binding.editWarrantyExpires.setText(asset.warrantyExpiresAt?.substringBefore('T') ?: "")
                binding.editRentalRate.setText(asset.rentalDayRate ?: "")
                if (statusEditable) {
                    binding.editStatusToggle.check(
                        if (asset.status == "maintenance") R.id.editStatusMaintenance else R.id.editStatusAvailable
                    )
                }
                hasPrefilled = true
            }

            // Idempotent per render (derives only from the stable asset.status + placement state),
            // so it stays correct after a rotation that skipped prefill.
            binding.editStatusToggle.isVisible = statusEditable
            binding.editStatusReadOnly.isVisible = !statusEditable
            if (!statusEditable) {
                binding.editStatusReadOnly.text = asset.status.replaceFirstChar { it.uppercase() }
            }

            val canEditStatus = statusEditable && !readyState.hasOpenPlacement
            binding.editMaintenanceHint.isVisible = readyState.hasOpenPlacement && statusEditable
            binding.editStatusMaintenance.isEnabled = canEditStatus
            binding.editStatusAvailable.isEnabled = canEditStatus
        }
    }

    private fun save() {
        val purchasePrice = binding.editPurchasePrice.text?.toString()?.takeIf { it.isNotBlank() }
        val rentalRate = binding.editRentalRate.text?.toString()?.takeIf { it.isNotBlank() }

        if (purchasePrice != null && purchasePrice.toBigDecimalOrNull() == null) {
            Toast.makeText(requireContext(), R.string.serialized_equipment_edit_invalid_price, Toast.LENGTH_SHORT).show()
            return
        }
        if (rentalRate != null && rentalRate.toBigDecimalOrNull() == null) {
            Toast.makeText(requireContext(), R.string.serialized_equipment_edit_invalid_rate, Toast.LENGTH_SHORT).show()
            return
        }

        val status = when (binding.editStatusToggle.checkedButtonId) {
            R.id.editStatusMaintenance -> "maintenance"
            else -> "available"
        }
        viewModel.save(
            serialNumber = binding.editSerialNumber.text?.toString()?.takeIf { it.isNotBlank() },
            assetTag = binding.editAssetTag.text?.toString()?.takeIf { it.isNotBlank() },
            status = status,
            note = binding.editNote.text?.toString()?.takeIf { it.isNotBlank() },
            manufacturer = binding.editManufacturer.text?.toString()?.takeIf { it.isNotBlank() },
            model = binding.editModel.text?.toString()?.takeIf { it.isNotBlank() },
            vendor = binding.editVendor.text?.toString()?.takeIf { it.isNotBlank() },
            purchaseDate = binding.editPurchaseDate.text?.toString()?.takeIf { it.isNotBlank() },
            purchasePrice = purchasePrice,
            warrantyExpiresAt = binding.editWarrantyExpires.text?.toString()?.takeIf { it.isNotBlank() },
            rentalDayRate = rentalRate
        )
    }

    private fun showDatePicker(onDateSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val formatted = String.format("%04d-%02d-%02d", year, month + 1, day)
                onDateSelected(formatted)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    override fun onDestroyView() {
        // Restore the app-wide default so we don't leave other screens in RESIZE.
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val EDITABLE_STATUSES = setOf("available", "maintenance")
        const val KEY_HAS_PREFILLED = "rp_fr_029_has_prefilled"
    }
}
