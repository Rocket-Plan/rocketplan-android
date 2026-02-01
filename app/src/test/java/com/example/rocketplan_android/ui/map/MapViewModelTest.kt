package com.example.rocketplan_android.ui.map

import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.model.ProjectWithProperty
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val companyId = 1L
    private val projectsFlow = MutableStateFlow<List<ProjectWithProperty>>(emptyList())
    private val companyIdFlow = MutableStateFlow<Long?>(companyId)
    private val assignedProjectsFlow = MutableStateFlow<Set<Long>>(emptySet())
    private val errorsFlow = MutableStateFlow("")

    private val syncQueueManager = mockk<SyncQueueManager>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()
    private val localDataService = mockk<LocalDataService>(relaxed = true)
    private val remoteLogger = mockk<RemoteLogger>(relaxed = true)

    private fun createViewModel(): MapViewModel {
        val application = mockk<RocketPlanApplication>()
        every { application.localDataService } returns localDataService
        every { application.syncQueueManager } returns syncQueueManager
        every { application.authRepository } returns authRepository
        every { application.remoteLogger } returns remoteLogger

        every { localDataService.observeProjectsWithProperty() } returns projectsFlow
        every { authRepository.observeCompanyId() } returns companyIdFlow
        every { syncQueueManager.assignedProjects } returns assignedProjectsFlow
        every { syncQueueManager.errors } returns errorsFlow
        coJustRun { syncQueueManager.ensureInitialSync() }

        return MapViewModel(application)
    }

    @Test
    fun `calls ensureInitialSync on init`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        io.mockk.coVerify { syncQueueManager.ensureInitialSync() }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `emits ready state with projects`() = runTest {
        val project = createProject(projectId = 1L)
        projectsFlow.value = listOf(ProjectWithProperty(project, null))
        assignedProjectsFlow.value = setOf(1L)

        val viewModel = createViewModel()

        repeat(5) {
            advanceUntilIdle()
            if (viewModel.uiState.value is MapUiState.Ready) return@repeat
        }

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(MapUiState.Ready::class.java)
        val ready = state as MapUiState.Ready
        assertThat(ready.myProjects).hasSize(1)
        assertThat(ready.myProjects.first().projectId).isEqualTo(1L)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `filters projects by company`() = runTest {
        val project1 = createProject(projectId = 1L, companyId = companyId)
        val project2 = createProject(projectId = 2L, companyId = 999L)
        projectsFlow.value = listOf(
            ProjectWithProperty(project1, null),
            ProjectWithProperty(project2, null)
        )
        assignedProjectsFlow.value = setOf(1L, 2L)

        val viewModel = createViewModel()

        repeat(5) {
            advanceUntilIdle()
            if (viewModel.uiState.value is MapUiState.Ready) return@repeat
        }

        val ready = viewModel.uiState.value as MapUiState.Ready
        // Only project1 should be visible (matches companyId)
        assertThat(ready.myProjects).hasSize(1)
        assertThat(ready.myProjects.first().projectId).isEqualTo(1L)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `creates markers for WIP projects with coordinates`() = runTest {
        val property = createProperty(latitude = 40.7128, longitude = -74.0060)
        val project = createProject(projectId = 1L, status = "wip")
        projectsFlow.value = listOf(ProjectWithProperty(project, property))
        assignedProjectsFlow.value = setOf(1L)

        val viewModel = createViewModel()

        repeat(5) {
            advanceUntilIdle()
            if (viewModel.uiState.value is MapUiState.Ready) return@repeat
        }

        val ready = viewModel.uiState.value as MapUiState.Ready
        assertThat(ready.wipProjects).hasSize(1)
        assertThat(ready.markers).hasSize(1)
        val marker = ready.markers.first()
        assertThat(marker.latitude).isEqualTo(40.7128)
        assertThat(marker.longitude).isEqualTo(-74.0060)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `excludes projects without coordinates from markers`() = runTest {
        val project = createProject(projectId = 1L, status = "wip")
        // No property means no coordinates
        projectsFlow.value = listOf(ProjectWithProperty(project, null))
        assignedProjectsFlow.value = setOf(1L)

        val viewModel = createViewModel()

        repeat(5) {
            advanceUntilIdle()
            if (viewModel.uiState.value is MapUiState.Ready) return@repeat
        }

        val ready = viewModel.uiState.value as MapUiState.Ready
        assertThat(ready.wipProjects).hasSize(1)
        assertThat(ready.markers).isEmpty()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `separates my projects from WIP projects`() = runTest {
        val myProject = createProject(projectId = 1L, status = "estimate")
        val wipProject = createProject(projectId = 2L, status = "wip")
        projectsFlow.value = listOf(
            ProjectWithProperty(myProject, null),
            ProjectWithProperty(wipProject, null)
        )
        // Only project 1 is assigned to me
        assignedProjectsFlow.value = setOf(1L)

        val viewModel = createViewModel()

        repeat(5) {
            advanceUntilIdle()
            if (viewModel.uiState.value is MapUiState.Ready) return@repeat
        }

        val ready = viewModel.uiState.value as MapUiState.Ready
        assertThat(ready.myProjects).hasSize(1)
        assertThat(ready.myProjects.first().projectId).isEqualTo(1L)
        assertThat(ready.wipProjects).hasSize(1)
        assertThat(ready.wipProjects.first().projectId).isEqualTo(2L)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `refreshProjects sets refreshing flag and calls sync`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refreshProjects()

        assertThat(viewModel.isRefreshing.value).isTrue()
        verify { syncQueueManager.refreshProjectsIncremental() }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `clears refreshing flag when data updates`() = runTest {
        projectsFlow.value = listOf(ProjectWithProperty(createProject(1L), null))
        assignedProjectsFlow.value = setOf(1L)

        val viewModel = createViewModel()

        repeat(5) {
            advanceUntilIdle()
            if (viewModel.uiState.value is MapUiState.Ready) return@repeat
        }

        assertThat(viewModel.isRefreshing.value).isFalse()

        viewModel.viewModelScope.cancel()
    }

    private fun createProject(
        projectId: Long,
        companyId: Long = this.companyId,
        status: String = "estimate"
    ) = OfflineProjectEntity(
        projectId = projectId,
        serverId = projectId,
        uuid = "uuid-$projectId",
        title = "Project $projectId",
        projectNumber = "RP-25-$projectId",
        uid = null,
        alias = null,
        addressLine1 = "123 Main St",
        addressLine2 = null,
        status = status,
        propertyType = null,
        companyId = companyId,
        propertyId = projectId * 10,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false,
        createdAt = Date(),
        updatedAt = Date(),
        lastSyncedAt = Date()
    )

    private fun createProperty(
        propertyId: Long = 100L,
        latitude: Double? = null,
        longitude: Double? = null
    ) = OfflinePropertyEntity(
        propertyId = propertyId,
        serverId = propertyId,
        uuid = "property-$propertyId",
        address = "123 Main St",
        city = "New York",
        state = "NY",
        zipCode = "10001",
        latitude = latitude,
        longitude = longitude,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        createdAt = Date(),
        updatedAt = Date(),
        lastSyncedAt = Date()
    )
}
