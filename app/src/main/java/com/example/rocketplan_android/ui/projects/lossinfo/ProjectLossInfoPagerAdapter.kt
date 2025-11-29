package com.example.rocketplan_android.ui.projects.lossinfo

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class ProjectLossInfoPagerAdapter(
    fragment: Fragment,
    private val projectId: Long
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PropertyInfoFragment.newInstance(projectId)
            1 -> LossInfoFragment.newInstance(projectId)
            else -> ClaimsInfoFragment.newInstance(projectId)
        }
    }
}
