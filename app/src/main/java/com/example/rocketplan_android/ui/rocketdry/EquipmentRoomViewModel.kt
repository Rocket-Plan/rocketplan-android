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
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.ui.projects.addroom.RoomTypeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

sealed class EquipmentRoomUiState {
    object Loading : EquipmentRoomUiState()
    data class Ready(
        val projectAddress: String,
        val roomName: String,
        val roomIconRes: Int,
        val equipment: List<RoomEquipmentItem>,
        val typeOptions: List<EquipmentTypeMeta>,
        val otherRooms: List<RoomMeta>,
        val moveTransferEnabled: Boolean
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

data class RoomMeta(
    val roomId: Long,
    val serverId: Long?,
    val uuid: String,
    val name: String,
    val iconRes: Int
)

class EquipmentRoomViewModel(
    application: Application,
    private val projectId: Long,
    private val roomId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val secureStorage = rocketPlanApp.secureStorage

    private val _uiState = MutableStateFlow<EquipmentRoomUiState>(EquipmentRoomUiState.Loading)
    val uiState: StateFlow<EquipmentRoomUiState> = _uiState

    private var currentCompanyId: Long? = null
    private val _moveTransferEnabled = MutableStateFlow(false)
    private var moveTransferEnabledInitialized = false

    init {
        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeRooms(projectId),
                localDataService.observeEquipmentForRoom(roomId)
            ) { projects, rooms, equipment ->
                Triple(projects, rooms, equipment)
            }.collect { (projects, rooms, equipment) ->
                val project = projects.firstOrNull { it.projectId == projectId }
                currentCompanyId = project?.companyId
                val room = rooms.firstOrNull { it.roomId == roomId }
                if (!moveTransferEnabledInitialized) {
                    _moveTransferEnabled.value = withContext(Dispatchers.IO) {
                        secureStorage.getEquipmentMoveTransferEnabledSync()
                    }
                    moveTransferEnabledInitialized = true
                }
                val enabled = _moveTransferEnabled.value
                _uiState.value = resolveState(project, room, equipment, rooms, enabled)
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

    fun moveEquipment(item: RoomEquipmentItem, toRoomId: Long, toRoomUuid: String, quantity: Int?, note: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val equipment = localDataService.getEquipmentByUuid(item.uuid) ?: return@launch
            val idempotencyKey = UUID.randomUUID().toString()
            offlineSyncRepository.syncQueueEnqueuer.enqueueEquipmentMove(
                equipment = equipment,
                toRoomId = toRoomId,
                toRoomUuid = toRoomUuid,
                quantity = quantity,
                note = note,
                idempotencyKey = idempotencyKey,
                lockUpdatedAt = equipment.serverUpdatedAt?.toApiTimestamp()
            )
        }
    }

    fun transferEquipment(item: RoomEquipmentItem, toRoomId: Long, toRoomUuid: String, toProjectId: Long, quantity: Int, note: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val equipment = localDataService.getEquipmentByUuid(item.uuid) ?: return@launch
            val idempotencyKey = UUID.randomUUID().toString()
            offlineSyncRepository.syncQueueEnqueuer.enqueueEquipmentTransfer(
                equipment = equipment,
                toRoomId = toRoomId,
                toRoomUuid = toRoomUuid,
                toProjectId = toProjectId,
                quantity = quantity,
                note = note,
                idempotencyKey = idempotencyKey,
                lockUpdatedAt = equipment.serverUpdatedAt?.toApiTimestamp()
            )
        }
    }

    suspend fun getAllProjectsForTransfer(): List<OfflineProjectEntity> = withContext(Dispatchers.IO) {
        localDataService.getAllProjects().filter { it.serverId != null && it.serverId > 0 }
    }

    suspend fun getRoomsForProject(projectServerId: Long): List<OfflineRoomEntity> = withContext(Dispatchers.IO) {
        val companyId = currentCompanyId ?: return@withContext emptyList()
        val project = localDataService.getProjectByServerId(projectServerId, companyId) ?: return@withContext emptyList()
        localDataService.getRoomsByProject(project.projectId).filter { it.serverId != null && it.serverId > 0 }
    }

    suspend fun getEquipmentForUuid(uuid: String): OfflineEquipmentEntity? = withContext(Dispatchers.IO) {
        localDataService.getEquipmentByUuid(uuid)
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
        equipment: List<OfflineEquipmentEntity>,
        rooms: List<OfflineRoomEntity>,
        moveTransferEnabled: Boolean
    ): EquipmentRoomUiState {
        if (project == null || room == null) return EquipmentRoomUiState.Loading
        val items = equipment
            .map { it.toUiItem() }
            .sortedBy { it.typeLabel.lowercase(Locale.getDefault()) }

        val otherRooms = rooms
            .filter { it.roomId != roomId }
            .map { it.toMeta() }

        return EquipmentRoomUiState.Ready(
            projectAddress = buildProjectAddress(project),
            roomName = room.title,
            roomIconRes = resolveRoomIcon(room),
            equipment = items,
            typeOptions = EquipmentTypeMapper.allOptions(),
            otherRooms = otherRooms,
            moveTransferEnabled = moveTransferEnabled
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

    private fun OfflineRoomEntity.toMeta(): RoomMeta {
        return RoomMeta(
            roomId = roomId,
            serverId = serverId,
            uuid = uuid,
            name = title,
            iconRes = resolveRoomIcon(this)
        )
    }

    suspend fun loadMovementHistory(pivotId: Long): Result<List<MovementHistoryItem>> = withContext(Dispatchers.IO) {
        offlineSyncRepository.getEquipmentRoomMovements(pivotId).map { movements ->
            movements.map { dto ->
                MovementHistoryItem(
                    id = dto.id,
                    uuid = dto.uuid,
                    fromRoomId = dto.fromRoomId,
                    toRoomId = dto.toRoomId,
                    fromLocationId = dto.fromLocationId,
                    toLocationId = dto.toLocationId,
                    quantity = dto.quantity,
                    movedAt = dto.movedAt,
                    movedByUserId = dto.movedByUserId,
                    note = dto.note,
                    idempotencyKey = dto.idempotencyKey
                )
            }
        }
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

data class MovementHistoryItem(
    val id: Long,
    val uuid: String?,
    val fromRoomId: Long?,
    val toRoomId: Long?,
    val fromLocationId: Long?,
    val toLocationId: Long?,
    val quantity: Int?,
    val movedAt: String?,
    val movedByUserId: Long?,
    val note: String?,
    val idempotencyKey: String?
)
