package org.geocrew.core.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.geocrew.core.data.db.TrackPointDao
import org.geocrew.core.data.db.TrackPointEntity
import org.geocrew.core.formats.ImportContext
import org.geocrew.core.formats.PackageManifest
import org.geocrew.core.formats.ProjectPackage
import org.geocrew.core.model.AttachmentKind
import org.geocrew.core.model.Project
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

data class PackageExport(val features: Int, val photos: Int, val audio: Int = 0)
data class PackageImport(val project: Project, val features: Int, val photos: Int, val warnings: List<String>, val audio: Int = 0)

/** Exports a project to, and imports one from, a [ProjectPackage] zip. */
@Singleton
class ProjectPackages @Inject constructor(
    private val projects: ProjectRepository,
    private val features: FeatureRepository,
    private val tracks: TrackRepository,
    private val trackPoints: TrackPointDao,
    private val photos: PhotoStore,
    private val overlayImages: OverlayImages,
    private val defaults: BuildDefaults,
    private val clock: Clock,
) {
    suspend fun export(projectId: String, output: OutputStream): PackageExport = withContext(Dispatchers.IO) {
        val p = projects.get(projectId) ?: throw IllegalStateException("工程不存在")
        val list = features.all(projectId)
        val points = list.associate { it.id to tracks.points(it.id) }
        val manifest = PackageManifest(
            name = p.name,
            createdAt = p.createdAt.toString(),
            exportedAt = clock.instant().toString(),
            // The view travels with the project; the link to a workspace never does.
            config = p.config.copy(teamFormVersion = 0, teamFormPending = false, teamCommentSeq = 0, chatReadSeq = 0, pushEnabled = false),
            generator = defaults.userAgent,
        )
        val n = ProjectPackage.write(output, manifest, list, { points[it].orEmpty() }, overlay = overlayImages::file) { ref -> photos.file(ref.sha, ref.kind) }
        PackageExport(list.size, n[AttachmentKind.PHOTO] ?: 0, n[AttachmentKind.AUDIO] ?: 0)
    }

    /** Imports a package as a new project (never merging into an existing one) and returns it. */
    suspend fun import(input: InputStream): PackageImport = withContext(Dispatchers.IO) {
        val target = java.util.UUID.randomUUID().toString()
        val stored = mutableMapOf<AttachmentKind, Int>()
        val warnings = mutableListOf<String>()
        val overlayFiles = mutableSetOf<String>()
        val c = ProjectPackage.read(input, ImportContext(target, clock.instant()), overlay = { name, data ->
            if (overlayImages.putBlocking(name, data)) overlayFiles += name
        }) { ref, data ->
            val what = if (ref.kind == AttachmentKind.AUDIO) "录音" else "照片"
            val actual = photos.putBlocking(data, ref.kind)
            if (actual == null) warnings += "$what ${ref.sha.take(8)} 是空文件"
            else if (actual != ref.sha) warnings += "$what ${ref.sha.take(8)} 内容与文件名不符，已跳过"
            else stored.merge(ref.kind, 1, Int::plus)
        }
        val existing = projects.all().map { it.name }.toSet()
        val name = c.manifest.name.trim().ifEmpty { "导入的工程" }.let { if (it in existing) "$it（导入）" else it }
        // Overlays whose picture did not come along are left out rather than shown blank.
        val (overlays, lost) = c.manifest.config.overlays.partition { it.file in overlayFiles }
        if (lost.isNotEmpty()) warnings += "${lost.size} 张叠加图片缺少图片文件，未导入"
        val project = projects.create(name, id = target, config = c.manifest.config.copy(overlays = overlays))
        features.importAll(c.features)
        for ((trackId, pts) in c.tracks) {
            trackPoints.insertAll(
                pts.mapIndexed { i, p ->
                    TrackPointEntity(trackId, i, p.position.lng, p.position.lat, p.position.alt, p.time.toEpochMilli(), p.accuracy, p.speed, p.bearing)
                },
            )
        }
        PackageImport(project, c.features.size, stored[AttachmentKind.PHOTO] ?: 0, c.warnings + warnings, stored[AttachmentKind.AUDIO] ?: 0)
    }
}
