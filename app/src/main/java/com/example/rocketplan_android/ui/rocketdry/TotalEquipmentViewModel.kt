package com.example.rocketplan_android.ui.rocketdry

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.ui.projects.addroom.RoomTypeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Room with its equipment items for the breakdown section.
 */
data class RoomEquipmentBreakdown(
    val roomId: Long,
    val roomName: String,
    val roomIconRes: Int,
    val equipment: List<RoomEquipmentItem>
)

sealed class TotalEquipmentUiState {
    object Loading : TotalEquipmentUiState()
    data class Ready(
        val projectAddress: String,
        val equipmentByType: List<EquipmentTypeSummary>,
        val roomBreakdowns: List<RoomEquipmentBreakdown>
    ) : TotalEquipmentUiState()
}

class TotalEquipmentViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TotalEquipmentVM"

        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TotalEquipmentViewModel(application, projectId) as T
            }
        }
    }

    private val rocketPlanApp: RocketPlanApplication = application as RocketPlanApplication
    private val localDataService: LocalDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository: OfflineSyncRepository = rocketPlanApp.offlineSyncRepository

    private val _uiState = MutableStateFlow<TotalEquipmentUiState>(TotalEquipmentUiState.Loading)
    val uiState: StateFlow<TotalEquipmentUiState> = _uiState.asStateFlow()

    init {
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeEquipmentForProject(projectId),
                localDataService.observeRooms(projectId)
            ) { projects: List<OfflineProjectEntity>, equipment: List<OfflineEquipmentEntity>, rooms: List<OfflineRoomEntity> ->
                val project = projects.firstOrNull { it.projectId == projectId }
                Triple(project, equipment, rooms)
            }.collect { triple ->
                val project = triple.first
                val equipment = triple.second
                val rooms = triple.third

                val address = project?.let { buildProjectAddress(it) } ?: "Project $projectId"
                val activeEquipment = equipment.filter { !it.isDeleted }

                // Build equipment by type summaries
                val equipmentByType = buildEquipmentTypeSummaries(activeEquipment)

                // Build room breakdowns
                val roomBreakdowns = buildRoomBreakdowns(activeEquipment, rooms)

                _uiState.value = TotalEquipmentUiState.Ready(
                    projectAddress = address,
                    equipmentByType = equipmentByType,
                    roomBreakdowns = roomBreakdowns
                )
            }
        }
    }

    private fun buildProjectAddress(project: OfflineProjectEntity): String {
        val address = project.addressLine1?.takeIf { it.isNotBlank() }
        val title = project.title.takeIf { it.isNotBlank() }
        val alias = project.alias?.takeIf { it.isNotBlank() }
        return address ?: alias ?: title ?: "Unknown Project"
    }

    private fun buildEquipmentTypeSummaries(equipment: List<OfflineEquipmentEntity>): List<EquipmentTypeSummary> {
        return equipment
            .groupBy { it.type }
            .map { (type, items) ->
                val meta = EquipmentTypeMapper.metaFor(type)
                EquipmentTypeSummary(
                    type = type,
                    label = meta.label,
                    count = items.sumOf { it.quantity },
                    iconRes = meta.iconRes
                )
            }
            .sortedByDescending { it.count }
    }

    private fun buildRoomBreakdowns(
        equipment: List<OfflineEquipmentEntity>,
        rooms: List<OfflineRoomEntity>
    ): List<RoomEquipmentBreakdown> {
        val roomsById = rooms.associateBy { it.roomId }
        val equipmentByRoom = equipment.groupBy { it.roomId }

        return equipmentByRoom
            .filter { it.key != null }
            .mapNotNull { (roomId, roomEquipment) ->
                val room = roomsById[roomId] ?: return@mapNotNull null
                if (room.isDeleted) return@mapNotNull null

                val equipmentItems = roomEquipment.map { eq ->
                    val meta = EquipmentTypeMapper.metaFor(eq.type)
                    RoomEquipmentItem(
                        equipmentId = eq.equipmentId,
                        uuid = eq.uuid,
                        typeKey = eq.type,
                        typeLabel = meta.label,
                        quantity = eq.quantity,
                        startDate = eq.startDate,
                        endDate = eq.endDate,
                        iconRes = meta.iconRes,
                        status = eq.status ?: "active",
                        dayCount = calculateDayCount(eq.startDate, eq.endDate)
                    )
                }

                val iconName = room.roomType?.takeIf { it.isNotBlank() } ?: room.title
                val roomIconRes = RoomTypeCatalog.resolveIconRes(rocketPlanApp, room.roomTypeId, iconName)

                RoomEquipmentBreakdown(
                    roomId = room.roomId,
                    roomName = room.title,
                    roomIconRes = roomIconRes,
                    equipment = equipmentItems
                )
            }
            .sortedBy { it.roomName }
    }

    private fun calculateDayCount(startDate: Date?, endDate: Date?): Int {
        if (startDate == null) return 0
        val end = endDate ?: Date()
        val diffMs = end.time - startDate.time
        return TimeUnit.MILLISECONDS.toDays(diffMs).toInt().coerceAtLeast(1)
    }

    fun changeQuantity(item: RoomEquipmentItem, delta: Int) {
        val newQuantity = (item.quantity + delta).coerceAtLeast(1)
        if (newQuantity == item.quantity) return
        viewModelScope.launch(Dispatchers.IO) {
            persistUpdate(item, quantity = newQuantity)
        }
    }

    fun updateStartDate(item: RoomEquipmentItem, newStartDate: Date) {
        viewModelScope.launch(Dispatchers.IO) {
            val (_, end) = ensureDateOrder(newStartDate, item.endDate)
            persistUpdate(item, startDate = newStartDate, endDate = end)
        }
    }

    fun updateEndDate(item: RoomEquipmentItem, newEndDate: Date) {
        viewModelScope.launch(Dispatchers.IO) {
            val (start, end) = ensureDateOrder(item.startDate, newEndDate)
            persistUpdate(item, startDate = start, endDate = end)
        }
    }

    fun deleteEquipment(item: RoomEquipmentItem) {
        viewModelScope.launch(Dispatchers.IO) {
            offlineSyncRepository.deleteEquipmentOffline(
                equipmentId = item.equipmentId,
                uuid = item.uuid
            )
            Log.d(TAG, "Deleted equipment ${item.equipmentId}")
        }
    }

    private suspend fun persistUpdate(
        item: RoomEquipmentItem,
        quantity: Int = item.quantity,
        startDate: Date? = item.startDate,
        endDate: Date? = item.endDate
    ) {
        val meta = EquipmentTypeMapper.metaFor(item.typeKey)
        val (start, end) = ensureDateOrder(startDate, endDate)
        // Note: roomId is null here since we don't change room assignment from total view
        // We need to get the roomId from the equipment entity first
        val equipmentEntity = localDataService.getEquipmentByUuid(item.uuid)
        offlineSyncRepository.upsertEquipmentOffline(
            projectId = projectId,
            roomId = equipmentEntity?.roomId ?: 0L,
            type = meta.label,
            quantity = quantity,
            status = item.status,
            startDate = start,
            endDate = end,
            equipmentId = item.equipmentId,
            uuid = item.uuid
        )
        Log.d(TAG, "Updated equipment ${item.equipmentId}: qty=$quantity")
    }

    private fun ensureDateOrder(start: Date?, end: Date?): Pair<Date?, Date?> {
        if (start == null || end == null) return start to end
        return if (end.before(start)) end to start else start to end
    }
}
