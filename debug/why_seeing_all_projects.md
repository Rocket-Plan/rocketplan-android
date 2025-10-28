# Why You're Seeing All Projects (Not Just Your Company)

## Summary
You're seeing projects from **3 different companies** because you're logged in as **user 832** (Jer B / jeremie@rocketplantech.com), and the app syncs projects using `/api/users/{userId}/projects` which returns **ALL projects the user has access to**, regardless of company.

## The Projects You're Seeing

### In Local Database (9 projects):
```
Company 470 "Another Test Company" (8 projects):
- 4909, 4926, 4929, 4941, 4958, 4962, 4964, 4968

Company 7 "Team Fortify" (1 project):
- 4970 (201 Faker Road)
```

### In QA Database - User 832 Has Access To (13 projects):
```
Company 470 "Another Test Company": 10 projects
Company 473 "Ggez": 2 projects  
Company 7 "Team Fortify": 1 project
```

## How Project Syncing Works

### Code Flow (SyncQueueManager.kt:163-164):
```kotlin
userId?.let { syncRepository.syncUserProjects(it) }       // Line 163
companyId?.let { syncRepository.syncCompanyProjects(it) } // Line 164
```

The sync calls **BOTH** endpoints:
1. `/api/users/832/projects` - Returns all projects user 832 has access to
2. `/api/companies/{companyId}/projects` - Returns projects for a specific company

### Why You See Projects From Multiple Companies:

**User-based sync (`syncUserProjects`):**
- Endpoint: `/api/users/832/projects`
- Returns: **ALL projects where user 832 is a member** (via `project_user` table)
- This crosses company boundaries!
- User 832 has access to projects in companies 470, 473, and 7

**Company-based sync (`syncCompanyProjects`):**
- Endpoint: `/api/companies/{companyId}/projects`  
- Returns: **Only projects belonging to that specific company**
- Limited to single company scope

## Why This Happens

In the `project_user` table, user 832 has been granted access to:
- 10 projects in company 470
- 2 projects in company 473
- 1 project in company 7

When you sync, the user endpoint returns ALL of these, regardless of company boundaries.

## The Local Database Reality

Your local database shows:
```sql
SELECT companyId, COUNT(*) 
FROM offline_projects 
WHERE isDeleted = 0 
GROUP BY companyId;

companyId  |  project_count
-----------|---------------
NULL       |  9
```

**All projects have `companyId = NULL`!**

This is because:
1. The `ProjectDto` likely doesn't include `company_id` field from the API
2. Or the mapping in `toEntity()` doesn't populate it
3. So even though projects belong to different companies in QA, locally they all show NULL

## What Changed?

You mentioned "i made some change where i now see all of the projects, not just the project in my company."

**Likely what happened:**
1. **Before:** You were only calling `syncCompanyProjects(companyId)` 
2. **After:** Now calling `syncUserProjects(userId)` as well (or switched to it)

The current code calls **BOTH** (lines 163-164 in SyncQueueManager.kt), so you get:
- All user projects (crosses companies)
- Plus all company projects (if companyId is set)

## To Fix This (If Needed)

### Option 1: Only Sync Company Projects
Remove line 163, keep only line 164:
```kotlin
// userId?.let { syncRepository.syncUserProjects(it) }  // Remove this
companyId?.let { syncRepository.syncCompanyProjects(it) }  // Keep this
```

### Option 2: Filter Projects by Current Company
After syncing, filter out projects that don't belong to the user's primary company:
```kotlin
val projects = localDataService.getAllProjects()
val userCompanyId = authRepository.getStoredCompanyId()
val filteredProjects = projects.filter { it.companyId == userCompanyId }
```

### Option 3: Store Company ID in Projects
Update the API to include `company_id` in the response, and update the DTO/mapping:
```kotlin
data class ProjectDto(
    val id: Long,
    val uid: String,
    val companyId: Long?,  // Add this
    // ... other fields
)

fun ProjectDto.toEntity() = OfflineProjectEntity(
    serverId = id,
    companyId = companyId,  // Map this
    // ... other fields
)
```

## Current Behavior is CORRECT if:

You **want** users to see all projects they have access to, regardless of company. This makes sense if:
- Users collaborate across multiple companies
- Users are consultants/contractors working with multiple clients
- The app is multi-tenant and users can switch between companies

## Verification

To confirm which endpoint is being called, add logging:
```kotlin
// In SyncQueueManager.kt
userId?.let { 
    Log.d("Sync", "🔍 Syncing projects for USER $it")
    syncRepository.syncUserProjects(it) 
}
companyId?.let { 
    Log.d("Sync", "🔍 Syncing projects for COMPANY $it")
    syncRepository.syncCompanyProjects(it) 
}
```

Check the logs to see:
- Which userId is being used (should be 832)
- Which companyId is being used (if any)
- Whether both or just one endpoint is called

