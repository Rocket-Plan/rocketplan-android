# Image Processor Assembly Implementation Plan

## Overview

Based on the iOS implementation, we need to replicate the Image Processor Assembly system for Android. This system creates local assembly records that track photo uploads to the image processor backend, maintains upload state across app restarts, and provides UI feedback on upload progress.

## iOS Implementation Analysis

### Core Data Models (iOS)

In addition to the Core Data schema, iOS serializes a lightweight snapshot (`StoredUploadData`) for every queued assembly into `UserDefaults`. That blob keeps the processing URL, API key, template id, project id, group UUID, room id, user id, albums, order, and notes so the upload manager can exactly reconstruct pending assemblies after a crash or device reboot.

#### ImageProcessorAssembly
**Location**: `RocketPlan/Models/ImageProcessorAssembly+CoreDataProperties.swift`

**Properties**:
- `assemblyId: String` - Unique identifier (UUID without dashes)
- `roomId: Int32` - Associated room ID
- `projectId: Int32` - Associated project ID
- `groupUuid: String` - Group identifier for batching
- `status: String` - Current status (enum)
- `totalFiles: Int16` - Total number of photos in assembly
- `bytesReceived: Int64` - Total bytes received/uploaded
- `createdAt: Date?` - When assembly was created
- `lastUpdatedAt: Date?` - Last update timestamp
- `errorMessage: String?` - Error message if failed
- `failsCount: Int16` - Number of failure attempts
- `retryCount: Int16` - Number of retry attempts
- `nextRetry: Date?` - When to retry next
- `lastTimeout: Int32` - Last timeout value
- `isWaitingForConnectivity: Bool` - Network pause state
- `photos: NSSet?` - Relationship to ImageProcessorPhoto entities

**Status Values**:
- `queued` - Waiting in upload queue
- `pending` - Pending upload start
- `creating` - POST to API in progress
- `created` - Assembly created on server
- `uploading` - TUS uploads in progress
- `processing` - All files uploaded, server processing
- `completed` - Successfully processed
- `failed` - Failed at any stage
- `cancelled` - User cancelled
- `retrying` - Retry in progress
- `waitingForConnectivity` - Paused due to no network

#### ImageProcessorPhoto
**Location**: `RocketPlan/Models/ImageProcessorPhoto+CoreDataClass.swift`

**Properties**:
- `photoId: String` - Unique identifier (UUID)
- `fileName: String` - Original filename
- `localFilePath: String?` - Path to local file
- `status: String` - Upload status (enum)
- `orderIndex: Int16` - Order in assembly
- `fileSize: Int64` - File size in bytes
- `bytesUploaded: Int64` - Bytes uploaded so far
- `uploadTaskId: String?` - TUS upload task identifier
- `lastUpdatedAt: Date?` - Last update timestamp
- `errorMessage: String?` - Error message if failed
- `assembly: ImageProcessorAssembly` - Parent assembly relationship

**Status Values**:
- `pending` - Not yet uploaded
- `processing` - Being processed
- `uploading` - Upload in progress
- `completed` - Successfully uploaded
- `failed` - Upload failed
- `cancelled` - Upload cancelled

### API Request Model (iOS)

**ImageProcessorAssemblyRequest**:
```kotlin
{
  "assembly_id": String,          // UUID without dashes
  "total_files": Int,             // Number of photos
  "room_id": Int?,                // Optional room ID
  "project_id": Int,              // Project ID
  "group_uuid": String,           // Batch identifier
  "bytes_received": Int,          // Total bytes
  "photo_names": [String],        // List of filenames
  "albums": {String: [String]},   // Photo -> album mappings
  "ir_photos": [[String: IRPhoto]], // IR photo metadata
  "order": [String],              // Photo ordering
  "notes": {String: [String]},    // Photo -> notes mappings
  "entity_type": String?,         // Optional entity type
  "entity_id": Int?              // Optional entity ID
}
```

### API Endpoints

#### Create Assembly
- **Endpoint**: `POST /api/rooms/{roomId}/image-processor`
- **Alternative**: `POST /api/image-processor` (for entity uploads)
- **Purpose**: Create an assembly on the server and get processing URL
- **Response**:
  ```json
  {
    "success": true,
    "message": "string",
    "errors": {},
    "processing_url": "string"
  }
  ```

### Upload Flow (iOS)

