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
            statusText.text = if (forceRefresh) "Reloading from server..." else "Loading..."

            val result = repository.getConfiguration(forceRefresh)

            loadingIndicator.visibility = View.GONE
            reloadButton.isEnabled = true

            result.onSuccess { config ->
                serviceText.text = "Service: ${config.service}"
                urlText.text = "URL: ${config.url}"
                val key = config.apiKey
                apiKeyText.text = if (key != null && key.length > 28) {
                    "API Key: ${key.take(20)}...${key.takeLast(8)}"
                } else {
                    "API Key: ${key ?: "N/A"}"
                }
                statusText.text = if (forceRefresh) "✅ Reloaded from server" else "✅ Loaded successfully"
                statusText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))

                if (forceRefresh) {
                    Toast.makeText(
                        requireContext(),
                        "Config reloaded: ${config.service}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { error ->
                serviceText.text = "Service: Error"
                urlText.text = "URL: Error"
                apiKeyText.text = "API Key: Error"
                statusText.text = "❌ Failed: ${error.message}"
                statusText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))

                Toast.makeText(
                    requireContext(),
                    "Failed to load config: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
