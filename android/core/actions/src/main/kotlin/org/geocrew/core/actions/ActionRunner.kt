package org.geocrew.core.actions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.team.TeamComments
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.formats.GeoJson
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.Geodesy
import org.geocrew.core.model.Geometry
import org.geocrew.core.model.LngLat
import org.geocrew.core.model.MapDisplay
import org.geocrew.core.model.Props
import org.geocrew.core.model.Sample
import org.geocrew.core.model.SampleTypes
import org.geocrew.core.model.audio
import org.geocrew.core.model.action.ActionCall
import org.geocrew.core.model.action.ActionCatalog
import org.geocrew.core.model.action.ActionResult
import org.geocrew.core.model.action.ActionSource
import org.geocrew.core.model.action.ActionSpec
import org.geocrew.core.model.samples
import org.geocrew.core.model.string
import org.geocrew.core.sensors.LocationSource
import org.geocrew.core.sensors.TrackingController
import org.geocrew.core.sensors.TrackingState
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs actions: the one place every change made by the assistant, a quick
 * command, a plugin or automation goes through. Each change is logged with
 * a snapshot of what it touched, so it can be undone later. The permission
 * check happens before this (see [org.geocrew.core.model.action.PermissionPolicy]).
 */
@Singleton
class ActionRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val features: FeatureRepository,
    private val projects: ProjectRepository,
    private val location: LocationSource,
    private val tracking: TrackingController,
    private val comments: TeamComments,
    private val session: TeamSession,
    private val host: ActionHost,
    val log: ActionLog,
    private val reference: org.geocrew.core.data.repo.ReferenceLayers,
) {
    val specs: List<ActionSpec> get() = ActionCatalog.all

    fun spec(name: String): ActionSpec? = ActionCatalog.byName(name)

    /** Records changed by the running call, captured before the change. */
    private class Changes {
        val before = LinkedHashMap<String, Snapshot>()
    }

    suspend fun run(call: ActionCall, source: ActionSource, requestId: String): ActionResult {
        val spec = spec(call.name) ?: return ActionResult.fail("没有这个操作：${call.name}")
        val changes = Changes()
        val result = try {
            execute(call, changes)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ActionResult.fail("${spec.title}失败：${e.message ?: e.javaClass.simpleName}")
        }
        if (result.ok && spec.risk != org.geocrew.core.model.action.ActionRisk.READ) {
            log.add(
                LogEntry(
                    id = UUID.randomUUID().toString(), requestId = requestId, action = spec.name, title = spec.title,
                    summary = result.summary, source = source, at = Instant.now(), before = changes.before.values.toList(),
                ),
            )
        }
        return result
    }

    // --- undo ------------------------------------------------------------------

    /** Puts records back as they were before [entry]. Returns a note when something could not be undone. */
    suspend fun undo(entry: LogEntry): String? {
        if (!entry.undoable) return "这一步不能撤销"
        var missing = 0
        for (s in entry.before.asReversed()) {
            val cur = features.get(s.id)
            if (cur == null) { missing++; continue }
            if (!s.existed) {
                if (!cur.deleted) features.delete(s.id)
                continue
            }
            val from = cur.copy(
                kind = s.kind?.let(::FeatureKind) ?: cur.kind,
                geometry = s.geometry?.let { GeoJson.decodeGeometry(it) },
                properties = s.properties,
            )
            features.restore(s.id, from)
            if (s.deleted) features.delete(s.id)
        }
        log.markUndone(setOf(entry.id))
        return if (missing > 0) "有 $missing 条记录已经不在这台手机上，没有撤销" else null
    }

    /** Undoes every step of one request, newest first. */
    suspend fun undoRequest(requestId: String): String? {
        log.ensureLoaded()
        val steps = log.entries.value.filter { it.requestId == requestId && it.undoable }
        if (steps.isEmpty()) return "没有可以撤销的操作"
        return steps.mapNotNull { undo(it) }.firstOrNull()
    }

    private suspend fun undoLast(): ActionResult {
        log.ensureLoaded()
        val last = log.entries.value.firstOrNull { it.undoable } ?: return ActionResult.fail("没有可以撤销的操作")
        val note = undo(last)
        return ActionResult(true, "已撤销：${last.summary}" + (note?.let { "（$it）" } ?: ""))
    }

    // --- state for the assistant ----------------------------------------------

    /** A few lines about where the user is, put in the assistant's prompt. */
    suspend fun stateLines(): List<String> {
        val p = projects.current.first()
        val open = host.openRecord.value?.let { features.get(it) }?.takeIf { !it.deleted }
        val t = tracking.state.value
        return buildList {
            add("工程：${p.name}" + (p.team?.let { "（工作组，角色 ${it.role}）" } ?: "（单机）"))
            add("页面：" + (host.page.value ?: "未知"))
            open?.let { add("打开的记录：${describe(it)}") }
            add("轨迹：" + if (t is TrackingState.Recording) "正在记录（${t.points} 点）" else "未在记录")
            add("定位权限：" + if (location.hasPermission()) "有" else "没有")
            add("时间：" + Instant.now().truncatedTo(ChronoUnit.MINUTES))
        }
    }

    private fun describe(f: GeoFeature): String = buildString {
        append("id=").append(f.id).append(" ")
        append(f.title ?: "未命名").append("（").append(f.kind.id).append("）")
        f.properties.string(Props.LITHOLOGY)?.let { append(" 岩性 ").append(it) }
        Attitude.fromProperties(f.properties)?.let { append(" 产状 ").append(it.format()) }
    }

    // --- execution --------------------------------------------------------------

    private suspend fun snapshot(c: Changes, f: GeoFeature) {
        if (f.id in c.before) return
        c.before[f.id] = Snapshot(
            f.id, existed = true, kind = f.kind.id, geometry = f.geometry?.let { GeoJson.encodeGeometry(it) },
            properties = f.properties, deleted = f.deleted,
        )
    }

    private suspend fun resolve(id: String?): GeoFeature? {
        if (id == null || id == ActionCatalog.CURRENT) {
            val p = projects.current.first()
            host.openRecord.value?.let { o -> features.get(o)?.takeIf { !it.deleted && it.projectId == p.id }?.let { return it } }
            return features.all(p.id).filter { !it.deleted }.maxByOrNull { it.createdAt }
        }
        return features.get(id)?.takeIf { !it.deleted }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun attitudeFrom(a: JsonObject): Attitude? {
        val dd = a.num("dip_direction") ?: return null
        val dip = a.num("dip") ?: return null
        return runCatching { Attitude.of(dd, dip) }.getOrNull()
    }

    private suspend fun here(): LngLat? {
        if (!location.hasPermission() || !location.isEnabled()) return null
        return withTimeoutOrNull(15_000) { location.fixes(intervalMillis = 1000).first { (it.accuracy ?: 0f) < 50f } }?.position
            ?: withTimeoutOrNull(5_000) { location.fixes(intervalMillis = 1000).first() }?.position
    }

    private suspend fun execute(call: ActionCall, c: Changes): ActionResult {
        val a = call.args
        return when (call.name) {
            ActionCatalog.FIND_RECORDS -> find(a)
            ActionCatalog.REFERENCE_HERE -> {
                val p = projects.current.first()
                if (p.config.referenceLayers.isEmpty()) {
                    return ActionResult.fail("这个工程还没有参考图层。在「导入」里选一个文件，点「作为参考图层」。")
                }
                val layers = p.config.referenceLayers.filter { it.visible }
                if (layers.isEmpty()) return ActionResult.fail("参考图层都关着，先在地图「图层」里打开要查的那个。")
                val lng = a.num("lng")
                val lat = a.num("lat")
                val at = (if (lng != null && lat != null) LngLat(lng, lat) else here())
                    ?: return ActionResult.fail("现在拿不到位置，请打开定位后再问。")
                val r = org.geocrew.core.model.ReferenceLookup.at(at, layers.map { it to reference.shapes(p.id, it) })
                ActionResult(
                    true, r.text(),
                    buildJsonObject {
                        put("areas", buildJsonArray {
                            r.areas.forEach { x ->
                                add(buildJsonObject {
                                    put("layer", x.layer); x.value?.let { put("value", it) }
                                    put("attributes", buildJsonObject { x.attributes.entries.take(20).forEach { (k, v) -> put(k, v.take(200)) } })
                                })
                            }
                        })
                        put("lines", buildJsonArray {
                            r.lines.forEach { n -> add(buildJsonObject { put("layer", n.layer); n.value?.let { put("value", it) }; put("meters", n.meters.toInt()) }) }
                        })
                    },
                )
            }
            ActionCatalog.TEAM_WHERE -> {
                if (!session.status.value.linked) return ActionResult.fail("这个工程还没加入工作组，看不到队友。")
                val online = session.status.value.online
                val mates = session.positions.value.values.mapNotNull { t ->
                    val at = runCatching { LngLat(t.lon, t.lat) }.getOrNull() ?: return@mapNotNull null
                    org.geocrew.core.model.TeamWhere.Mate(t.nickname, at, t.time, t.memberId in online)
                }
                val me = if (mates.isEmpty()) null else here()
                val rows = org.geocrew.core.model.TeamWhere.rows(me, mates, java.time.Instant.now())
                ActionResult(
                    true, org.geocrew.core.model.TeamWhere.text(rows, haveMe = me != null),
                    buildJsonObject {
                        put("teammates", buildJsonArray {
                            rows.forEach { r ->
                                add(buildJsonObject {
                                    put("name", r.name); put("lng", r.at.lng); put("lat", r.at.lat); put("online", r.online)
                                    r.distance?.let { put("meters", it.toInt()) }
                                    r.bearing?.let { put("bearing", it.toInt()) }
                                    r.ageSeconds?.let { put("age_seconds", it) }
                                })
                            }
                        })
                    },
                )
            }
            ActionCatalog.DAY_SUMMARY -> {
                val p = projects.current.first()
                val zone = java.time.ZoneId.systemDefault()
                val today = java.time.LocalDate.now(zone)
                val date = a.str("date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: today
                val s = org.geocrew.core.model.DaySummary.of(features.all(p.id), date, zone, p.config.recordRules)
                ActionResult(
                    true, s.text(today = date == today),
                    buildJsonObject {
                        put("date", date.toString()); put("geo_points", s.points); put("other_records", s.otherRecords)
                        put("samples", s.samples); put("photos", s.photos); put("track_m", s.trackMeters.toInt()); put("incomplete", s.incomplete)
                    },
                )
            }
            ActionCatalog.GET_RECORD -> {
                val f = resolve(a.str("id")) ?: return ActionResult.fail("找不到这条记录")
                ActionResult(true, "读取了 ${f.title ?: "记录"}", detail(f))
            }
            ActionCatalog.OPEN -> {
                val page = a.str("page") ?: ActionCatalog.PAGE_MAP
                val id = a.str("id")?.let { resolve(it)?.id }
                host.emit(UiEffect.Open(page, id))
                ActionResult(true, "已打开页面")
            }
            ActionCatalog.PLACE_POINT -> placePoint(a, c)
            ActionCatalog.UPDATE_RECORD -> {
                val f = resolve(a.str("id")) ?: return ActionResult.fail("找不到要改的记录")
                snapshot(c, f)
                val att = attitudeFrom(a)
                if ((a.num("dip") != null || a.num("dip_direction") != null) && att == null) return ActionResult.fail("产状要同时给倾向和倾角")
                val next = features.edit(f.id) {
                    a.str("title")?.takeIf { it.isNotEmpty() }?.let { this[Props.TITLE] = it }
                    a.str("lithology")?.let { this[Props.LITHOLOGY] = it.ifEmpty { null } }
                    att?.writeTo(this)
                    a.str("note")?.let { this[Props.NOTE] = it.ifEmpty { null } }
                    a.str("note_append")?.takeIf { it.isNotEmpty() }?.let { add ->
                        val cur = f.note.orEmpty().trimEnd()
                        this[Props.NOTE] = if (cur.isEmpty()) add else "$cur\n$add"
                    }
                } ?: return ActionResult.fail("找不到要改的记录")
                ActionResult(true, "已修改 ${next.title ?: "记录"}" + (att?.let { "，产状 ${it.format()}" } ?: ""), touched = listOf(f.id))
            }
            ActionCatalog.ADD_SAMPLE -> {
                val f = resolve(a.str("id")) ?: return ActionResult.fail("找不到要登记样品的记录")
                snapshot(c, f)
                val p = projects.current.first()
                val taken = features.all(p.id).flatMap { it.samples.map(Sample::no) }
                val no = a.str("no")?.takeIf { it.isNotEmpty() } ?: SampleTypes.nextNumber(f.title, taken)
                if (taken.any { it.equals(no, ignoreCase = true) }) return ActionResult.fail("样品号 $no 已经用过")
                features.edit(f.id) { samples(f.samples + Sample(no, type = a.str("kind"), takenAt = Instant.now())) }
                ActionResult(true, "已在 ${f.title ?: "记录"} 登记样品 $no", touched = listOf(f.id))
            }
            ActionCatalog.SET_ATTRIBUTE -> {
                val key = a.str("key") ?: return ActionResult.fail("没有给属性名")
                if (key in PROTECTED) return ActionResult.fail("「$key」只能在界面里修改")
                val value = a.str("value").orEmpty()
                val targets = a.list("ids").mapNotNull { resolve(it) }
                if (targets.isEmpty()) return ActionResult.fail("找不到要改的记录")
                for (f in targets) {
                    snapshot(c, f)
                    features.edit(f.id) { this[key] = value.ifEmpty { null } }
                }
                ActionResult(true, "已把 ${targets.size} 条记录的「$key」设为「$value」", touched = targets.map { it.id })
            }
            ActionCatalog.START_TRACK -> {
                if (!location.hasPermission()) return ActionResult.fail("需要先给同勘定位权限")
                if (tracking.state.value is TrackingState.Recording) return ActionResult(true, "轨迹已经在记录了")
                tracking.start(context)
                ActionResult(true, "开始记录轨迹")
            }
            ActionCatalog.STOP_TRACK -> {
                if (tracking.state.value !is TrackingState.Recording) return ActionResult(true, "现在没有在记录轨迹")
                tracking.stop(context)
                ActionResult(true, "轨迹已停止并保存")
            }
            ActionCatalog.TAKE_PHOTO -> {
                val f = resolve(a.str("id")) ?: return ActionResult.fail("先打开或新建一条记录，再拍照")
                host.emit(UiEffect.TakePhoto(f.id))
                ActionResult(true, "已打开相机，照片会加到 ${f.title ?: "记录"}", touched = listOf(f.id))
            }
            ActionCatalog.DELETE_RECORDS -> {
                val targets = a.list("ids").mapNotNull { resolve(it) }
                if (targets.isEmpty()) return ActionResult.fail("找不到要删除的记录")
                for (f in targets) { snapshot(c, f); features.delete(f.id) }
                ActionResult(true, "已删除 ${targets.size} 条记录（可在最近删除里恢复）", touched = targets.map { it.id })
            }
            ActionCatalog.SEND_TEAM_MESSAGE -> {
                val text = a.str("text")?.takeIf { it.isNotEmpty() } ?: return ActionResult.fail("消息是空的")
                val p = projects.current.first()
                if (p.team == null) return ActionResult.fail("当前工程没有加入工作组")
                if (!comments.post(p.id, text.take(2000), urgent = a.bool("urgent") == true)) return ActionResult.fail("没有发出去")
                runCatching { session.syncNow() }
                ActionResult(true, if (a.bool("urgent") == true) "已发紧急消息给全组" else "已发到组内消息")
            }
            ActionCatalog.UNDO_LAST -> undoLast()
            else -> ActionResult.fail("这个版本还不支持 ${call.name}")
        }
    }

    private suspend fun placePoint(a: JsonObject, c: Changes): ActionResult {
        val pos = here() ?: return ActionResult.fail("没有拿到定位，请确认已打开定位并给了权限")
        val p = projects.current.first()
        val all = features.all(p.id)
        val title = a.str("title")?.takeIf { it.isNotEmpty() } ?: p.config.recordRules.nextName(p.config.numbering, all.map { it.title })
        val att = attitudeFrom(a)
        val f = features.create(p.id, FeatureKind.GEO_POINT, Geometry.Point(pos)) {
            title?.let { this[Props.TITLE] = it }
            a.str("lithology")?.takeIf { it.isNotEmpty() }?.let { this[Props.LITHOLOGY] = it }
            att?.writeTo(this)
            a.str("note")?.takeIf { it.isNotEmpty() }?.let { this[Props.NOTE] = it }
        }
        c.before[f.id] = Snapshot(f.id, existed = false)
        host.openRecord.value = f.id
        return ActionResult(
            true,
            "已在当前位置打点 ${title ?: ""}".trim() + listOfNotNull(a.str("lithology")?.takeIf { it.isNotEmpty() }, att?.format()).joinToString("") { "，$it" },
            buildJsonObject { put("id", f.id); title?.let { put("title", it) } },
            touched = listOf(f.id),
        )
    }

    private suspend fun find(a: JsonObject): ActionResult {
        val p = projects.current.first()
        val q = a.str("query")?.takeIf { it.isNotEmpty() }
        val kinds = when (a.str("kind")) {
            "point" -> setOf(FeatureKind.POINT, FeatureKind.GEO_POINT, FeatureKind.PHOTO)
            "line" -> setOf(FeatureKind.LINE, FeatureKind.ROUTE, FeatureKind.TRACK, FeatureKind.SECTION)
            "polygon" -> setOf(FeatureKind.POLYGON)
            else -> null
        }
        val since = a.int("days")?.let { Instant.now().minus(it.toLong(), ChronoUnit.DAYS) }
        val all = features.all(p.id).filter { !it.deleted }
        val nearName = a.str("near")?.takeIf { it.isNotEmpty() }
        val center = nearName?.let { n ->
            all.firstOrNull { it.id == n } ?: all.filter { it.title?.trim().equals(n, ignoreCase = true) }.maxByOrNull { it.createdAt }
                ?: return ActionResult.fail("没有找到名为「$n」的记录")
        }
        val within = a.num("within_m") ?: if (center != null) 500.0 else null
        val me = when {
            center != null -> MapDisplay.anchor(center.geometry) ?: return ActionResult.fail("「${center.title ?: nearName}」没有位置，不能按距离查找")
            within != null -> here() ?: return ActionResult.fail("没有拿到定位，不能按距离查找")
            else -> null
        }
        val hits = all.asSequence()
            .filter { it.id != center?.id }
            .filter { kinds == null || it.kind in kinds }
            .filter { since == null || it.createdAt >= since }
            .filter { f ->
                q == null || listOfNotNull(f.title, f.properties.string(Props.LITHOLOGY), f.note).any { it.contains(q, ignoreCase = true) }
            }
            .map { f -> f to me?.let { m -> MapDisplay.anchor(f.geometry)?.let { Geodesy.distance(m, it) } } }
            .filter { (_, d) -> within == null || (d != null && d <= within) }
            .sortedWith(compareBy<Pair<GeoFeature, Double?>> { it.second ?: Double.MAX_VALUE }.thenByDescending { it.first.createdAt })
            .toList()
        val limit = (a.int("limit") ?: 20).coerceIn(1, 100)
        val data = buildJsonArray {
            hits.take(limit).forEach { (f, d) ->
                add(
                    buildJsonObject {
                        put("id", f.id)
                        f.title?.let { put("title", it) }
                        put("kind", f.kind.id)
                        f.properties.string(Props.LITHOLOGY)?.let { put("lithology", it) }
                        Attitude.fromProperties(f.properties)?.let { put("attitude", it.format()) }
                        d?.let { put("distance_m", Math.round(it)) }
                    },
                )
            }
        }
        val around = center?.let { "「${it.title ?: nearName}」${within?.toInt()} 米内" } ?: within?.let { "${it.toInt()} 米内" }
        val text = when {
            hits.isEmpty() -> around?.let { "${it}没有其他记录" } ?: "没有找到记录"
            around != null -> "${around}有 ${hits.size} 条记录" + hits.take(5).joinToString("、", "：") { (f, d) ->
                (f.title ?: unnamed(f.kind)) + (d?.let { "（${Math.round(it)} 米）" } ?: "")
            } + if (hits.size > 5) "等" else ""
            else -> "找到 ${hits.size} 条记录"
        }
        return ActionResult(true, text, buildJsonObject { put("total", hits.size); put("records", data) })
    }

    private fun unnamed(k: FeatureKind) = when (k) {
        FeatureKind.GEO_POINT -> "无名地质点"
        FeatureKind.TRACK -> "无名轨迹"
        FeatureKind.LINE -> "无名线"
        FeatureKind.POLYGON -> "无名面"
        else -> "无名记录"
    }

    private fun detail(f: GeoFeature): JsonObject = buildJsonObject {
        put("id", f.id)
        f.title?.let { put("title", it) }
        put("kind", f.kind.id)
        put("created", f.createdAt.toString())
        MapDisplay.anchor(f.geometry)?.let { put("lng", it.lng); put("lat", it.lat) }
        Attitude.fromProperties(f.properties)?.let { put("attitude", it.format()) }
        // Plain text values only, trimmed; this is data the user wrote, not instructions.
        val props = buildJsonObject {
            f.properties.forEach { (k, v) ->
                if (k in SKIP) return@forEach
                val s = (v as? JsonPrimitive)?.contentOrNull ?: return@forEach
                put(k, s.take(400))
            }
        }
        put("properties", props)
        put("samples", f.samples.size)
        put("photos", f.photos.size)
        put("audio", f.audio.size)
    }

    companion object {
        /** Keys an action may not set directly: review, tasks and references have their own rules. */
        val PROTECTED = setOf(Props.TITLE, "review", "assignee", "assignee_name", "task_status", Props.PHOTOS, Props.AUDIO, Props.SAMPLES)
        private val SKIP = setOf(Props.PHOTOS, Props.AUDIO, Props.SAMPLES, Props.STRIKE, Props.DIP, Props.DIP_DIRECTION)
    }
}