1. **Assembly Creation**
   - Generate unique `assemblyId` (UUID without dashes) and immediately create `ImageProcessorAssembly` + `ImageProcessorPhoto` records (status `queued`).
   - Compute total bytes/photo names for downstream logging and payloads.
   - Persist the complete upload payload into `StoredUploadData` (UserDefaults) before network calls so cold-start recovery has all required context.
   - Log a lifecycle event (`image_processor_lifecycle`) that includes room/entity identifiers, byte totals, and timestamps.

2. **Server Registration**
   - Fetch/cached processing configuration via `ImageProcessingConfigurationService` to obtain processing URL/API key.
   - POST assembly metadata to `/api/rooms/{roomId}/image-processor` (room uploads) or `/api/image-processor` (entity uploads).
   - Update assembly status to `creating`; on success mark `created` and `markPhotosProcessing`. Failures mark `failed`, store the error, and schedule retries.

3. **File Upload (TUS Protocol)**
   - For each photo in assembly:
     - Start TUS upload to processing URL
     - Update photo status to `uploading`
     - Track `bytesUploaded` progress
     - On complete: update photo status to `completed`
     - On failure: update photo status to `failed`
   - When recovering uploads the manager deduplicates filenames, drops missing files, and patches backend `total_files` counts to stay consistent.

4. **Processing**
   - Once all photos uploaded: update assembly status to `processing`
   - Server sends Pusher notifications with processing updates
   - On completion: update assembly status to `completed`
   - On failure: update assembly status to `failed`

5. **Retry Logic**
   - Failed assemblies can be retried up to 13 times
   - Exponential backoff with `nextRetry` timestamp
   - Network disconnection triggers `waitingForConnectivity` state
   - Automatic resume when network restored
   - Missing local files trigger cleanup (remove from Core Data, remote log, PATCH backend totals).

### UI Integration (iOS)

#### AssemblyObserver
**Location**: `RocketPlan/ViewModels/AssemblyObserver.swift`

**Purpose**: Room-scoped observer that watches Core Data changes for assemblies

**Key Features**:
- **Room-Scoped Observation**: Each room cell has its own observer
- **Core Data Monitoring**: Watches both Assembly and Photo entity changes
- **Published State**: `@Published var isProcessing: Bool`
- **Progress Tracking**: `@Published var progress: (current: Int, total: Int, thumbnailPath: String?)?`
- **Network Pause Tracking**: Separate pause state from backend lifecycle

**Architecture**:
```swift
class AssemblyObserver: ObservableObject {
    @Published var isProcessing: Bool = false
    @Published var progress: (current: Int, total: Int, thumbnailPath: String?)? = nil
    @Published var hasFailed: Bool = false

    private let roomId: String
    private var pausedAssemblies: Set<String> = []

    init(roomId: String) {
        self.roomId = roomId
        setupTargetedObservation() // Watches Core Data changes
    }
}
```

**Static Registry for Cross-Component Communication**:
```swift
private static var observers: [String: WeakRef<AssemblyObserver>] = [:]

static func notifyAssemblyPaused(_ assemblyId: String, roomId: String, reason: String)
static func notifyAssemblyResumed(_ assemblyId: String, roomId: String)
```

#### Room Cell Display
- Shows spinner icon when `isProcessing = true`
- Shows progress "X/Y" when photos are uploading
- Shows pause icon when network disconnected
- Shows thumbnail of first completed photo

### Persistence Strategy (iOS)

#### Why Core Data?
- **Survives App Restarts**: Uploads continue across sessions
- **Background Support**: URLSession background uploads persist
- **Atomic Updates**: Transaction support for state changes
- **Query Performance**: Efficient filtering by room/project
- **Relationship Management**: Assembly ↔ Photos automatically managed

#### Cleanup Strategy
- Completed assemblies kept for 7 days
- Failed assemblies kept for retry attempts
- Photos deleted after assembly completion
- Temporary files cleaned up on success/failure
- Serialized `StoredUploadData` entries are removed once assemblies complete/cancel.
- Missing local files trigger a cleanup routine that removes them from Core Data, logs the event, and PATCHes backend `total_files`.

### Configuration & Telemetry

- `ImageProcessingConfigurationService` caches `/api/configuration/processing-service` with a version flag so stale data can be busted centrally.
- Remote logging categories (`image_processor_lifecycle`, `image_processor_state_machine`, `image_processor_file_cleanup`) track every meaningful transition with supporting metadata.
- NWPathMonitor-backed connectivity checks toggle `isWaitingForConnectivity` and prevent wasted retries when the backend is unreachable.

