package org.geocrew.feature.record

import org.geocrew.core.data.repo.PhotoExif
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.geocrew.core.data.repo.FeatureRepository
import org.geocrew.core.data.repo.PhotoStore
import org.geocrew.core.data.repo.ProjectRepository
import org.geocrew.core.data.repo.SettingsRepository
import org.geocrew.core.data.team.CommentItem
import org.geocrew.core.data.team.TeamComments
import org.geocrew.core.data.team.TeamRepository
import org.geocrew.core.data.team.TeamSession
import org.geocrew.core.model.review
import org.geocrew.core.model.Attitude
import org.geocrew.core.model.AudioInfo
import org.geocrew.core.model.audio
import org.geocrew.core.model.audioInfo
import org.geocrew.core.model.CoordinateFormat
import org.geocrew.core.model.FeatureKind
import org.geocrew.core.model.FieldTemplate
import org.geocrew.core.model.GeoFeature
import org.geocrew.core.model.PhotoInfo
import org.geocrew.core.model.photoInfo
import org.geocrew.core.model.Props
import org.geocrew.core.model.ProjectConfig
import org.geocrew.core.model.RecordCheck
import org.geocrew.core.model.Sample
import org.geocrew.core.model.samples
import org.geocrew.core.model.TaskInfo
import org.geocrew.core.model.TaskStatus
import org.geocrew.core.model.task
import org.geocrew.core.model.string
import org.geocrew.core.sensors.LocationSource
import org.geocrew.core.sensors.OrientationSource
import java.io.File
import java.time.Instant
import javax.inject.Inject

/** Longest voice note; a longer talk becomes several. */
private const val MAX_RECORDING_MILLIS = 10 * 60_000L

data class Revision(val feature: GeoFeature, val author: String)

sealed interface HistoryState {
    data object Idle : HistoryState
    data object Loading : HistoryState
    data class Loaded(val revisions: List<Revision>) : HistoryState
    data class Failed(val message: String) : HistoryState
}

