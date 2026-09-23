package com.foldforge.studio.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.foldforge.studio.FoldForgeApp
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.ZipTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs up every project (excluding snapshots/journals, which are reproducible) into a single ZIP in
 * app storage. Runs in WorkManager so large workspaces don't block the UI.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as FoldForgeApp).container
        val workspace = container.workspaceDir
        val dir = backupDir(applicationContext)
        val name = "FoldForge-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"
        val out = File(dir, name)
        try {
            out.outputStream().use { os ->
                ZipTools.zipDirectory(workspace, os) { rel ->
                    rel.contains("/${ProjectFileSystem.META_DIR}/snapshots") || rel.contains("/${ProjectFileSystem.META_DIR}/journal")
                }
            }
            (dir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }.drop(5).forEach { it.delete() }
            Result.success(workDataOf("path" to out.absolutePath, "size" to out.length()))
        } catch (e: Exception) {
            out.delete()
            Result.failure(workDataOf("error" to (e.message ?: "backup failed")))
        }
    }

    companion object {
        const val WORK_NAME = "foldforge-backup"
        fun backupDir(context: Context) = File(context.filesDir, "outputs/backups").also { it.mkdirs() }
        fun enqueue(context: Context) = WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>().build())

        /** Restores projects from a backup ZIP into the workspace; existing projects are kept (new folders get suffixes). */
        fun restore(context: Context, backup: java.io.InputStream): Int {
            val container = (context.applicationContext as FoldForgeApp).container
            val staging = File(context.cacheDir, "restore-${System.nanoTime()}")
            try {
                ZipTools.extract(backup, staging, stripSingleRoot = false)
                var count = 0
                (staging.listFiles() ?: emptyArray()).filter { File(it, "${ProjectFileSystem.META_DIR}/project.json").isFile }.forEach { dir ->
                    val target = container.projectStore.newProjectDir(dir.name)
                    target.delete()
                    if (!dir.renameTo(target)) dir.copyRecursively(target)
                    count++
                }
                container.projects.refresh()
                return count
            } finally {
                staging.deleteRecursively()
            }
        }
    }
}
