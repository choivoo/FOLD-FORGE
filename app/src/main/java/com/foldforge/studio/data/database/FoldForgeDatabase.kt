package com.foldforge.studio.data.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val path: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    val pinned: Boolean = false,
)

/** Single-row workspace session used for crash recovery / "Restore Session". */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: Int = 1,
    val projectId: String?,
    val openTabs: String, // newline separated paths
    val activeFile: String?,
    val cursors: String, // JSON map path -> offset
    val layoutPreset: String,
    val updatedAt: Long,
    val cleanExit: Boolean,
)

@Entity(tableName = "snapshots", indices = [Index("projectId")])
data class SnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val projectId: String,
    val label: String,
    val reason: String,
    val createdAt: Long,
    val fileCount: Int,
)

@Entity(tableName = "ai_history", indices = [Index("projectId")])
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val role: String,
    val text: String,
    val model: String?,
    val createdAt: Long,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

@Entity(tableName = "qa_runs", indices = [Index("projectId")])
data class QaRunEntity(
    @PrimaryKey val runId: String,
    val projectId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val pass: Int,
    val fail: Int,
    val warn: Int,
    val overall: String,
    val reportPath: String,
)

@Entity(tableName = "build_history", indices = [Index("projectId")])
data class BuildHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val kind: String,
    val status: String,
    val outputPath: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val createdAt: Long,
    val message: String,
)

@Entity(tableName = "git_cache")
data class GitCacheEntity(
    @PrimaryKey val projectId: String,
    val branch: String?,
    val state: String,
    val ahead: Int,
    val behind: Int,
    val changes: Int,
    val remoteUrl: String?,
    val updatedAt: Long,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY pinned DESC, lastOpenedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE projects SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: String, time: Long)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE id = 1")
    suspend fun get(): SessionEntity?

    @Upsert
    suspend fun save(session: SessionEntity)

    @Query("UPDATE sessions SET cleanExit = :clean WHERE id = 1")
    suspend fun markClean(clean: Boolean)

    @Query("DELETE FROM sessions")
    suspend fun clear()
}

@Dao
interface SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SnapshotEntity)

    @Query("SELECT * FROM snapshots WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observe(projectId: String): Flow<List<SnapshotEntity>>
}

@Dao
interface AiHistoryDao {
    @Insert
    suspend fun insert(m: AiMessageEntity): Long

    @Query("SELECT * FROM ai_history WHERE projectId = :projectId ORDER BY createdAt ASC, id ASC")
    suspend fun forProject(projectId: String): List<AiMessageEntity>

    @Query("DELETE FROM ai_history WHERE projectId = :projectId")
    suspend fun clear(projectId: String)
}

@Dao
interface QaRunDao {
    @Upsert
    suspend fun upsert(run: QaRunEntity)

    @Query("SELECT * FROM qa_runs WHERE projectId = :projectId ORDER BY startedAt DESC LIMIT 50")
    fun observe(projectId: String): Flow<List<QaRunEntity>>
}

@Dao
interface BuildHistoryDao {
    @Insert
    suspend fun insert(b: BuildHistoryEntity): Long

    @Query("SELECT * FROM build_history WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 50")
    fun observe(projectId: String): Flow<List<BuildHistoryEntity>>
}

@Dao
interface GitCacheDao {
    @Upsert
    suspend fun upsert(g: GitCacheEntity)

    @Query("SELECT * FROM git_cache")
    fun observeAll(): Flow<List<GitCacheEntity>>
}

@Database(
    entities = [
        ProjectEntity::class, SessionEntity::class, SnapshotEntity::class, AiMessageEntity::class,
        QaRunEntity::class, BuildHistoryEntity::class, GitCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FoldForgeDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun sessions(): SessionDao
    abstract fun snapshots(): SnapshotDao
    abstract fun aiHistory(): AiHistoryDao
    abstract fun qaRuns(): QaRunDao
    abstract fun builds(): BuildHistoryDao
    abstract fun gitCache(): GitCacheDao

    companion object {
        fun create(context: Context): FoldForgeDatabase =
            Room.databaseBuilder(context, FoldForgeDatabase::class.java, "foldforge.db").build()
    }
}
