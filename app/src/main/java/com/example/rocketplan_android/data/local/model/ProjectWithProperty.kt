package com.example.rocketplan_android.data.local.model

import androidx.room.Embedded
import androidx.room.Relation
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity

/**
 * Lightweight projection of a project and its linked property so map screens can
 * access coordinates without additional queries.
 */
data class ProjectWithProperty(
    @Embedded val project: OfflineProjectEntity,
    @Relation(
        parentColumn = "propertyId",
        entityColumn = "propertyId"
    )
    val property: OfflinePropertyEntity?
)
