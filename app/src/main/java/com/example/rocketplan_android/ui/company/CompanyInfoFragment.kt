package com.example.rocketplan_android.ui.company

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.R
import com.example.rocketplan_android.databinding.FragmentCompanyInfoBinding
import kotlinx.coroutines.launch

class CompanyInfoFragment : Fragment() {

    private var _binding: FragmentCompanyInfoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CompanyInfoViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCompanyInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.retryButton.setOnClickListener {
            viewModel.refreshCompanyInfo()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshCompanyInfo()
        }

        binding.environmentValue.text = getString(
            R.string.company_info_environment_value,
            BuildConfig.ENVIRONMENT,
            BuildConfig.VERSION_NAME
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is CompanyInfoUiState.Loading -> showLoading()
                        is CompanyInfoUiState.Error -> showError(state.message)
                        is CompanyInfoUiState.Content -> showContent(state)
                    }
                }
            }
        }
    }

    private fun showLoading() {
        binding.swipeRefresh.isRefreshing = false
        binding.loadingContainer.isVisible = true
        binding.contentContainer.isVisible = false
        binding.errorContainer.isVisible = false
    }

    private fun showError(message: String) {
        binding.swipeRefresh.isRefreshing = false
        binding.loadingContainer.isVisible = false
        binding.contentContainer.isVisible = false
        binding.errorContainer.isVisible = true
        binding.errorText.text = message
    }

    private fun showContent(content: CompanyInfoUiState.Content) {
        binding.swipeRefresh.isRefreshing = content.isRefreshing
        binding.loadingContainer.isVisible = false
        binding.errorContainer.isVisible = false
        binding.contentContainer.isVisible = true

        binding.companyName.text = content.companyName
        binding.companyId.text = content.companyId?.let {
            getString(R.string.company_info_company_id_value, it)
        } ?: getString(R.string.company_info_company_id_missing)
        binding.userName.text = content.userName
        binding.userEmail.text = content.userEmail

        updateLogo(content.logoUrl)
    }

    private fun updateLogo(logoUrl: String?) {
        if (logoUrl.isNullOrBlank()) {
            applyDefaultLogo()
            return
        }

        binding.companyLogo.load(logoUrl) {
            crossfade(true)
            listener(
                onStart = {
                    binding.companyLogo.scaleType = ImageView.ScaleType.CENTER_CROP
                    ImageViewCompat.setImageTintList(binding.companyLogo, null)
                },
                onError = { _, _ ->
                    applyDefaultLogo()
                }
            )
        }
    }

    private fun applyDefaultLogo() {
        binding.companyLogo.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val white = ContextCompat.getColor(requireContext(), android.R.color.white)
        ImageViewCompat.setImageTintList(binding.companyLogo, ColorStateList.valueOf(white))
        binding.companyLogo.setImageResource(R.drawable.ic_building)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
