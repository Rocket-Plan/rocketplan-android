package com.example.rocketplan_android.ui.auth

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.rocketplan_android.MainActivity
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentOauthWebviewBinding

/**
 * Renders OAuth and other web-based flows inside the app using WebView to avoid
 * launching external browsers.
 */
class OAuthWebViewFragment : Fragment() {

    private var _binding: FragmentOauthWebviewBinding? = null
    private val binding get() = _binding!!

    private val args: OAuthWebViewFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOauthWebviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupWebView()
        binding.webView.loadUrl(args.url)
    }

    private fun setupToolbar() {
        binding.toolbar.title = if (args.title.isNotBlank()) {
            args.title
        } else {
            getString(R.string.oauth_webview_default_title)
        }
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
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
                return handleUri(uri)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = url?.let(Uri::parse) ?: return false
                return handleUri(uri)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun handleUri(uri: Uri): Boolean {
        // Allow standard HTTP/HTTPS traffic to stay within WebView
        if (uri.scheme == "http" || uri.scheme == "https") {
            return false
        }

        val activity = requireActivity() as? MainActivity
        val handled = activity?.handleOAuthRedirect(uri) == true
        if (handled) {
            findNavController().navigateUp()
            return true
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.external_browser_blocked),
            Toast.LENGTH_LONG
        ).show()
        return true
    }

    override fun onDestroyView() {
        binding.webView.apply {
            stopLoading()
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }
}