---

## Android Implementation Plan

### Phase 1: Database Schema & Entities

#### 1.1 Create Room Database Entities

**File**: `app/src/main/java/com/example/rocketplan_android/data/local/entity/ImageProcessorEntities.kt`

```kotlin
@Entity(
    tableName = "image_processor_assemblies",
    indices = [
        Index(value = ["assemblyId"], unique = true),
        Index(value = ["roomId"]),
        Index(value = ["projectId"]),
        Index(value = ["status"]),
        Index(value = ["groupUuid"])
    ]
)
data class ImageProcessorAssemblyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val assemblyId: String,              // UUID without dashes
    val roomId: Long?,                   // Nullable for entity uploads
    val projectId: Long,
    val groupUuid: String,               // Batch identifier
    val status: String,                  // AssemblyStatus enum value
    val totalFiles: Int,
    val bytesReceived: Long,
    val createdAt: Long,                 // Timestamp millis
    val lastUpdatedAt: Long,
    val errorMessage: String? = null,
    val failsCount: Int = 0,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    val lastTimeout: Int = 0,
    val isWaitingForConnectivity: Boolean = false
)

enum class AssemblyStatus(val value: String) {
    QUEUED("queued"),
    PENDING("pending"),
    CREATING("creating"),
    CREATED("created"),
    UPLOADING("uploading"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    RETRYING("retrying"),
    WAITING_FOR_CONNECTIVITY("waiting_for_connectivity");

    companion object {
        fun fromValue(value: String) = values().find { it.value == value }
    }
}

@Entity(
    tableName = "image_processor_photos",
    foreignKeys = [
        ForeignKey(
            entity = ImageProcessorAssemblyEntity::class,
            parentColumns = ["id"],
            childColumns = ["assemblyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["photoId"], unique = true),
        Index(value = ["assemblyId"]),
        Index(value = ["status"]),
        Index(value = ["uploadTaskId"])
    ]
)
data class ImageProcessorPhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val photoId: String,                 // UUID
    val assemblyId: Long,                // Foreign key
    val fileName: String,
    val localFilePath: String?,
    val status: String,                  // PhotoStatus enum value
    val orderIndex: Int,
    val fileSize: Long,
    val bytesUploaded: Long = 0,
    val uploadTaskId: String? = null,
    val lastUpdatedAt: Long,
    val errorMessage: String? = null
)

enum class PhotoStatus(val value: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    UPLOADING("uploading"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromValue(value: String) = values().find { it.value == value }
    }
}
```

#### 1.2 Create DAO

**File**: `app/src/main/java/com/example/rocketplan_android/data/local/dao/ImageProcessorDao.kt`

```kotlin
@Dao
interface ImageProcessorDao {

    // Assembly operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssembly(assembly: ImageProcessorAssemblyEntity): Long

    @Update
    suspend fun updateAssembly(assembly: ImageProcessorAssemblyEntity)

    @Query("SELECT * FROM image_processor_assemblies WHERE assemblyId = :assemblyId")
    suspend fun getAssembly(assemblyId: String): ImageProcessorAssemblyEntity?

    @Query("SELECT * FROM image_processor_assemblies WHERE roomId = :roomId ORDER BY createdAt DESC")
    fun observeAssembliesByRoom(roomId: Long): Flow<List<ImageProcessorAssemblyEntity>>

    @Query("SELECT * FROM image_processor_assemblies WHERE status IN (:statuses)")
    suspend fun getAssembliesByStatus(statuses: List<String>): List<ImageProcessorAssemblyEntity>

    @Query("SELECT * FROM image_processor_assemblies WHERE status = 'queued' OR status = 'pending' ORDER BY createdAt ASC")
    suspend fun getQueuedAssemblies(): List<ImageProcessorAssemblyEntity>

    @Query("SELECT * FROM image_processor_assemblies WHERE status = 'failed' AND nextRetryAt <= :currentTime")
    suspend fun getRetryableAssemblies(currentTime: Long): List<ImageProcessorAssemblyEntity>

    @Query("DELETE FROM image_processor_assemblies WHERE status = 'completed' AND lastUpdatedAt < :cutoffTime")
    suspend fun deleteOldCompletedAssemblies(cutoffTime: Long)

    // Photo operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: ImageProcessorPhotoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhotos(photos: List<ImageProcessorPhotoEntity>)

    @Update
    suspend fun updatePhoto(photo: ImageProcessorPhotoEntity)

    @Query("SELECT * FROM image_processor_photos WHERE assemblyId = :assemblyId ORDER BY orderIndex ASC")
    suspend fun getPhotosByAssembly(assemblyId: Long): List<ImageProcessorPhotoEntity>

    @Query("SELECT * FROM image_processor_photos WHERE assemblyId = :assemblyId ORDER BY orderIndex ASC")
    fun observePhotosByAssembly(assemblyId: Long): Flow<List<ImageProcessorPhotoEntity>>

    @Query("SELECT * FROM image_processor_photos WHERE uploadTaskId = :taskId")
    suspend fun getPhotoByUploadTaskId(taskId: String): ImageProcessorPhotoEntity?

    @Query("SELECT * FROM image_processor_photos WHERE assemblyId = :assemblyId AND fileName = :fileName")
    suspend fun getPhotoByFilename(assemblyId: Long, fileName: String): ImageProcessorPhotoEntity?

    @Query("SELECT * FROM image_processor_photos WHERE status = :status")
    suspend fun getPhotosByStatus(status: String): List<ImageProcessorPhotoEntity>
}
```

