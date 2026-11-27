package org.geocrew.storage

import java.time.Instant
import org.geocrew.core.edit.FieldType
import org.geocrew.core.sync.FieldDef
import org.geocrew.core.sync.LayerSpec
import org.geocrew.core.sync.PushResult
import org.geocrew.core.sync.SyncClient

/**
 * A project and the team workspace it syncs with (protocol v1 8.4, ADR
 * 0008), through [client], signed in as [account]: sharing the project, then
 * sending what it has to send.
 */
internal class ProjectSync(private val project: Project, private val client: SyncClient, private val account: SyncAccount) {
    /** A layer the workspace cannot take: one whose features are of mixed kinds (backlog: GEOMETRY layers). */
    class Unsupported(val layer: String) : Exception("layer $layer mixes points, lines and areas")

    /** What a push did: changes the server has, changes it refused (all so far), and conflicts it recorded. */
    data class Pushed(val sent: Int, val rejected: Int, val conflicts: Int)

    /**
     * Shares the project with the workspace (protocol v1 5.6): makes each layer
     * there with the project's ids, links the project, and queues every feature
     * as a new row. It can run again after it was cut short: layers already
     * there and features already queued stay as they are.
     */
    fun share() {
        val specs = specs(project)
        for (spec in specs) client.createLayer(account.workspace, account.token, spec)
        project.link(account.workspace)
        project.data { ds ->
            ds.begin()
            try {
                for (layer in project.layers) Outbox.enqueueNew(ds, layer.name, layer.id, project.target(layer.name).store.features())
                ds.commit()
            } catch (e: Exception) {
                runCatching { ds.rollback() }
                throw e
            }
        }
    }

    /** Sends what the project has to send, at most [batch] changes a request, until nothing is left or the network fails. */
    fun push(batch: Int): Pushed {
        project.data(Sending::prepare)
        var sent = 0
        var conflicts = 0
        while (true) {
            val changes = project.data { Sending.next(it, batch.coerceAtLeast(1)) }
            if (changes.isEmpty()) break
            val (results, _) = client.push(account.workspace, account.token, Instant.now(), changes)
            project.data { Sending.done(it, results) }
            sent += results.count { it.status != PushResult.REJECTED }
            conflicts += results.sumOf { it.conflicts.size }
            // A server that answers for none of them would be asked again and again.
            if (results.none { r -> changes.any { it.id == r.id } }) break
        }
        return Pushed(sent, project.data(Sending::counts).second, conflicts)
    }

    companion object {
        /** Each layer of [project] as the workspace will have it; [Unsupported] for one it cannot take, before anything is made. */
        fun specs(project: Project): List<LayerSpec> = project.layers.map { spec(project, it) }

        // A layer as the workspace has it: its kind of geometry, CRS and fields, by the ids the project keeps (protocol v1 7.1, 7.2).
        private fun spec(project: Project, layer: Project.Layer): LayerSpec {
            val geometry = when (layer.geometry?.uppercase()?.removeSuffix(" Z")?.removeSuffix(" M")?.removeSuffix(" ZM")?.trimEnd('Z', 'M')) {
                null, "", "NONE" -> "None"
                "POINT" -> "Point"
                "LINESTRING" -> "LineString"
                "POLYGON" -> "Polygon"
                "MULTIPOINT" -> "MultiPoint"
                "MULTILINESTRING" -> "MultiLineString"
                "MULTIPOLYGON" -> "MultiPolygon"
                else -> throw Unsupported(layer.name)
            }
            val ids = project.fieldIds(layer.name)
            val fields = project.target(layer.name).store.fields.mapNotNull { f ->
                val id = ids[f.name] ?: return@mapNotNull null
                val type = when (f.type) {
                    FieldType.INTEGER, FieldType.INTEGER64 -> "integer"
                    FieldType.REAL -> "real"
                    FieldType.STRING -> "string"
                    FieldType.DATE -> "date"
                    FieldType.DATETIME -> "datetime"
                    FieldType.BOOLEAN -> "boolean"
                    FieldType.OTHER -> "json"
                }
                FieldDef(id, f.name, type)
            }
            return LayerSpec(layer.id, layer.name, geometry, layer.srs ?: Crs.LONGITUDE_LATITUDE, fields)
        }
    }
}
