package com.example.rocketplan_android.ui.info

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentTermsAndConditionsBinding

class TermsAndConditionsFragment : Fragment() {

    private var _binding: FragmentTermsAndConditionsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTermsAndConditionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? AppCompatActivity)?.supportActionBar?.title =
            getString(R.string.terms_and_conditions_title)

        binding.progressBar.isVisible = savedInstanceState == null
        setupWebView()

        if (savedInstanceState == null) {
            binding.webView.loadUrl(TERMS_URL)
        } else {
            binding.swipeRefresh.isRefreshing = false
            binding.progressBar.isVisible = false
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.progressBar.isVisible = true
            binding.webView.reload()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return uri.scheme != "http" && uri.scheme != "https"
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val scheme = url?.substringBefore(":")?.lowercase()
                return scheme != null && scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.isVisible = false
                binding.swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                binding.progressBar.isVisible = false
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(
                    requireContext(),
                    getString(R.string.terms_loading_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        binding.swipeRefresh.setOnRefreshListener(null)
        binding.webView.apply {
            stopLoading()
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TERMS_URL = "https://rocketplantech.com/terms-conditions/"
    }
}