#### 1.3 Update OfflineDatabase

**File**: `app/src/main/java/com/example/rocketplan_android/data/local/OfflineDatabase.kt`

```kotlin
@Database(
    entities = [
        // ... existing entities ...
        ImageProcessorAssemblyEntity::class,
        ImageProcessorPhotoEntity::class
    ],
    version = X, // Increment version
    exportSchema = true
)
abstract class OfflineDatabase : RoomDatabase {
    // ... existing DAOs ...
    abstract fun imageProcessorDao(): ImageProcessorDao
}
```

### Phase 2: Data Models & API

#### 2.1 API Request/Response Models

**File**: `app/src/main/java/com/example/rocketplan_android/data/model/ImageProcessorModels.kt`

```kotlin
data class ImageProcessorAssemblyRequest(
    @SerializedName("assembly_id")
    val assemblyId: String,
    @SerializedName("total_files")
    val totalFiles: Int,
    @SerializedName("room_id")
    val roomId: Long?,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("group_uuid")
    val groupUuid: String,
    @SerializedName("bytes_received")
    val bytesReceived: Long,
    @SerializedName("photo_names")
    val photoNames: List<String>,
    @SerializedName("albums")
    val albums: Map<String, List<String>> = emptyMap(),
    @SerializedName("ir_photos")
    val irPhotos: List<Map<String, IRPhotoData>> = emptyList(),
    @SerializedName("order")
    val order: List<String> = emptyList(),
    @SerializedName("notes")
    val notes: Map<String, List<String>> = emptyMap(),
    @SerializedName("entity_type")
    val entityType: String? = null,
    @SerializedName("entity_id")
    val entityId: Long? = null
)

data class IRPhotoData(
    @SerializedName("thermal_file_name")
    val thermalFileName: String,
    @SerializedName("visual_file_name")
    val visualFileName: String
)

data class ImageProcessorAssemblyResponse(
    val success: Boolean,
    val message: String?,
    val errors: Map<String, List<String>>? = null,
    @SerializedName("processing_url")
    val processingUrl: String?
)

data class FileToUpload(
    val uri: Uri,
    val filename: String,
    val deleteOnCompletion: Boolean = false
)
```

#### 2.2 API Interface

**File**: `app/src/main/java/com/example/rocketplan_android/data/api/ImageProcessorApi.kt`

```kotlin
interface ImageProcessorApi {

    @POST("/api/rooms/{roomId}/image-processor")
    suspend fun createRoomAssembly(
        @Path("roomId") roomId: Long,
        @Body request: ImageProcessorAssemblyRequest
    ): Response<ImageProcessorAssemblyResponse>

    @POST("/api/image-processor")
    suspend fun createEntityAssembly(
        @Body request: ImageProcessorAssemblyRequest
    ): Response<ImageProcessorAssemblyResponse>
}
```

### Phase 3: Repository & Service Layer

#### 3.1 ImageProcessorRepository

**File**: `app/src/main/java/com/example/rocketplan_android/data/repository/ImageProcessorRepository.kt`

