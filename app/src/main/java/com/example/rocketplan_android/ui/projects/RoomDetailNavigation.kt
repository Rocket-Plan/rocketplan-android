package com.example.rocketplan_android.ui.projects

import java.io.Serializable

data class PhotosAddedResult(
    val addedCount: Int,
    val assemblyId: String?
) : Serializable
