package org.geocrew.core.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.geocrew.core.formats.GeoJson
import org.geocrew.core.formats.ImportContext
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.ReferenceLayer
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores reference layers: each one's shapes as a GeoJSON file under
 * filesDir/reference/<project>/<layer>.geojson, listed in the project's
 * [org.geocrew.core.model.ProjectConfig.referenceLayers]. Nothing here syncs.
 */
@Singleton
class ReferenceLayers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projects: ProjectRepository,
) {
    private val cache = ConcurrentHashMap<String, List<GeoFeature>>()

    private fun dir(projectId: String) = File(File(context.filesDir, "reference"), projectId.filter { it.isLetterOrDigit() || it == '-' })

    private fun file(projectId: String, layerId: String) = File(dir(projectId), layerId.filter { it.isLetterOrDigit() || it == '-' } + ".geojson")

    /** Saves [shapes] as a new layer of [projectId] and lists it, drawn on top of other reference layers. */
    suspend fun add(projectId: String, name: String, shapes: List<GeoFeature>): ReferenceLayer {
        require(shapes.isNotEmpty()) { "没有可以显示的图形" }
        val layer = ReferenceLayer(
            id = UUID.randomUUID().toString(),
            name = name.take(60),
            colorBy = ReferenceLayer.guessColorBy(shapes),
            count = shapes.size,
        )
        withContext(Dispatchers.IO) {
            val f = file(projectId, layer.id)
            f.parentFile?.mkdirs()
            f.writeText(GeoJson.encodeCollection(shapes.filter { it.geometry != null }).toString())
        }
        cache[layer.id] = shapes
        projects.updateConfig(projectId) { it.copy(referenceLayers = it.referenceLayers + layer) }
        return layer
    }

    /** The shapes of [layer], read once and then kept in memory. */
    suspend fun shapes(projectId: String, layer: ReferenceLayer): List<GeoFeature> = cache[layer.id] ?: withContext(Dispatchers.IO) {
        val f = file(projectId, layer.id)
        if (!f.isFile) return@withContext emptyList()
        runCatching { GeoJson.decode(f.readText(), ImportContext(projectId)).features }.getOrDefault(emptyList())
    }.also { cache[layer.id] = it }

    suspend fun update(projectId: String, layer: ReferenceLayer) =
        projects.updateConfig(projectId) { c -> c.copy(referenceLayers = c.referenceLayers.map { if (it.id == layer.id) layer.copy(opacity = layer.opacity.coerceIn(0.1f, 1f)) else it }) }

    suspend fun remove(projectId: String, layerId: String) {
        projects.updateConfig(projectId) { c -> c.copy(referenceLayers = c.referenceLayers.filter { it.id != layerId }) }
        cache.remove(layerId)
        withContext(Dispatchers.IO) { file(projectId, layerId).delete() }
    }

    /** The stored GeoJSON of [layerId], if it is on this phone. */
    fun stored(projectId: String, layerId: String): File? = file(projectId, layerId).takeIf { it.isFile }

    /** SHA-256 (hex) of the stored GeoJSON of [layerId]; the attachment hash when it is shared. */
    suspend fun sha256(projectId: String, layerId: String): String? = withContext(Dispatchers.IO) {
        val f = stored(projectId, layerId) ?: return@withContext null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Keeps a layer received from the workspace: [src] becomes its stored GeoJSON. */
    suspend fun receive(projectId: String, layerId: String, src: File) = withContext(Dispatchers.IO) {
        val f = file(projectId, layerId)
        f.parentFile?.mkdirs()
        src.copyTo(f, overwrite = true)
        cache.remove(layerId)
    }

    /** Deletes the stored file of a layer the workspace stopped sharing. */
    suspend fun drop(projectId: String, layerId: String) = withContext(Dispatchers.IO) {
        cache.remove(layerId)
        file(projectId, layerId).delete()
    }

    /** Removes every layer file of a deleted project. */
    suspend fun removeAll(projectId: String) = withContext(Dispatchers.IO) { dir(projectId).deleteRecursively() }
}
