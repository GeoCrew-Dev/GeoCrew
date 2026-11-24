package org.geocrew.core.data.team

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Member tokens, one file per project, kept apart from the database in the
 * app's no-backup directory: they never leave the device through backups and
 * are never shown in the UI or written to logs.
 */
@Singleton
class TeamCredentials @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.noBackupFilesDir, "team-tokens")

    suspend fun token(projectId: String): String? = withContext(Dispatchers.IO) {
        file(projectId).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
    }

    suspend fun save(projectId: String, token: String) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "$projectId.tmp")
        tmp.writeText(token)
        tmp.renameTo(file(projectId))
        Unit
    }

    suspend fun clear(projectId: String) = withContext(Dispatchers.IO) {
        file(projectId).delete()
        Unit
    }

    private fun file(projectId: String) = File(dir, projectId.filter { it.isLetterOrDigit() || it == '-' })
}
