package com.example.rocketplan_android.ui.projects.lossinfo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.example.rocketplan_android.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

/**
 * Lightweight container that mirrors the iOS Project/Loss Info entry point.
 * Hosts three tabs (Property, Loss, Claims) so we can start wiring the flow
 * without blocking on data plumbing.
 */
class ProjectLossInfoFragment : Fragment() {

    private val args: ProjectLossInfoFragmentArgs by navArgs()
    private val viewModel: ProjectLossInfoViewModel by viewModels {
        ProjectLossInfoViewModel.provideFactory(requireActivity().application, args.projectId)
    }

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_project_loss_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tabLayout = view.findViewById(R.id.lossInfoTabLayout)
        viewPager = view.findViewById(R.id.lossInfoViewPager)

        viewPager.adapter = ProjectLossInfoPagerAdapter(this, args.projectId)
        viewPager.offscreenPageLimit = 2 // Keep all three tabs alive for smoother switching
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.loss_info_tab_property)
                1 -> getString(R.string.loss_info_tab_loss)
                else -> getString(R.string.loss_info_tab_claims)
            }
        }.attach()

        updateToolbarTitle(getString(R.string.add_project_info_title))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val title = state.projectTitle ?: getString(R.string.add_project_info_title)
                    updateToolbarTitle(title)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    if (event is ProjectLossInfoEvent.PropertyMissing) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.loss_info_property_details_heading)
                            .setMessage(event.message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun updateToolbarTitle(title: String) {
        (activity as? AppCompatActivity)?.supportActionBar?.title = title
    }
}
