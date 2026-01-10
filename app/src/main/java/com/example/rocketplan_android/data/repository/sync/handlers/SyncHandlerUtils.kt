package com.example.rocketplan_android.data.repository.sync.handlers

import retrofit2.HttpException

internal const val SYNC_TAG = "API"

internal fun Throwable.isConflict(): Boolean = (this as? HttpException)?.code() == 409

internal fun Throwable.isMissingOnServer(): Boolean = (this as? HttpException)?.code() in listOf(404, 410)
