package org.geocrew.android

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.action.Outcome
import org.geocrew.storage.SyncActions

/** This phone as a team's member list shows it: its maker and model. */
fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ").take(64)

/** What sync.now says of fields of this phone's that took other names, the team having theirs (protocol v1 7.4); null for none. */
internal fun renamedFields(context: Context, out: JsonObject): String? {
    val renamed = (out["renamed"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return when (renamed.size) {
        0 -> null
        1 -> context.getString(R.string.team_field_renamed, renamed[0].text("from"), renamed[0].text("to"))
        else -> context.resources.getQuantityString(R.plurals.team_fields_renamed, renamed.size, renamed.size)
    }
}

/** What a failed sync action says to the user: short, by what went wrong (protocol v1 3.2). */
internal class SyncTexts(
    private val offline: String,
    private val badInvite: String,
    private val signedOut: String,
    private val unsupported: String,
    private val mixed: String,
    private val notLinked: String,
    private val archived: String,
    private val unsaved: String,
    private val untrusted: String,
    private val keyChanged: String,
    private val forbidden: String,
    private val notUploaded: String,
    private val stale: String,
    private val nameTaken: String,
    private val failed: String,
) {
    fun of(outcome: Outcome): String = when ((outcome as? Outcome.Failed)?.code) {
        SyncActions.OFFLINE -> offline
        "bad_invite" -> badInvite
        "unauthorized" -> signedOut
        SyncActions.UNSUPPORTED -> unsupported
        SyncActions.MIXED -> mixed
        SyncActions.NOT_LINKED -> notLinked
        "archived" -> archived
        SyncActions.UNSAVED -> unsaved
        SyncActions.UNTRUSTED -> untrusted
        SyncActions.KEY_CHANGED -> keyChanged
        "forbidden" -> forbidden
        SyncActions.NOT_UPLOADED -> notUploaded
        SyncActions.STALE -> stale
        SyncActions.NAME_TAKEN -> nameTaken
        else -> failed.format((outcome as? Outcome.Failed)?.message ?: outcome.toString())
    }
}

@Composable
internal fun syncTexts() = SyncTexts(
    offline = stringResource(R.string.sync_offline),
    badInvite = stringResource(R.string.sync_bad_invite),
    signedOut = stringResource(R.string.sync_signed_out),
    unsupported = stringResource(R.string.sync_unsupported),
    mixed = stringResource(R.string.sync_mixed),
    notLinked = stringResource(R.string.sync_not_linked),
    archived = stringResource(R.string.sync_archived),
    unsaved = stringResource(R.string.failure_unsaved),
    untrusted = stringResource(R.string.sync_untrusted),
    keyChanged = stringResource(R.string.sync_key_changed),
    forbidden = stringResource(R.string.sync_forbidden),
    notUploaded = stringResource(R.string.attachment_not_uploaded),
    stale = stringResource(R.string.sync_stale),
    nameTaken = stringResource(R.string.sync_name_taken),
    failed = stringResource(R.string.action_failed),
)

// A server's address as typed, https:// in front when no scheme is given. A Chinese keyboard may type full-width
// punctuation (colon, slash, the ideographic full stop): it is read as the ASCII it stands for, and spaces are dropped.
internal fun address(typed: String): String {
    val t = typed.map { c ->
        when (c) {
            in '\uFF01'..'\uFF5E' -> c - 0xFEE0
            '\u3002' -> '.'
            else -> c
        }
    }.joinToString("").filterNot { it.isWhitespace() }
    if (!t.contains("://")) return "https://$t"
    // The scheme as the transport and the key check read it: lower case.
    return t.substringBefore("://").lowercase() + "://" + t.substringAfter("://")
}

private fun usable(typed: String): Boolean = address(typed).substringAfter("://").isNotBlank()

/** Sharing project [project] with a new team workspace on a server: its address, the workspace's name and the user's nickname. */
@Composable
fun ShareDialog(project: String, onDismiss: () -> Unit, onShare: (server: String, name: String, nickname: String) -> Unit) {
    var server by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(project) }
    var nickname by remember { mutableStateOf("") }
    val ready = usable(server) && name.isNotBlank() && nickname.isNotBlank() && nickname.trim().length <= 32
    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        icon = { Icon(Icons.Rounded.GroupAdd, null) },
        title = { Text(stringResource(R.string.team_share)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerField(server) { server = it }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.team_workspace_name)) }, singleLine = true)
                OutlinedTextField(nickname, { nickname = it }, label = { Text(stringResource(R.string.team_nickname)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onShare(address(server), name.trim(), nickname.trim()) }, enabled = ready) { Text(stringResource(R.string.team_share_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Joining a team workspace: the server's address, the invite code and the user's nickname. */
@Composable
fun JoinDialog(onDismiss: () -> Unit, onJoin: (server: String, code: String, nickname: String) -> Unit) {
    var server by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    val ready = usable(server) && code.isNotBlank() && nickname.isNotBlank() && nickname.trim().length <= 32
    AppDialog(
        onDismissRequest = onDismiss,
        twoPane = true,
        icon = { Icon(Icons.Rounded.GroupAdd, null) },
        title = { Text(stringResource(R.string.team_join)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerField(server) { server = it }
                OutlinedTextField(
                    code, { code = it.uppercase() },
                    label = { Text(stringResource(R.string.team_invite)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                )
                OutlinedTextField(nickname, { nickname = it }, label = { Text(stringResource(R.string.team_nickname)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onJoin(address(server), code.trim(), nickname.trim()) }, enabled = ready) { Text(stringResource(R.string.team_join_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ServerField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        label = { Text(stringResource(R.string.team_server_address)) },
        placeholder = { Text("https://") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
}

/** What sharing or joining on a server needs first about its key (ADR 0014, protocol v1 3.4). */
internal sealed interface KeyCheck {
    /** A certificate authority vouches for the server (or it is plain HTTP, in debug builds): nothing to pin. */
    data object Vouched : KeyCheck

    /** The user checks [fingerprint] with the leader; it is pinned once they say it matches. */
    data class Ask(val fingerprint: String) : KeyCheck

    data class Failed(val outcome: Outcome) : KeyCheck
}

internal suspend fun checkKey(graph: AppGraph, server: String): KeyCheck {
    if (!server.startsWith("https://")) return KeyCheck.Vouched
    val done = graph.invoke(SyncActions.KEY, buildJsonObject { put("server", server) })
    if (done !is Outcome.Done) return KeyCheck.Failed(done)
    val key = done.output.jsonObject
    val fingerprint = (key["fingerprint"] as? JsonPrimitive)?.contentOrNull
        ?: return KeyCheck.Failed(Outcome.Failed(SyncActions.UNTRUSTED, "the server showed no key"))
    return if ((key["trusted"] as? JsonPrimitive)?.booleanOrNull == true) KeyCheck.Vouched else KeyCheck.Ask(fingerprint)
}

/** A key fingerprint in groups of four, to read out and compare. */
internal fun grouped(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")

/** The server's key, to check with the leader's before it is taken. */
@Composable
fun KeyDialog(fingerprint: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text(stringResource(R.string.team_check_key)) },
        text = { Text(grouped(fingerprint), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.team_key_matches)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A workspace's invite code, large, to read out or copy. */
@Composable
fun InviteDialog(code: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AppDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PersonAdd, null) },
        title = { Text(stringResource(R.string.team_invite)) },
        text = { Text(code, style = MaterialTheme.typography.displaySmall) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text(stringResource(R.string.copy)) } },
    )
}
