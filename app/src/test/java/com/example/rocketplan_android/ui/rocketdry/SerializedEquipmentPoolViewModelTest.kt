package com.example.rocketplan_android.ui.rocketdry

import app.cash.turbine.test
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class SerializedEquipmentPoolViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val companyId = 99L

    @Test
    fun `mode ON emits Ready with all statuses`() = runTest {
        val assets = MutableStateFlow(
            listOf(
                asset(1, "Air Mover", "available"),
                asset(2, "Dehu", "deployed"),
                asset(3, "Heater", "maintenance")
                // No retired fixture: the backend index never returns retired (soft-deleted) units
                // and locally-retired rows are isDeleted=true — they cannot appear in the pool.
            )
        )
        val vm = createViewModel(mode = true, assets = assets)

        vm.uiState.test {
            val ready = awaitReady()
            assertThat(ready.items.map { it.status }).containsExactly(
                "available", "deployed", "maintenance"
            )
            assertThat(ready.items.map { it.name }).containsExactly("Air Mover", "Dehu", "Heater")
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `status filter narrows the list`() = runTest {
        val assets = MutableStateFlow(
            listOf(
                asset(1, "Air Mover", "available"),
                asset(2, "Dehu", "deployed")
            )
        )
        val vm = createViewModel(mode = true, assets = assets)

        vm.uiState.test {
            awaitReady()
            vm.setStatusFilter(PoolStatusFilter.DEPLOYED)
            val filtered = awaitReadyMatching { it.items.size == 1 }
            assertThat(filtered.items.single().name).isEqualTo("Dehu")
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `search narrows the list by name serial and tag`() = runTest {
        val assets = MutableStateFlow(
            listOf(
                asset(1, "Air Mover", "available", serial = "SN-AAA"),
                asset(2, "Dehu", "available", assetTag = "TAG-BBB"),
                asset(3, "Heater", "available")
            )
        )
        val vm = createViewModel(mode = true, assets = assets)

        vm.uiState.test {
            awaitReady()
            vm.setSearchQuery("bbb")
            val filtered = awaitReadyMatching { it.items.size == 1 && it.items.first().name == "Dehu" }
            assertThat(filtered.items.single().name).isEqualTo("Dehu")
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `mode UNKNOWN emits Unavailable and never Ready`() = runTest {
        val vm = createViewModel(mode = null, assets = MutableStateFlow(emptyList()))

        vm.uiState.test {
            var state = awaitItem()
            while (state is SerializedPoolUiState.Loading) state = awaitItem()
            assertThat(state).isInstanceOf(SerializedPoolUiState.Unavailable::class.java)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `register enqueues via the repository`() = runTest {
        val repo = mockk<OfflineSyncRepository>(relaxed = true)
        coEvery {
            repo.registerEquipmentAssetOffline(any(), any(), any(), any(), any(), any(), any(), any())
        } returns asset(5, "New Unit", "available")

        val vm = createViewModel(mode = true, assets = MutableStateFlow(emptyList()), repo = repo)

        vm.uiState.test {
            awaitReady()
            vm.register("New Unit", "catalog-uuid-1", "SN-123")
            coVerify(timeout = 2_000) {
                repo.registerEquipmentAssetOffline(
                    companyId = companyId,
                    name = "New Unit",
                    catalogUuid = "catalog-uuid-1",
                    serialNumber = "SN-123"
                )
            }
            cancelAndConsumeRemainingEvents()
        }
    }

    // --- helpers ---

    private suspend fun app.cash.turbine.ReceiveTurbine<SerializedPoolUiState>.awaitReady(): SerializedPoolUiState.Ready {
        var state = awaitItem()
        while (state !is SerializedPoolUiState.Ready) state = awaitItem()
        return state
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<SerializedPoolUiState>.awaitReadyMatching(
        predicate: (SerializedPoolUiState.Ready) -> Boolean
    ): SerializedPoolUiState.Ready {
        while (true) {
            val state = awaitItem()
            if (state is SerializedPoolUiState.Ready && predicate(state)) return state
        }
    }

    private fun createViewModel(
        mode: Boolean?,
        assets: MutableStateFlow<List<OfflineEquipmentAssetEntity>>,
        repo: OfflineSyncRepository = mockk(relaxed = true)
    ): SerializedEquipmentPoolViewModel {
        val localDataService = mockk<LocalDataService>()
        val secureStorage = mockk<SecureStorage>()
        val authRepository = mockk<AuthRepository>(relaxed = true)
        val remoteLogger = mockk<RemoteLogger>(relaxed = true)

        every { secureStorage.observeSerializedEquipmentEnabled(companyId) } returns flowOf(mode)
        coEvery { secureStorage.getCompanyIdSync() } returns companyId
        every { localDataService.observeEquipmentAssetsForCompany(companyId) } returns assets
        coEvery { repo.refreshSerializedPool(companyId) } returns Result.success(Unit)

        val application = mockk<RocketPlanApplication>()
        every { application.localDataService } returns localDataService
        every { application.offlineSyncRepository } returns repo
        every { application.authRepository } returns authRepository
        every { application.secureStorage } returns secureStorage
        every { application.remoteLogger } returns remoteLogger
        every { application.getString(any()) } returns "message"

        return SerializedEquipmentPoolViewModel(application, companyId)
    }

    private fun asset(
        id: Long,
        name: String,
        status: String,
        serial: String? = null,
        assetTag: String? = null
    ): OfflineEquipmentAssetEntity =
        OfflineEquipmentAssetEntity(
            assetId = id,
            serverId = id,
            uuid = "asset-$id",
            companyId = companyId,
            catalogUuid = "catalog-$id",
            name = name,
            serialNumber = serial,
            assetTag = assetTag,
            status = status,
            createdAt = Date(),
            updatedAt = Date(),
            syncStatus = SyncStatus.SYNCED
        )
}
