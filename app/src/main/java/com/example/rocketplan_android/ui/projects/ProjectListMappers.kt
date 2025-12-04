package com.example.rocketplan_android.ui.projects

import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.model.ProjectStatus

fun OfflineProjectEntity.toListItem(): ProjectListItem {
    val displayTitle = listOfNotNull(
        addressLine1?.takeIf { it.isNotBlank() },
        title.takeIf { it.isNotBlank() }
    ).firstOrNull() ?: "Project ${serverId ?: projectId}"
    val displayCode = uid?.takeIf { it.isNotBlank() }
        ?: projectNumber?.takeIf { it.isNotBlank() }
        ?: "RP-${serverId ?: projectId}"
    val displayAlias = alias?.takeIf { it.isNotBlank() }
    return ProjectListItem(
        projectId = projectId,
        title = displayTitle,
        projectCode = displayCode,
        alias = displayAlias,
        status = status,
        propertyId = propertyId
    )
}

fun ProjectListItem.matchesStatus(targetStatus: ProjectStatus): Boolean {
    return ProjectStatus.fromApiValue(status) == targetStatus
}