```kotlin
class ImageProcessorRepository(
    private val api: ImageProcessorApi,
    private val dao: ImageProcessorDao,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Creates a new assembly locally and registers it with the server
     */
    suspend fun createAssembly(
        roomId: Long?,
        projectId: Long,
        filesToUpload: List<FileToUpload>,
        groupUuid: String = UUID.randomUUID().toString(),
        albums: Map<String, List<String>> = emptyMap(),
        notes: Map<String, List<String>> = emptyMap()
    ): Result<String> = withContext(ioDispatcher) {
        try {
            // Generate unique assembly ID
            val assemblyId = UUID.randomUUID().toString().replace("-", "")
            val currentTime = System.currentTimeMillis()

            // Calculate total bytes
            val totalBytes = filesToUpload.sumOf { file ->
                getFileSize(file.uri)
            }

            // Create local assembly record
            val assemblyEntity = ImageProcessorAssemblyEntity(
                assemblyId = assemblyId,
                roomId = roomId,
                projectId = projectId,
                groupUuid = groupUuid,
                status = AssemblyStatus.QUEUED.value,
                totalFiles = filesToUpload.size,
                bytesReceived = totalBytes,
                createdAt = currentTime,
                lastUpdatedAt = currentTime
            )

            val assemblyDbId = dao.insertAssembly(assemblyEntity)
            Log.d("ImageProcessor", "Created local assembly: $assemblyId (dbId: $assemblyDbId)")

            // Create photo records
            val photoEntities = filesToUpload.mapIndexed { index, file ->
                ImageProcessorPhotoEntity(
                    photoId = UUID.randomUUID().toString(),
                    assemblyId = assemblyDbId,
                    fileName = file.filename,
                    localFilePath = file.uri.toString(),
                    status = PhotoStatus.PENDING.value,
                    orderIndex = index,
                    fileSize = getFileSize(file.uri),
                    lastUpdatedAt = currentTime
                )
            }

            dao.insertPhotos(photoEntities)
            Log.d("ImageProcessor", "Created ${photoEntities.size} photo records")

            // Update status to creating
            updateAssemblyStatus(assemblyId, AssemblyStatus.CREATING)

            // Register with server
            val request = ImageProcessorAssemblyRequest(
                assemblyId = assemblyId,
                totalFiles = filesToUpload.size,
                roomId = roomId,
                projectId = projectId,
                groupUuid = groupUuid,
                bytesReceived = totalBytes,
                photoNames = filesToUpload.map { it.filename },
                albums = albums,
                notes = notes
            )

            val response = if (roomId != null) {
                api.createRoomAssembly(roomId, request)
            } else {
                api.createEntityAssembly(request)
            }

            if (response.isSuccessful && response.body()?.success == true) {
                updateAssemblyStatus(assemblyId, AssemblyStatus.CREATED)
                Log.d("ImageProcessor", "Server assembly created successfully")
                Result.success(assemblyId)
            } else {
                val error = response.body()?.message ?: "Unknown error"
                updateAssemblyStatus(assemblyId, AssemblyStatus.FAILED, error)
                Log.e("ImageProcessor", "Server assembly creation failed: $error")
                Result.failure(Exception(error))
            }

        } catch (e: Exception) {
            Log.e("ImageProcessor", "Assembly creation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Updates assembly status
     */
    suspend fun updateAssemblyStatus(
        assemblyId: String,
        status: AssemblyStatus,
        errorMessage: String? = null
    ) = withContext(ioDispatcher) {
        val assembly = dao.getAssembly(assemblyId) ?: return@withContext
        val updated = assembly.copy(
            status = status.value,
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = errorMessage
        )
        dao.updateAssembly(updated)
        Log.d("ImageProcessor", "Assembly $assemblyId status: ${status.value}")
    }

    /**
     * Updates photo upload progress
     */
    suspend fun updatePhotoProgress(
        photoId: String,
        bytesUploaded: Long
    ) = withContext(ioDispatcher) {
        // Implementation for updating photo progress
    }

    /**
     * Gets assemblies for a specific room
     */
    fun observeRoomAssemblies(roomId: Long): Flow<List<ImageProcessorAssemblyEntity>> {
        return dao.observeAssembliesByRoom(roomId)
    }

    /**
     * Cleanup old completed assemblies
     */
    suspend fun cleanupOldAssemblies(daysOld: Int = 7) = withContext(ioDispatcher) {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysOld.toLong())
        dao.deleteOldCompletedAssemblies(cutoffTime)
    }

    private fun getFileSize(uri: Uri): Long {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        } ?: 0L
    }
}
```

