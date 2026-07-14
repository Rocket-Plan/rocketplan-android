package com.example.rocketplan_android.data.feature

import com.example.rocketplan_android.data.storage.SecureStorage

/**
 * RP-FR-019 — the serialized-equipment mode for the active company.
 *
 * Three states, per plan: the UI must mount the legacy count write-system only
 * when [OFF], the serialized write-system only when [ON], and NEITHER (a
 * retryable state) when [UNKNOWN] — never defaulting to legacy while the flag is
 * unknown. The flag is company-scoped and cached per company.
 */
enum class SerializedEquipmentMode {
    /** Company has serialized equipment enabled — mount serialized UI only. */
    ON,

    /** Company uses count-based equipment — mount legacy UI only. */
    OFF,

    /** Flag never fetched or fetch failed — mount neither; show retry. */
    UNKNOWN
}

/**
 * Resolves the serialized-equipment mode for the currently active company from
 * the per-company cache written by [SerializedEquipmentModeProvider.cache].
 */
class SerializedEquipmentModeProvider(
    private val secureStorage: SecureStorage
) {
    /** Mode for an explicit company id. */
    suspend fun modeFor(companyId: Long): SerializedEquipmentMode =
        when (secureStorage.getSerializedEquipmentEnabledSync(companyId)) {
            true -> SerializedEquipmentMode.ON
            false -> SerializedEquipmentMode.OFF
            null -> SerializedEquipmentMode.UNKNOWN
        }

    /** Mode for the active company; UNKNOWN if no active company is set. */
    suspend fun activeMode(): SerializedEquipmentMode {
        val companyId = secureStorage.getCompanyIdSync() ?: return SerializedEquipmentMode.UNKNOWN
        return modeFor(companyId)
    }

    /** Persist a freshly-fetched flag value for a company. */
    suspend fun cache(companyId: Long, enabled: Boolean) {
        secureStorage.saveSerializedEquipmentEnabled(companyId, enabled)
    }
}
