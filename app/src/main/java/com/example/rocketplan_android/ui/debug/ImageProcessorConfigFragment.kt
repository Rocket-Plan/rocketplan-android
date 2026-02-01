package com.example.rocketplan_android.ui.debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import kotlinx.coroutines.launch

/**
 * Fragment that displays the current image processor configuration
 * and allows reloading it from the server.
 */
class ImageProcessorConfigFragment : Fragment() {

    private lateinit var serviceText: TextView
    private lateinit var urlText: TextView
    private lateinit var apiKeyText: TextView
    private lateinit var statusText: TextView
    private lateinit var reloadButton: Button
    private lateinit var loadingIndicator: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_image_processor_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        serviceText = view.findViewById(R.id.serviceText)
        urlText = view.findViewById(R.id.urlText)
        apiKeyText = view.findViewById(R.id.apiKeyText)
        statusText = view.findViewById(R.id.statusText)
        reloadButton = view.findViewById(R.id.reloadButton)
        loadingIndicator = view.findViewById(R.id.loadingIndicator)

        reloadButton.setOnClickListener {
            loadConfiguration(forceRefresh = true)
        }

        loadConfiguration(forceRefresh = false)
    }

    private fun loadConfiguration(forceRefresh: Boolean) {
        val app = requireActivity().application as RocketPlanApplication
        val repository = app.imageProcessingConfigurationRepository

        lifecycleScope.launch {
            loadingIndicator.visibility = View.VISIBLE
            reloadButton.isEnabled = false
            statusText.text = if (forceRefresh) getString(R.string.config_reloading) else getString(R.string.config_loading)

            val result = repository.getConfiguration(forceRefresh)

            loadingIndicator.visibility = View.GONE
            reloadButton.isEnabled = true

            result.onSuccess { config ->
                serviceText.text = getString(R.string.config_service_format, config.service)
                urlText.text = getString(R.string.config_url_format, config.url)
                val key = config.apiKey
                apiKeyText.text = if (key != null && key.length > 28) {
                    getString(R.string.config_api_key_truncated_format, key.take(20), key.takeLast(8))
                } else {
                    getString(R.string.config_api_key_format, key ?: getString(R.string.config_api_key_na))
                }
                statusText.text = if (forceRefresh) getString(R.string.config_reloaded_success) else getString(R.string.config_loaded_success)
                statusText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))

                if (forceRefresh) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.config_reloaded_toast, config.service),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { error ->
                val errorLabel = getString(R.string.config_error_label)
                serviceText.text = getString(R.string.config_service_format, errorLabel)
                urlText.text = getString(R.string.config_url_format, errorLabel)
                apiKeyText.text = getString(R.string.config_api_key_format, errorLabel)
                statusText.text = getString(R.string.config_failed_format, error.message)
                statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))

                Toast.makeText(
                    requireContext(),
                    getString(R.string.config_load_failed_toast, error.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
