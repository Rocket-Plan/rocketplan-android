package com.example.rocketplan_android.data.model

import androidx.annotation.StringRes
import com.example.rocketplan_android.R

enum class ProjectStatus(
    val backendId: Int,
    val apiValue: String,
    @StringRes val labelRes: Int
) {
    WIP(1, "wip", R.string.project_status_wip),
    COMPLETED(2, "completed", R.string.project_status_completed),
    ESTIMATE(3, "estimate", R.string.project_status_estimate),
    UNDER_CONTRACT(4, "under_contract", R.string.project_status_under_contract),
    LEAD_STAGE(5, "lead_stage", R.string.project_status_lead_stage),
    INVOICED(6, "invoiced", R.string.project_status_invoiced),
    PAID(7, "paid", R.string.project_status_paid),
    RECON(8, "recon", R.string.project_status_recon);

    companion object {
        val orderedStatuses: List<ProjectStatus> = listOf(
            RECON,
            WIP,
            ESTIMATE,
            UNDER_CONTRACT,
            LEAD_STAGE,
            INVOICED,
            PAID,
            COMPLETED
        )

        fun fromBackendId(id: Int?): ProjectStatus? =
            values().firstOrNull { it.backendId == id }

        fun fromApiValue(value: String?): ProjectStatus? =
            values().firstOrNull { it.apiValue.equals(value, ignoreCase = true) }
    }
}
