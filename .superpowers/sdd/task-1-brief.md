# Task 1: Room Database Setup

**Files:**
- Create: `app/src/main/java/io/github/jiangood/xq/data/AnalysisRecord.kt`
- Create: `app/src/main/java/io/github/jiangood/xq/data/AnalysisRecordDao.kt`
- Create: `app/src/main/java/io/github/jiangood/xq/data/AppDatabase.kt`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/src/main/java/io/github/jiangood/xq/App.kt`

## Steps

### Step 1: Add Room version and dependencies to libs.versions.toml

Add to the `[versions]` section:
```toml
room = "2.6.1"
```

Add to the `[libraries]` section:
```toml
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
```

Add to the `[plugins]` section:
```toml
kotlin-kapt = { id = "org.jetbrains.kotlin.kapt", version.ref = "kotlin" }
```

### Step 2: Apply KAPT plugin and add Room deps in app/build.gradle.kts

In the `plugins` block, add:
```kotlin
alias(libs.plugins.kotlin.kapt)
```

In the `dependencies` block, add:
```kotlin
implementation(libs.room.runtime)
implementation(libs.room.ktx)
kapt(libs.room.compiler)
```

### Step 3: Create AnalysisRecord entity

File: `app/src/main/java/io/github/jiangood/xq/data/AnalysisRecord.kt`

```kotlin
package io.github.jiangood.xq.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "analysis_records")
data class AnalysisRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val status: String,
    val move: String? = null,
    val fen: String? = null,
    val pieceCount: Int? = null,
    val elapsedMs: Long? = null,
    val errorMessage: String? = null,
    val screenshotPath: String? = null,
    val visualizationPath: String? = null,
    val logs: String = ""
)
```

### Step 4: Create AnalysisRecordDao

File: `app/src/main/java/io/github/jiangood/xq/data/AnalysisRecordDao.kt`

```kotlin
package io.github.jiangood.xq.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisRecordDao {
    @Query("SELECT * FROM analysis_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<AnalysisRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AnalysisRecord): Long

    @Query("SELECT COUNT(*) FROM analysis_records")
    suspend fun count(): Int

    @Query("DELETE FROM analysis_records WHERE id IN (SELECT id FROM analysis_records ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("DELETE FROM analysis_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}
```

### Step 5: Create AppDatabase

File: `app/src/main/java/io/github/jiangood/xq/data/AppDatabase.kt`

```kotlin
package io.github.jiangood.xq.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AnalysisRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun analysisRecordDao(): AnalysisRecordDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "analysis_history.db"
                ).build().also { instance = it }
            }
        }
    }
}
```

### Step 6: Initialize DB in App.kt

Add `AppDatabase.getInstance(this)` to `App.onCreate()`.

### Step 7: Verify Build

Run: `.\gradlew :app:compileDebugSources` → must succeed

## Context

This is the first task of the Analysis History feature. It creates the data layer. Later tasks will add log session capture, DB writes, ViewModel observation, and UI.

The existing `app/build.gradle.kts` already has the `kotlin-android` plugin and compose deps. The `gradle/libs.versions.toml` already has `[versions]`, `[libraries]`, and `[plugins]` sections. Follow the exact existing format.

## Report

Write report to `D:\ws\xiangqi\.superpowers\sdd\task-1-report.md` containing:
- Status: DONE / NEEDS_CONTEXT / BLOCKED
- List of commits made
- Test results summary (command run + output)
- Any concerns
