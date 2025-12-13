package com.example.rocketplan_android.ui.rocketdry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.ui.projects.addroom.RoomTypeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import kotlin.math.max

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

sealed class EquipmentRoomUiState {
    object Loading : EquipmentRoomUiState()
    data class Ready(
        val projectAddress: String,
        val roomName: String,
        val roomIconRes: Int,
        val equipment: List<RoomEquipmentItem>,
        val typeOptions: List<EquipmentTypeMeta>
    ) : EquipmentRoomUiState()
}

data class RoomEquipmentItem(
    val equipmentId: Long?,
    val uuid: String,
    val typeKey: String,
    val typeLabel: String,
    val quantity: Int,
    val startDate: Date?,
    val endDate: Date?,
    val iconRes: Int,
    val status: String,
    val dayCount: Int
)

class EquipmentRoomViewModel(
    application: Application,
    private val projectId: Long,
    private val roomId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository

    private val _uiState = MutableStateFlow<EquipmentRoomUiState>(EquipmentRoomUiState.Loading)
    val uiState: StateFlow<EquipmentRoomUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeRooms(projectId),
                localDataService.observeEquipmentForRoom(roomId)
            ) { projects, rooms, equipment ->
                val project = projects.firstOrNull { it.projectId == projectId }
                val room = rooms.firstOrNull { it.roomId == roomId }
                resolveState(project, room, equipment)
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun addEquipment(typeKey: String, quantity: Int, startDate: Date?, endDate: Date?) {
        viewModelScope.launch(Dispatchers.IO) {
            val meta = EquipmentTypeMapper.metaFor(typeKey)
            val (start, end) = ensureDateOrder(startDate, endDate)
            offlineSyncRepository.upsertEquipmentOffline(
                projectId = projectId,
                roomId = roomId,
                type = meta.label,
                quantity = quantity.coerceAtLeast(1),
                status = "active",
                startDate = start,
                endDate = end
            )
        }
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
        offlineSyncRepository.upsertEquipmentOffline(
            projectId = projectId,
            roomId = roomId,
            type = meta.label,
            quantity = quantity,
            status = item.status,
            startDate = start,
            endDate = end,
            equipmentId = item.equipmentId,
            uuid = item.uuid
        )
    }

    private fun resolveState(
        project: OfflineProjectEntity?,
        room: OfflineRoomEntity?,
        equipment: List<OfflineEquipmentEntity>
    ): EquipmentRoomUiState {
        if (project == null || room == null) return EquipmentRoomUiState.Loading
        val items = equipment
            .map { it.toUiItem() }
            .sortedBy { it.typeLabel.lowercase(Locale.getDefault()) }

        return EquipmentRoomUiState.Ready(
            projectAddress = buildProjectAddress(project),
            roomName = room.title,
            roomIconRes = resolveRoomIcon(room),
            equipment = items,
            typeOptions = EquipmentTypeMapper.allOptions()
        )
    }

    private fun ensureDateOrder(startDate: Date?, endDate: Date?): Pair<Date?, Date?> {
        if (startDate == null || endDate == null) return startDate to endDate
        return if (startDate.after(endDate)) startDate to startDate else startDate to endDate
    }

    private fun OfflineEquipmentEntity.toUiItem(): RoomEquipmentItem {
        val meta = EquipmentTypeMapper.metaFor(type)
        return RoomEquipmentItem(
            equipmentId = equipmentId.takeIf { it > 0 },
            uuid = uuid,
            typeKey = meta.key,
            typeLabel = meta.label,
            quantity = quantity,
            startDate = startDate,
            endDate = endDate,
            iconRes = meta.iconRes,
            status = status,
            dayCount = calculateDays(startDate, endDate)
        )
    }

    private fun calculateDays(startDate: Date?, endDate: Date?): Int {
        if (startDate == null || endDate == null) return 0
        val start = startDate.time / MILLIS_PER_DAY
        val end = endDate.time / MILLIS_PER_DAY
        return max(1, (end - start + 1).toInt())
    }

    private fun buildProjectAddress(project: OfflineProjectEntity): String {
        val address = project.addressLine1?.takeIf { it.isNotBlank() }
        val title = project.title.takeIf { it.isNotBlank() }
        val alias = project.alias?.takeIf { it.isNotBlank() }
        return listOfNotNull(address, title, alias).firstOrNull()
            ?: "Project ${project.projectId}"
    }

    private fun resolveRoomIcon(room: OfflineRoomEntity): Int {
        val iconName = room.roomType ?: room.title
        return RoomTypeCatalog.resolveIconRes(
            rocketPlanApp,
            room.roomTypeId,
            iconName
        )
    }

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long,
            roomId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(EquipmentRoomViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return EquipmentRoomViewModel(application, projectId, roomId) as T
            }
        }
    }
}
