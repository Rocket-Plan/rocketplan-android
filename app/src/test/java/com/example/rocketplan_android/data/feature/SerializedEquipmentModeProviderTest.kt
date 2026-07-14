package com.example.rocketplan_android.data.feature

import com.example.rocketplan_android.data.storage.SecureStorage
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RP-FR-019 — the serialized-equipment mode must resolve to exactly three states
 * so the UI can mount legacy-only (OFF), serialized-only (ON), or neither with a
 * retry (UNKNOWN). A cache miss must NEVER collapse to OFF.
 */
class SerializedEquipmentModeProviderTest {

    private val secureStorage = mockk<SecureStorage>()
    private val provider = SerializedEquipmentModeProvider(secureStorage)

    @Test
    fun `cached true resolves to ON`() = runTest {
        coEvery { secureStorage.getSerializedEquipmentEnabledSync(7) } returns true
        assertThat(provider.modeFor(7)).isEqualTo(SerializedEquipmentMode.ON)
    }

    @Test
    fun `cached false resolves to OFF`() = runTest {
        coEvery { secureStorage.getSerializedEquipmentEnabledSync(7) } returns false
        assertThat(provider.modeFor(7)).isEqualTo(SerializedEquipmentMode.OFF)
    }

    @Test
    fun `cache miss resolves to UNKNOWN not OFF`() = runTest {
        coEvery { secureStorage.getSerializedEquipmentEnabledSync(7) } returns null
        assertThat(provider.modeFor(7)).isEqualTo(SerializedEquipmentMode.UNKNOWN)
    }

    @Test
    fun `activeMode is UNKNOWN when no active company`() = runTest {
        coEvery { secureStorage.getCompanyIdSync() } returns null
        assertThat(provider.activeMode()).isEqualTo(SerializedEquipmentMode.UNKNOWN)
    }

    @Test
    fun `activeMode reads the active company flag`() = runTest {
        coEvery { secureStorage.getCompanyIdSync() } returns 42L
        coEvery { secureStorage.getSerializedEquipmentEnabledSync(42) } returns true
        assertThat(provider.activeMode()).isEqualTo(SerializedEquipmentMode.ON)
    }

    @Test
    fun `observeMode maps flag emissions to modes`() = runTest {
        io.mockk.every { secureStorage.observeSerializedEquipmentEnabled(7) } returns
            kotlinx.coroutines.flow.flowOf(true, false, null)
        val modes = mutableListOf<SerializedEquipmentMode>()
        provider.observeMode(7).collect { modes.add(it) }
        assertThat(modes).containsExactly(
            SerializedEquipmentMode.ON,
            SerializedEquipmentMode.OFF,
            SerializedEquipmentMode.UNKNOWN
        ).inOrder()
    }

    @Test
    fun `mode is per-company isolated`() = runTest {
        coEvery { secureStorage.getSerializedEquipmentEnabledSync(1) } returns true
        coEvery { secureStorage.getSerializedEquipmentEnabledSync(2) } returns false
        assertThat(provider.modeFor(1)).isEqualTo(SerializedEquipmentMode.ON)
        assertThat(provider.modeFor(2)).isEqualTo(SerializedEquipmentMode.OFF)
    }
}
