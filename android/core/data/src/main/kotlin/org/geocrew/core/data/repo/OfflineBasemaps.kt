package org.geocrew.core.data.repo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.geocrew.core.formats.MbTiles
import org.geocrew.core.model.tiles.TileSource
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class BasemapImport(val source: TileSource, val tiles: Int, val skipped: Int)

/**
 * Offline base maps: an MBTiles file the user picks is unpacked into
 * `files/basemaps/<id>/{z}/{x}/{y}.ext` and registered as a custom tile
 * source with a `file://` URL. Deleting the source deletes the folder.
 */
@Singleton
class OfflineBasemaps @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val root: File get() = File(context.filesDir, "basemaps").apply { mkdirs() }

    /**
     * Imports [uri]. [progress] gets (done, total) tile counts. Fails with a
     * Chinese message for vector packages, empty files and too little space.
     */
    suspend fun import(uri: Uri, fallbackName: String, progress: (Int, Int) -> Unit = { _, _ -> }): BasemapImport =
        withContext(Dispatchers.IO) {
            // SQLite needs a real file, so the picked document is copied first.
            val copy = File(context.cacheDir, "import-${UUID.randomUUID()}.mbtiles")
            val id = "offline-" + UUID.randomUUID().toString().take(8)
            val dir = File(root, id)
            try {
                val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                // Room for the copy plus the unpacked tiles (about the same size again).
                if (size > 0 && StatFs(context.filesDir.path).availableBytes < size * 2 + SPARE) {
                    throw IOException("手机存储空间不足：需要约 ${size * 2 / MB} MB")
                }
                (context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")).use { input ->
                    copy.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
                val db = try {
                    SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY)
                } catch (e: Exception) {
                    throw IOException("不是有效的 MBTiles 文件")
                }
                db.use { unpack(it, id, dir, fallbackName, progress) }
            } catch (e: Throwable) {
                dir.deleteRecursively()
                throw e
            } finally {
                copy.delete()
            }
        }

    private suspend fun unpack(db: SQLiteDatabase, id: String, dir: File, fallbackName: String, progress: (Int, Int) -> Unit): BasemapImport {
        val rows = mutableMapOf<String, String>()
        try {
            db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                while (c.moveToNext()) if (!c.isNull(0) && !c.isNull(1)) rows[c.getString(0).lowercase()] = c.getString(1)
            }
        } catch (e: Exception) {
            // The metadata table is required by the spec but some tools skip it.
        }
        val meta = MbTiles.parseMeta(rows, fallbackName)
        MbTiles.problem(meta)?.let { throw IOException(it) }
        val total = try {
            db.rawQuery("SELECT COUNT(*) FROM tiles", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (e: Exception) {
            throw IOException("文件里没有瓦片表，不是有效的 MBTiles 文件")
        }
        if (total == 0) throw IOException("文件里没有瓦片")

        var ext = meta.format
        var written = 0
        var skipped = 0
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        db.rawQuery("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles", null).use { c ->
            while (c.moveToNext()) {
                val z = c.getInt(0)
                val x = c.getInt(1)
                val y = MbTiles.xyzRow(z, c.getInt(2))
                val data = c.getBlob(3)
                if (data == null || data.isEmpty() || !MbTiles.valid(z, x, y)) {
                    skipped++
                    continue
                }
                if (ext == null) {
                    ext = MbTiles.sniff(data) ?: throw IOException("认不出瓦片格式")
                    if (ext == "pbf") throw IOException("这是矢量瓦片包，目前只支持栅格瓦片（png、jpg、webp）")
                }
                val f = File(dir, MbTiles.relativePath(z, x, y, ext!!))
                f.parentFile?.mkdirs()
                f.writeBytes(data)
                written++
                minZ = minOf(minZ, z)
                maxZ = maxOf(maxZ, z)
                if (written % 200 == 0) {
                    coroutineContext.ensureActive()
                    progress(written + skipped, total)
                }
            }
        }
        if (written == 0) throw IOException("文件里没有可用的瓦片")
        progress(total, total)
        // Trust the tiles over the metadata for the zoom range.
        val fixed = if (rows.containsKey("minzoom") && rows.containsKey("maxzoom")) meta else meta.copy(minZoom = minZ, maxZoom = maxZ)
        val source = MbTiles.source(id, fixed, ext!!, Uri.fromFile(dir).toString())
        settings.saveCustomSource(source)
        return BasemapImport(source, written, skipped)
    }

    /** Removes an offline source and its tiles. */
    suspend fun delete(source: TileSource) = withContext(Dispatchers.IO) {
        settings.deleteCustomSource(source.id)
        if (source.offline) folder(source.id)?.deleteRecursively()
    }

    /** Disk use of an offline source, in bytes. */
    suspend fun size(source: TileSource): Long = withContext(Dispatchers.IO) {
        folder(source.id)?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    // Ids come from settings; only plain names inside the root are accepted.
    private fun folder(id: String): File? =
        id.takeIf { Regex("^offline-[A-Za-z0-9-]{1,40}$").matches(it) }?.let { File(root, it) }

    private companion object {
        const val MB = 1024L * 1024
        const val SPARE = 50 * MB
    }
}