#### 3.2 Persistent Upload Snapshot (`StoredUploadData`)

- Add a Kotlin serialization data class mirroring iOS:

```kotlin
@Serializable
data class StoredUploadData(
    val processingUrl: String,
    val apiKey: String?,
    val templateId: String,
    val projectId: Long,
    val roomId: Long?,
    val groupUuid: String,
    val userId: Long,
    val albums: Map<String, List<String>>,
    val order: List<String>,
    val notes: Map<String, List<String>>
)
```

- Persist each payload under `upload_data_<assemblyId>` inside encrypted `SharedPreferences` before any network mutation.
- Repository helpers:
  - `storeOriginalUploadData(assemblyId, data)` encodes JSON and writes it on the IO dispatcher.
  - `restoreOriginalUploadData(assemblyId)` returns the cached blob or `null`.
  - `reconstructUploadDataFromRoom(assemblyId)` deduplicates filenames and rebuilds `FileToUpload` entries when the cache is missing.
- During restoration, remove files that disappeared from disk, call a DAO helper to drop their records, and PATCH backend `total_files` so server/UI stay aligned.
- Delete the stored blob after completion/cancellation to avoid replaying stale uploads.

#### 3.3 Configuration & Logging Parity

- Reuse `ImageProcessingConfigurationRepository` to cache `/api/configuration/processing-service` (versioned) and expose synchronous getters for WorkManager.
- Introduce a lightweight telemetry helper that mirrors the iOS remote logging payloads (assembly lifecycle, status transitions, cleanup, errors). This can ride on the existing Retrofit logging endpoint or `Timber` until backend logging is available.
- Hook connectivity checks (`ConnectivityManager.NetworkCallback` + backend HEAD/health probes) into the repository so assemblies flip between `waiting_for_connectivity` and active states.

### Phase 4: UI Integration

#### 4.1 AssemblyObserver ViewModel

**File**: `app/src/main/java/com/example/rocketplan_android/ui/upload/AssemblyObserver.kt`

```kotlin
class AssemblyObserver(
    private val roomId: Long,
    private val repository: ImageProcessorRepository
) : ViewModel() {

    data class UploadProgress(
        val current: Int,
        val total: Int,
        val thumbnailPath: String?
    )

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _progress = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = _progress.asStateFlow()

    private val _hasFailed = MutableStateFlow(false)
    val hasFailed: StateFlow<Boolean> = _hasFailed.asStateFlow()

    init {
        observeAssemblies()
    }

    private fun observeAssemblies() {
        viewModelScope.launch {
            repository.observeRoomAssemblies(roomId)
                .collect { assemblies ->
                    updateUIState(assemblies)
                }
        }
    }

    private suspend fun updateUIState(assemblies: List<ImageProcessorAssemblyEntity>) {
        val activeAssemblies = assemblies.filter { assembly ->
            AssemblyStatus.fromValue(assembly.status) in listOf(
                AssemblyStatus.QUEUED,
                AssemblyStatus.PENDING,
                AssemblyStatus.CREATING,
                AssemblyStatus.CREATED,
                AssemblyStatus.UPLOADING,
                AssemblyStatus.PROCESSING
            )
        }

        _isProcessing.value = activeAssemblies.isNotEmpty()

        // Calculate progress from first active assembly
        val firstAssembly = activeAssemblies.firstOrNull()
        if (firstAssembly != null) {
            // Get photo progress
            // val photos = repository.getPhotosByAssembly(firstAssembly.id)
            // val completed = photos.count { it.status == PhotoStatus.COMPLETED.value }
            // _progress.value = UploadProgress(
            //     current = completed,
            //     total = firstAssembly.totalFiles,
            //     thumbnailPath = photos.firstOrNull()?.localFilePath
            // )
        } else {
            _progress.value = null
        }

        // Check for failures
        val failedAssemblies = assemblies.filter {
            AssemblyStatus.fromValue(it.status) == AssemblyStatus.FAILED
        }
        _hasFailed.value = failedAssemblies.isNotEmpty()
    }
}
```

#### 4.2 Room Cell Integration

