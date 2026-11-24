package org.geocrew.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.concurrent.TimeUnit

/** A team member's live position as relayed by the server. */
@Serializable
data class TeamPosition(
    @SerialName("member_id") val memberId: String = "",
    val nickname: String = "",
    val lon: Double,
    val lat: Double,
    val alt: Double? = null,
    val accuracy: Double? = null,
    val heading: Double? = null,
    val speed: Double? = null,
    val at: String? = null,
) {
    val time: Instant? get() = at?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

@Serializable
internal data class HelloWire(val online: List<String> = emptyList(), val positions: List<TeamPosition> = emptyList())

@Serializable
internal data class PresenceWire(@SerialName("member_id") val memberId: String, val online: Boolean)

@Serializable
internal data class SeqWire(val seq: Long)

@Serializable
internal data class ErrorWire(val error: String = "", val message: String = "")

sealed interface RealtimeEvent {
    data class Hello(val online: List<String>, val positions: List<TeamPosition>) : RealtimeEvent
    data class Presence(val memberId: String, val online: Boolean) : RealtimeEvent
    data class Position(val position: TeamPosition) : RealtimeEvent
    data class FeatureChanged(val seq: Long) : RealtimeEvent
    data object MembersChanged : RealtimeEvent
    data class Error(val code: String, val message: String) : RealtimeEvent
}

enum class RealtimeState { Idle, Connecting, Connected, Waiting, Unauthorized }

/**
 * The realtime channel of one workspace (protocol v0 §7). Reconnects with
 * exponential backoff until [stop]; a 401 stops for good because the token
 * was revoked.
 */
class RealtimeClient(
    private val api: GeoCrewApi,
    private val workspaceId: String,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(RealtimeState.Idle)
    val state: StateFlow<RealtimeState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                _state.value = RealtimeState.Connecting
                val closed = CompletableDeferred<Int>()
                val ws = open(closed)
                val code = try {
                    closed.await()
                } finally {
                    ws.cancel()
                    socket = null
                }
                if (code == 401 || code == 403) {
                    _state.value = RealtimeState.Unauthorized
                    return@launch
                }
                if (code == CONNECTED_THEN_CLOSED) backoff = 1_000L
                _state.value = RealtimeState.Waiting
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, null)
        socket = null
        _state.value = RealtimeState.Idle
    }

    /** Sends our position; dropped silently while disconnected (positions are ephemeral). */
    fun sendPosition(lon: Double, lat: Double, alt: Double? = null, accuracy: Double? = null, heading: Double? = null, speed: Double? = null): Boolean {
        val p = TeamPosition(lon = lon, lat = lat, alt = alt, accuracy = accuracy, heading = heading, speed = speed)
        val body = GeoCrewApi.json.encodeToJsonElement(TeamPosition.serializer(), p) as JsonObject
        val msg = JsonObject(body + ("type" to kotlinx.serialization.json.JsonPrimitive("position")) - "member_id" - "nickname")
        return socket?.send(msg.toString()) == true
    }

    private fun open(closed: CompletableDeferred<Int>): WebSocket {
        val req = Request.Builder().url(api.realtimeUrl(workspaceId)).apply {
            api.authHeader()?.let { header("Authorization", it) }
        }.build()
        val wsClient = client.newBuilder().pingInterval(25, TimeUnit.SECONDS).build()
        return wsClient.newWebSocket(req, object : WebSocketListener() {
            private var opened = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                socket = webSocket
                _state.value = RealtimeState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text)?.let { _events.tryEmit(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(if (opened) CONNECTED_THEN_CLOSED else 0)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(response?.code ?: if (opened) CONNECTED_THEN_CLOSED else 0)
            }
        })
    }

    companion object {
        private const val CONNECTED_THEN_CLOSED = -1

        /** Decodes one server message; unknown types are ignored for forward compatibility. */
        fun parse(text: String): RealtimeEvent? {
            val json = GeoCrewApi.json
            val o = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return null
            return runCatching {
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "hello" -> json.decodeFromJsonElement(HelloWire.serializer(), o).let { RealtimeEvent.Hello(it.online, it.positions) }
                    "presence" -> json.decodeFromJsonElement(PresenceWire.serializer(), o).let { RealtimeEvent.Presence(it.memberId, it.online) }
                    "position" -> RealtimeEvent.Position(json.decodeFromJsonElement(TeamPosition.serializer(), o))
                    "feature_changed" -> RealtimeEvent.FeatureChanged(json.decodeFromJsonElement(SeqWire.serializer(), o).seq)
                    "members_changed" -> RealtimeEvent.MembersChanged
                    "error" -> json.decodeFromJsonElement(ErrorWire.serializer(), o).let { RealtimeEvent.Error(it.error, it.message) }
                    else -> null
                }
            }.getOrNull()
        }
    }
}