@HiltViewModel
class RecordListViewModel @Inject constructor(
    projects: ProjectRepository,
    features: FeatureRepository,
) : ViewModel() {
    val kindFilter = MutableStateFlow<FeatureKind?>(null)
    val query = MutableStateFlow("")

    /**
     * Narrows the list to open tasks (everyone's, or only those handed to this
     * device's member), to records missing a required form field, or to
     * records [RecordCheck] finds something to look at in.
     */
    enum class TaskFilter { NONE, MINE, OPEN, INCOMPLETE, ISSUES }
    val taskFilter = MutableStateFlow(TaskFilter.NONE)

    /** This device's member id in the current workspace, if any. */
    val me: StateFlow<String?> = projects.current.map { it.team?.memberId }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val all: StateFlow<List<GeoFeature>> = projects.current
        .map { it.id }
        .distinctUntilChanged()
        .flatMapLatest { features.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val projectName: StateFlow<String> = projects.current.map { it.name }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The project's form, which decides what counts as incomplete. */
    val config: StateFlow<ProjectConfig> = projects.current.map { it.config }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProjectConfig())

    private val who = combine(me, config) { m, c -> m to c }

    /** Slips worth a second look, by record id. */
    val issues: StateFlow<Map<String, List<RecordCheck.Issue>>> = all.map { RecordCheck.of(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val filters = combine(kindFilter, query, taskFilter) { k, q, t -> Triple(k, q, t) }

    val items: StateFlow<List<GeoFeature>> = combine(all, filters, who, issues) { list, (kind, q, tf), (me, config), issues ->
        list.asSequence()
            .filter { kind == null || it.kind == kind }
            .filter { f ->
                val t = f.task
                when (tf) {
                    TaskFilter.NONE -> true
                    TaskFilter.OPEN -> t != null && t.status != TaskStatus.DONE
                    TaskFilter.MINE -> t != null && t.status != TaskStatus.DONE && t.assignee == me
                    TaskFilter.INCOMPLETE -> config.missing(f).isNotEmpty()
                    TaskFilter.ISSUES -> f.id in issues
                }
            }
            .filter { q.isBlank() || (it.title ?: "").contains(q, ignoreCase = true) || (it.note ?: "").contains(q, ignoreCase = true) }
            .sortedByDescending { it.sync.updatedAt }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val counts: StateFlow<Map<FeatureKind, Int>> = all.map { l -> l.groupingBy { it.kind }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Open tasks in the project and those of them handed to me. */
    data class TaskCounts(val open: Int = 0, val mine: Int = 0)

    /** Records still missing a required field. */
    val incomplete: StateFlow<Int> = combine(all, config) { l, c -> l.count { c.missing(it).isNotEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val taskCounts: StateFlow<TaskCounts> = combine(all, me) { l, me ->
        val open = l.mapNotNull { it.task }.filter { it.status != TaskStatus.DONE }
        TaskCounts(open.size, open.count { it.assignee == me })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TaskCounts())
}

/** Route argument holding the feature id. */
const val FEATURE_ID_ARG = "featureId"

@HiltViewModel
class FeatureDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext appContext: android.content.Context,
    private val features: FeatureRepository,
    val photos: PhotoStore,
    private val settings: SettingsRepository,
    private val orientation: OrientationSource,
    private val location: LocationSource,
    private val projects: ProjectRepository,
    private val team: TeamRepository,
    private val teamComments: TeamComments,
    private val session: TeamSession,
) : ViewModel() {
    private val id: String = checkNotNull(savedState[FEATURE_ID_ARG])

    /** True when the feature's project syncs with a workspace, so the server keeps its history. */
    val shared: StateFlow<Boolean> = projects.current.map { it.team != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _history = MutableStateFlow<HistoryState>(HistoryState.Idle)
    val history: StateFlow<HistoryState> = _history.asStateFlow()

    fun loadHistory() = viewModelScope.launch {
        val f = feature.value ?: return@launch
        val h = team.handle(f.projectId) ?: run { _history.value = HistoryState.Failed("当前工程未连接工作组"); return@launch }
        _history.value = HistoryState.Loading
        _history.value = try {
            val names = runCatching { team.members(h).associate { it.id to it.nickname } }.getOrDefault(emptyMap())
            HistoryState.Loaded(
                team.history(h, id).asReversed().map { r ->
                    Revision(r, r.sync.updatedBy?.let { names[it] ?: "已移出的成员" } ?: "未知")
                },
            )
        } catch (e: java.io.IOException) {
            HistoryState.Failed(if (e is org.geocrew.core.network.ApiException && e.status == 404) "服务器上还没有这条记录，同步后再看" else "读取失败：${e.message}")
        }
    }

    fun restore(r: Revision) = viewModelScope.launch {
        features.restore(id, r.feature)
        _history.value = HistoryState.Idle
    }

    val feature: StateFlow<GeoFeature?> = features.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Lithologies written elsewhere in this project, one entry per record, for suggestions. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val usedLithologies: StateFlow<List<String>> = feature.map { it?.projectId }.distinctUntilChanged()
        .flatMapLatest { pid ->
            if (pid == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else features.observeProject(pid).map { list -> list.filter { it.id != id }.mapNotNull { it.properties.string(Props.LITHOLOGY) } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sample numbers used anywhere in this project, for numbering and duplicate checks. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val usedSampleNumbers: StateFlow<List<String>> = feature.map { it?.projectId }.distinctUntilChanged()
        .flatMapLatest { pid ->
            if (pid == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else features.observeProject(pid).map { list -> list.filterNot { it.deleted }.flatMap { f -> f.samples.map { it.no } } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Adds [edited], or replaces [original] with it. Refuses a number used by
     * another sample in the project, since labs and stores key on it.
     */
    fun saveSample(original: Sample?, edited: Sample) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        val no = edited.no.trim()
        if (no.isEmpty()) { _fieldError.value = "请填写样品编号"; return@launch }
        val others = usedSampleNumbers.value.toMutableList().apply { original?.let { remove(it.no) } }
        if (others.any { it.trim().equals(no, ignoreCase = true) }) {
            _fieldError.value = "样品编号 $no 已在本工程用过"; return@launch
        }
        val sample = edited.copy(no = no, takenAt = edited.takenAt ?: original?.takenAt ?: java.time.Instant.now())
        val list = cur.samples.toMutableList()
        val at = original?.let { o -> list.indexOfFirst { it.no == o.no } } ?: -1
        if (at >= 0) list[at] = sample else list += sample
        features.edit(id) { samples(list) }
    }

    fun removeSample(no: String) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        features.edit(id) { samples(cur.samples.filterNot { it.no == no }) }
    }

    /** The project's form: the fields its records are meant to fill in. */
    val form: StateFlow<List<FieldTemplate>> = projects.current.map { it.config.fields }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _fieldError = MutableStateFlow<String?>(null)
    val fieldError: StateFlow<String?> = _fieldError.asStateFlow()
    fun consumeFieldError() { _fieldError.value = null }

    /** Sets one form field, typed by its template. */
    fun setField(field: FieldTemplate, raw: String) = viewModelScope.launch {
        val value = try {
            field.encode(raw)
        } catch (e: IllegalArgumentException) {
            _fieldError.value = e.message; return@launch
        }
        val cur = feature.value ?: return@launch
        if (cur.properties[field.key] == value) return@launch
        features.edit(id) { put(field.key, value) }
    }

    val coordinateFormat: StateFlow<CoordinateFormat> = settings.settings.map { it.coordinateFormat }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CoordinateFormat.DECIMAL)

    /** Saves text fields; blank values remove the property. */
    fun saveText(title: String, note: String, lithology: String) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        if (cur.title.orEmpty() == title && cur.note.orEmpty() == note && cur.properties.string(Props.LITHOLOGY).orEmpty() == lithology) return@launch
        features.edit(id) {
            this[Props.TITLE] = title.trim().ifBlank { null }
            this[Props.NOTE] = note.trim().ifBlank { null }
            this[Props.LITHOLOGY] = lithology.trim().ifBlank { null }
        }
    }

    /** A leader's review (protocol §6.9); null clears it. The server ignores this from anyone else. */
    fun setReview(state: org.geocrew.core.model.Review.State?, note: String) = viewModelScope.launch {
        val link = feature.value?.projectId?.let { projects.get(it) }?.team ?: return@launch
        if (link.role != TeamRepository.ROLE_LEADER) return@launch
        val r = state?.let { org.geocrew.core.model.Review(it, link.memberId, link.nickname, java.time.Instant.now(), note) }
        features.edit(id) { review(r) }
    }

    fun setMeetAt(at: java.time.Instant?) = viewModelScope.launch {
        features.edit(id) { this[Props.MEET_AT] = at?.toString() }
    }

    fun setLineStyle(ls: org.geocrew.core.model.LineStyle?) = viewModelScope.launch {
        features.edit(id) { this[Props.LINE_STYLE] = ls?.id }
    }

    fun setAttitude(a: Attitude?) = viewModelScope.launch {
        features.edit(id) {
            if (a == null) {
                remove(Props.STRIKE); remove(Props.DIP_DIRECTION); remove(Props.DIP)
            } else {
                a.writeTo(this)
            }
        }
    }

    /** Sets a free attribute. Numbers are stored as numbers so analysis tools can use them. */
    fun setAttribute(key: String, raw: String) = viewModelScope.launch {
        val k = key.trim()
        if (k.isEmpty() || k in RESERVED_KEYS) return@launch
        val v = raw.trim()
        features.edit(id) {
            val n = v.toDoubleOrNull()?.takeIf { it.isFinite() }
            when {
                v.isEmpty() -> remove(k)
                n != null -> this[k] = n
                else -> this[k] = v
            }
        }
    }

    fun removeAttribute(key: String) = viewModelScope.launch {
        if (key in RESERVED_KEYS) return@launch
        features.edit(id) { remove(key) }
    }

    fun setKind(kind: FeatureKind) = viewModelScope.launch {
        features.changeKind(id, kind)
    }

    fun newCaptureTarget() = photos.newCaptureTarget()

    /** Direction and position at the moment the camera is opened, for the photo's [PhotoInfo]. */
    private var aim: PhotoInfo? = null

    /**
     * Notes which way the back camera looks and where we are, just before
     * the camera app takes over (sensors stop reporting to apps in the
     * background). Returns within about a second either way.
     */
    suspend fun takeAim() {
        aim = null
        val look = if (orientation.available) {
            withTimeoutOrNull(800) { orientation.orientation().drop(2).first() }
        } else null
        val fix = if (location.hasPermission()) {
            withTimeoutOrNull(500) { location.fixes().first() }
                ?.takeIf { java.time.Duration.between(it.time, Instant.now()).abs().seconds < 120 }
        } else null
        val decl = settings.settings.first().declination?.toDouble()
            ?: fix?.let { orientation.declinationAt(it.position) } ?: 0.0
        aim = PhotoInfo(azimuth = look?.lookTrue(decl), position = fix?.position, takenAt = Instant.now())
    }

    fun addCaptured(file: File) = viewModelScope.launch {
        // The camera's own EXIF (GPS, time, sometimes direction) wins over our aim.
        val exif = withContext(Dispatchers.IO) { PhotoExif.read(file) }
        val info = exif?.orElse(aim) ?: aim
        aim = null
        photos.commit(file)?.let { addPhoto(it, info) }
    }

    fun addPicked(uri: android.net.Uri) = viewModelScope.launch {
        val sha = photos.import(uri) ?: return@launch
        val exif = withContext(Dispatchers.IO) { PhotoExif.read(photos.file(sha)) }
        addPhoto(sha, exif)
    }

    private fun addPhoto(sha: String, info: PhotoInfo?) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        if (sha in cur.photos) return@launch
        features.edit(id) {
            strings(Props.PHOTOS, cur.photos + sha)
            if (info != null && !info.isEmpty) photoInfo(sha, info)
        }
    }

    /**
     * Renders a field sketch, over photo [background] or on a white page,
     * and adds it to the record's photos marked as a sketch. A sketch over a
     * photo keeps that photo's direction.
     */
    fun saveSketch(strokes: List<org.geocrew.core.model.SketchStroke>, background: String?) = viewModelScope.launch {
        if (strokes.isEmpty()) return@launch
        val file = withContext(Dispatchers.Default) {
            val bg = background?.let { photos.decodeUpright(it, 2048) }
            val bmp = bg?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)?.also { if (it !== bg) bg.recycle() }
                ?: android.graphics.Bitmap.createBitmap(
                    org.geocrew.core.model.Sketch.BLANK_WIDTH, org.geocrew.core.model.Sketch.BLANK_HEIGHT, android.graphics.Bitmap.Config.ARGB_8888,
                ).also { it.eraseColor(android.graphics.Color.WHITE) }
            SketchRender.draw(android.graphics.Canvas(bmp), strokes, bmp.width.toFloat(), bmp.height.toFloat())
            val out = photos.newCaptureTarget().first
            val ok = runCatching { out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) } }.getOrDefault(false)
            bmp.recycle()
            out.takeIf { ok }
        }
        if (file == null) {
            _photoMessage.value = "素描保存失败"
            return@launch
        }
        val f = feature.value
        val info = PhotoInfo(
            position = (f?.geometry as? org.geocrew.core.model.Geometry.Point)?.position,
            takenAt = Instant.now(),
            sketch = true,
            drawnOn = background,
        ).orElse(background?.let { f?.photoInfo?.get(it) }?.copy(position = null, takenAt = null))
        val sha = photos.commit(file) ?: return@launch
        addPhoto(sha, info)
        _photoMessage.value = "素描已加到这条记录的照片里"
    }

    fun removePhoto(sha: String) = viewModelScope.launch {
        val cur = feature.value ?: return@launch
        features.edit(id) {
            strings(Props.PHOTOS, cur.photos - sha)
            photoInfo(sha, null)
        }
    }

    /** Saves a copy of photo [sha] with its record, position, direction and time stamped on it. */
    fun saveStamped(context: android.content.Context, sha: String) = viewModelScope.launch {
        val f = feature.value ?: return@launch
        val info = f.photoInfo[sha] ?: PhotoInfo()
        val project = projects.get(f.projectId)?.name
        val format = coordinateFormat.value
        val lines = info.stamp(f.title, project, java.time.ZoneId.systemDefault(), format) + "同勘 GeoCrew"
        val ok = withContext(Dispatchers.IO) {
            runCatching { PhotoStamp.save(context, photos.file(sha), lines, "GeoCrew_${f.title?.takeIf { it.isNotBlank() } ?: "photo"}_${sha.take(8)}") }.getOrDefault(false)
        }
        _photoMessage.value = if (ok) "已保存到相册的 Pictures/GeoCrew" else "保存失败"
    }

    private val _photoMessage = MutableStateFlow<String?>(null)
    val photoMessage: StateFlow<String?> = _photoMessage.asStateFlow()
    fun consumePhotoMessage() { _photoMessage.value = null }

    // --- voice notes (protocol v0 §6.3) --------------------------------------

    /** A recording in progress. */
    data class RecordingUi(val startedAt: Instant, val elapsedMillis: Long, val level: Float)

    /** The voice note playing now. */
    data class PlaybackUi(val sha: String, val positionMillis: Int, val durationMillis: Int)

    private val recorder = VoiceRecorder(appContext)
    private val player = VoicePlayer()
    private var recordFile: File? = null
    private var recordStart = 0L
    private var recordPosition: org.geocrew.core.model.LngLat? = null
    private var recordJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null
    private val _recording = MutableStateFlow<RecordingUi?>(null)
    val recording: StateFlow<RecordingUi?> = _recording.asStateFlow()
    private val _playback = MutableStateFlow<PlaybackUi?>(null)
    val playback: StateFlow<PlaybackUi?> = _playback.asStateFlow()

    /** Starts a voice note; the microphone permission must already be granted. */
    fun startRecording() {
        if (recorder.active) return
        stopPlayback()
        val target = photos.newRecordingTarget()
        try {
            recorder.start(target)
        } catch (e: Exception) {
            target.delete()
            _photoMessage.value = "无法开始录音" + (e.message?.let { "：$it" } ?: "")
            return
        }
        recordFile = target
        recordStart = android.os.SystemClock.elapsedRealtime()
        recordPosition = null
        val started = Instant.now()
        _recording.value = RecordingUi(started, 0, 0f)
        recordJob = viewModelScope.launch {
            launch {
                if (location.hasPermission()) {
                    recordPosition = withTimeoutOrNull(5_000) { location.fixes().first() }
                        ?.takeIf { java.time.Duration.between(it.time, Instant.now()).abs().seconds < 120 }?.position
                }
            }
            while (true) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - recordStart
                if (elapsed >= MAX_RECORDING_MILLIS) break
                _recording.value = RecordingUi(started, elapsed, recorder.level())
                kotlinx.coroutines.delay(200)
            }
            _photoMessage.value = "录音已到 ${MAX_RECORDING_MILLIS / 60_000} 分钟上限，已自动保存"
            stopRecording()
        }
    }

    /** Stops and attaches the voice note to the record. */
    fun stopRecording() {
        val ui = _recording.value ?: return
        val file = recordFile ?: return
        val seconds = (android.os.SystemClock.elapsedRealtime() - recordStart) / 1000.0
        val position = recordPosition
        val ok = recorder.stop()
        _recording.value = null
        recordFile = null
        recordJob?.cancel()
        recordJob = null
        viewModelScope.launch {
            if (!ok || seconds < 0.5) {
                file.delete()
                _photoMessage.value = "录音太短，没有保存"
                return@launch
            }
            val sha = photos.commit(file, org.geocrew.core.model.AttachmentKind.AUDIO) ?: return@launch
            val cur = feature.value ?: return@launch
            if (sha in cur.audio) return@launch
            features.edit(id) {
                strings(Props.AUDIO, cur.audio + sha)
                audioInfo(sha, AudioInfo(seconds, ui.startedAt, position))
            }
        }
    }

    /** Throws the recording in progress away. */
    fun cancelRecording() {
        _recording.value ?: return
        recorder.stop()
        recordFile?.delete()
        recordFile = null
        _recording.value = null
        recordJob?.cancel()
        recordJob = null
    }

    /** Plays [sha], or stops it when it is already playing. */
    fun togglePlay(sha: String) {
        val wasThis = _playback.value?.sha == sha
        stopPlayback()
        if (wasThis) return
        val file = photos.file(sha, org.geocrew.core.model.AttachmentKind.AUDIO)
        if (!file.isFile) {
            _photoMessage.value = "这段录音还没有下载到手机，联网同步后再试"
            return
        }
        val length = try {
            player.play(file) { stopPlayback() }
        } catch (e: Exception) {
            _photoMessage.value = "无法播放这段录音"
            return
        }
        playJob = viewModelScope.launch {
            while (true) {
                _playback.value = PlaybackUi(sha, player.positionMillis, length)
                kotlinx.coroutines.delay(200)
            }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        player.stop()
        _playback.value = null
    }

    fun removeAudio(sha: String) = viewModelScope.launch {
        if (_playback.value?.sha == sha) stopPlayback()
        val cur = feature.value ?: return@launch
        features.edit(id) {
            strings(Props.AUDIO, cur.audio - sha)
            audioInfo(sha, null)
        }
    }

    override fun onCleared() {
        // A recording still running here was abandoned with the screen.
        if (recorder.active) {
            recorder.stop()
            recordFile?.delete()
        }
        player.stop()
    }

    fun delete() = viewModelScope.launch { features.delete(id) }

    // --- discussion (protocol v0 §8) ------------------------------------------

    /** Who may do what in the discussion; null when the project has no team. */
    data class CommentAccess(val memberId: String, val canPost: Boolean, val isLeader: Boolean)

    val commentAccess: StateFlow<CommentAccess?> = projects.current.map { p ->
        p.team?.let { CommentAccess(it.memberId, it.role != TeamRepository.ROLE_READONLY, it.role == TeamRepository.ROLE_LEADER) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val comments: StateFlow<List<CommentItem>> = projects.current
        .map { it.id to it.team?.memberId }
        .distinctUntilChanged()
        .flatMapLatest { (pid, me) -> teamComments.forFeature(pid, id, me) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _commentError = MutableStateFlow<String?>(null)
    val commentError: StateFlow<String?> = _commentError.asStateFlow()
    fun consumeCommentError() { _commentError.value = null }

    fun postComment(text: String) = viewModelScope.launch {
        val pid = feature.value?.projectId ?: return@launch
        teamComments.post(pid, text, featureId = id)
    }

    /** Unsent comments are simply dropped; sent ones need the server. */
    fun deleteComment(c: CommentItem) = viewModelScope.launch {
        val pid = feature.value?.projectId ?: return@launch
        if (c.pending) {
            teamComments.discard(pid, c.id)
            return@launch
        }
        try {
            session.deleteComment(c.id)
        } catch (e: java.io.IOException) {
            _commentError.value = if (e is org.geocrew.core.network.ApiException && e.isForbidden) "只能删除自己的发言" else "删除需要联网：${e.message}"
        }
    }

    // --- task assignment (protocol v0 §6.1) -----------------------------------

    /** Someone a record can be handed to. */
    data class Assignee(val id: String, val nickname: String, val me: Boolean)

    /** Members to choose from: loaded from the server, or just this device's member while offline. */
    sealed interface AssigneeChoices {
        data object Idle : AssigneeChoices
        data object Loading : AssigneeChoices
        data class Loaded(val members: List<Assignee>, val offline: Boolean) : AssigneeChoices
    }

    private val _assignees = MutableStateFlow<AssigneeChoices>(AssigneeChoices.Idle)
    val assignees: StateFlow<AssigneeChoices> = _assignees.asStateFlow()

    fun loadAssignees() = viewModelScope.launch {
        val pid = feature.value?.projectId ?: return@launch
        val p = projects.get(pid) ?: return@launch
        val link = p.team ?: return@launch
        val me = Assignee(link.memberId, link.nickname, me = true)
        _assignees.value = AssigneeChoices.Loading
        val h = team.handle(p.id)
        val members = h?.let { handle ->
            runCatching { team.members(handle) }.getOrNull()
                ?.filter { !it.revoked && it.role != TeamRepository.ROLE_READONLY }
                ?.map { Assignee(it.id, it.nickname, it.id == link.memberId) }
                ?.sortedWith(compareByDescending<Assignee> { it.me }.thenBy { it.nickname })
        }
        _assignees.value = if (members != null) AssigneeChoices.Loaded(members, offline = false)
        else AssigneeChoices.Loaded(listOf(me), offline = true)
    }

    fun assign(a: Assignee) = viewModelScope.launch {
        val cur = feature.value?.task
        if (cur?.assignee == a.id) return@launch
        // A new assignee starts from scratch; reassigning a finished task reopens it.
        features.edit(id) { TaskInfo(a.id, a.nickname, TaskStatus.TODO).writeTo(this) }
    }

    fun setTaskStatus(status: TaskStatus) = viewModelScope.launch {
        val cur = feature.value?.task ?: return@launch
        if (cur.status == status) return@launch
        features.edit(id) { cur.copy(status = status).writeTo(this) }
    }

    fun clearTask() = viewModelScope.launch {
        if (feature.value?.task == null) return@launch
        features.edit(id) { TaskInfo.clear(this) }
    }
}

/** Properties with their own editors or managed by the app; free attributes never overwrite them. */
internal val RESERVED_KEYS = Props.MANAGED