**Update existing room cells to show upload status**:
```kotlin
// In RoomCardAdapter or similar
@Composable
fun RoomCard(
    room: RoomEntity,
    assemblyObserver: AssemblyObserver
) {
    val isProcessing by assemblyObserver.isProcessing.collectAsState()
    val progress by assemblyObserver.progress.collectAsState()

    // Show upload indicator when processing
    if (isProcessing) {
        CircularProgressIndicator()
        progress?.let { (current, total, _) ->
            Text("$current/$total")
        }
    }
}
```

### Phase 5: Background Upload Service

#### 5.1 Upload Worker

**File**: `app/src/main/java/com/example/rocketplan_android/worker/ImageProcessorUploadWorker.kt`

```kotlin
class ImageProcessorUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 1. Restore upload metadata (StoredUploadData or DAO snapshot).
        // 2. Ensure the assembly is registered server-side (recreate if missing).
        // 3. Stream files via TUS/OkHttp, updating ImageProcessorPhoto rows on progress.
        // 4. Mark assemblies/photos completed, failed, or waiting for connectivity.
        // 5. Reschedule itself when more queued assemblies remain.
        return processQueuedAssemblies()
    }
}
```

- Workers should run as foreground services (notification with progress) when uploads exceed a threshold, mirroring iOS background URLSession UX.
- Implement exponential backoff tied to `failsCount`/`nextRetryAt` plus a hard cap equivalent to iOS’s 13 retries.
- Pause uploads when the backend health check fails, set `waiting_for_connectivity`, and register a one-off worker once connectivity resumes.
- Emit local broadcasts/Flows so UI (room observers) can render state changes in near real time.

### Recovery & Resilience Requirements

- **Cold-start recovery**: On app or worker restart, attempt to load `StoredUploadData`. If missing, rebuild from Room snapshots, deduplicate filenames, and drop missing files.
- **Missing file remediation**: Remove missing photo records, log the cleanup, and PATCH backend totals to prevent mismatch errors.
- **Connectivity-aware queue**: When network/health checks fail, mark assemblies as `waiting_for_connectivity` and pause retries until connectivity is restored.
- **Telemetry parity**: Every major state change (queue, status transition, cleanup, retry, completion) should fire a remote logging event with room/project/assembly identifiers for parity with iOS dashboards.

### Phase 6: Testing Strategy

1. **Unit Tests**
   - DAO operations
   - Status transitions
   - Progress calculations

2. **Integration Tests**
   - Assembly creation flow
   - API communication
   - Database persistence

3. **UI Tests**
   - Upload indicator display
   - Progress updates
   - Error handling

### Phase 7: Migration Strategy

1. **Database Migration**
   - Add new tables with version bump
   - No data migration needed (new feature)

2. **Rollout**
   - Feature flag controlled
   - Gradual rollout to users
   - Monitor for issues

---

## Key Differences from iOS

### Persistence
- **iOS**: Core Data with NSManagedObject
- **Android**: Room Database with Entity classes

### Observation
- **iOS**: NSNotificationCenter + Core Data observers
- **Android**: Flow + Room reactive queries

### Background Processing
- **iOS**: URLSession background support + NWPathMonitor heartbeats and background tasks
- **Android**: WorkManager + ForegroundService notifications + ConnectivityManager callbacks

### UI Updates
- **iOS**: Combine + @Published properties
- **Android**: StateFlow + Jetpack Compose

---

## Next Steps

1. **Immediate**: Implement Phase 1 (Database Schema)
2. **Week 1**: Implement Phases 2-3 (API, Repository, StoredUploadData cache, config/logging plumbing)
3. **Week 2**: Implement Phase 4 (UI Integration + observers) while finishing connectivity hooks
4. **Week 3**: Implement Phase 5 (Background Upload + recovery/resume logic)
5. **Week 4**: Testing & Polish (unit/UI tests, telemetry verification, cleanup routines)

---

## References

- iOS Implementation: `/Users/kilka/GitHub/ios.rocketplantech.com/RocketPlan/`
- iOS Documentation: `/Users/kilka/GitHub/ios.rocketplantech.com/docs/assemblyobserver.md`
- Android Room Documentation: https://developer.android.com/training/data-storage/room
- Android WorkManager: https://developer.android.com/topic/libraries/architecture/workmanager
- Kotlin Serialization: https://developer.android.com/kotlin/serialization
