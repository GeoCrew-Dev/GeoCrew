package org.geocrew.storage

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.geocrew.core.action.Action
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.ActionSpec
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Risk
import org.geocrew.core.action.Stability
import org.geocrew.core.sync.HttpTransport
import org.geocrew.core.sync.KeyChanged
import org.geocrew.core.sync.Pins
import org.geocrew.core.sync.Received
import org.geocrew.core.sync.ServerError
import org.geocrew.core.sync.ServerKey
import org.geocrew.core.sync.SyncClient
import org.geocrew.core.sync.Transport
import org.geocrew.core.sync.Untrusted

/**
 * Syncing the open project with a team workspace (protocol v1, ADR 0008):
 * sharing it on a server, sending its saved edits, and how far it is. Each
 * project's account is kept in [accountsDir] on this phone; [transport] reaches a
 * server by its address and the key pinned for it, if any (HTTP, or a fake in
 * tests), and [keyOf] reads a server's key for the user to check.
 */
class SyncActions(
    private val workspace: Workspace,
    accountsDir: File,
    private val keyOf: (String) -> ServerKey = { Pins.keyOf(it) },
    private val transport: (server: String, pin: String?) -> Transport = { server, pin -> HttpTransport(server, pin) },
) {
    private val accounts = SyncAccounts(accountsDir)

    val actions: List<Action> = listOf(
        SyncAction(
            KEY, "Server key",
            "The key of the server at [server] (an https address): its fingerprint, and whether a certificate authority vouches for " +
                "it. One not vouched for is checked with the leader, then given as the [pin] to share or join",
            Risk.VIEW, KEY_PARAMS, """{"server": "https://192.168.1.20:8080"}""",
        ) { p ->
            remote {
                val key = keyOf(p.text("server")!!.trim())
                ActionResult.Done(
                    buildJsonObject {
                        put("fingerprint", key.fingerprint)
                        put("trusted", key.trusted)
                    },
                )
            }
        },
        SyncAction(
            SHARE, "Share project",
            "Creates a team workspace for the open project on the server at [server] (taking only the key [pin] when given), with " +
                "the user as its leader ([nickname], on [device]), sends it the project's layers and features, and syncs the project with it from then on; gives the invite code",
            Risk.SEND, SHARE_PARAMS, """{"server": "https://geo.example.org", "nickname": "张三", "device": "Galaxy S24"}""",
        ) { p ->
            val project = workspace.current ?: return@SyncAction ActionResult.Failed(NO_PROJECT, "no project is open")
            if (accounts.of(project.id) != null) return@SyncAction ActionResult.Failed(LINKED, "the project already syncs with a workspace")
            val server = p.text("server")!!.trim()
            val pin = p.text("pin")?.takeIf { it.isNotBlank() }
            remote {
                val client = SyncClient(transport(server, pin))
                val info = client.info()
                if (info.protocol != 1 || "sync" !in info.capabilities) return@remote ActionResult.Failed(UNSUPPORTED, "the server does not speak protocol v1 sync")
                if ("create" !in info.capabilities) return@remote ActionResult.Failed(UNSUPPORTED, "the server takes no new workspaces")
                // Every layer is checked before the workspace is made: none is left behind for a project that cannot go.
                ProjectSync.specs(project)
                val joined = client.create(p.text("name")?.takeIf { it.isNotBlank() } ?: project.name, p.text("nickname")!!, p.text("device")!!)
                val account = SyncAccount(server, joined.workspace, joined.member, joined.token, pin)
                accounts.put(project.id, account)
                val ended = ArrayList<String>()
                val sync = ProjectSync(project, client, account, changing(ended))
                sync.share()
                val pushed = sync.push(info.maxPushBatch)
                ActionResult.Done(
                    buildJsonObject {
                        put("workspace", joined.workspace)
                        joined.inviteCode?.let { put("invite_code", it) }
                        put("sent", pushed.sent)
                        put("rejected", pushed.rejected)
                    },
                    endsUndo = ended.distinct(),
                )
            }
        },
        SyncAction(
            JOIN, "Join workspace",
            "Joins the team workspace on the server at [server] (taking only the key [pin] when given) with an [invite_code] as a new member ([nickname], on [device]), or with " +
                "a [transfer_code] as the member it was issued for, makes a new project for it (named in [language]) and brings in its " +
                "layers and features; the open project closes, and nothing in it may be unsaved",
            Risk.SEND, JOIN_PARAMS, """{"server": "https://geo.example.org", "invite_code": "K7QX2MPA", "nickname": "李四", "device": "Mate 60"}""",
        ) { p ->
            unsaved()?.let { return@SyncAction it }
            val server = p.text("server")!!.trim()
            val invite = p.text("invite_code")?.takeIf { it.isNotBlank() }
            val transfer = p.text("transfer_code")?.takeIf { it.isNotBlank() }
            val pin = p.text("pin")?.takeIf { it.isNotBlank() }
            if ((invite == null) == (transfer == null)) return@SyncAction ActionResult.Failed(BAD_REQUEST, "an invite code or a transfer code, one of them")
            if (invite != null && p.text("nickname").isNullOrBlank()) return@SyncAction ActionResult.Failed(BAD_REQUEST, "a nickname comes with an invite code")
            remote {
                val client = SyncClient(transport(server, pin))
                val info = client.info()
                if (info.protocol != 1 || "sync" !in info.capabilities) return@remote ActionResult.Failed(UNSUPPORTED, "the server does not speak protocol v1 sync")
                val joined = if (transfer != null) client.joinWithTransferCode(transfer, p.text("device")!!) else client.join(invite!!, p.text("nickname")!!, p.text("device")!!)
                val opened = workspace.createAndOpen(joined.workspaceName, p.text("language") ?: "en")
                if (opened !is ActionResult.Done) return@remote opened
                val project = checkNotNull(workspace.current)
                val account = SyncAccount(server, joined.workspace, joined.member, joined.token, pin)
                accounts.put(project.id, account)
                project.link(joined.workspace)
                val ended = ArrayList(opened.endsUndo)
                val sync = ProjectSync(project, client, account, changing(ended))
                val pulled = sync.pull(info.maxPullBatch)
                keepNicknames(sync.project, client, account)
                ActionResult.Done(
                    buildJsonObject {
                        put("project", sync.project.name)
                        put("workspace", joined.workspace)
                        put("pulled", pulled.rows)
                        put("layers", pulled.layers)
                        renamed(sync)
                    },
                    endsUndo = ended.distinct(),
                )
            }
        },
        SyncAction(
            NOW, "Sync now",
            "Sends the open project's saved edits to the workspace it syncs with, then brings in the workspace's changes; a share cut " +
                "short is finished first. Changes come in only while nothing is unsaved (pulled is null then)",
            Risk.SEND, NO_PARAMS, "{}",
        ) { _ ->
            val project = workspace.current ?: return@SyncAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val account = accounts.of(project.id) ?: return@SyncAction ActionResult.Failed(NOT_LINKED, "the project syncs with no workspace")
            remote {
                val client = SyncClient(transport(account.server, account.pin))
                val info = client.info()
                val ended = ArrayList<String>()
                val sync = ProjectSync(project, client, account, changing(ended))
                if (!project.data(Outbox::linked)) sync.share()
                val pushed = sync.push(info.maxPushBatch)
                // Rows written into the file under an edit buffer with unsaved edits could clash with them on save.
                val pulled = if (unsaved() == null) sync.pull(info.maxPullBatch) else null
                keepNicknames(sync.project, client, account)
                ActionResult.Done(
                    buildJsonObject {
                        put("sent", pushed.sent)
                        put("rejected", pushed.rejected)
                        put("conflicts", pushed.conflicts + (pulled?.conflicts ?: 0))
                        put("open_conflicts", Conflicts.open(sync.project))
                        put("structure", sync.project.data { SharedSchema.unsent(it, sync.project) }.size)
                        renamed(sync)
                        if (pulled == null) put("pulled", JsonNull) else put("pulled", pulled.rows)
                        put("layers", pulled?.layers ?: 0)
                    },
                    endsUndo = ended.distinct(),
                )
            }
        },
        SyncAction(
            INVITE, "Invite code",
            "The invite code of the workspace the open project syncs with, for a member who may invite others; null for the others",
            Risk.VIEW, NO_PARAMS, "{}",
        ) { _ ->
            val project = workspace.current ?: return@SyncAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val account = accounts.of(project.id) ?: return@SyncAction ActionResult.Failed(NOT_LINKED, "the project syncs with no workspace")
            remote {
                val code = SyncClient(transport(account.server, account.pin)).inviteCode(account.workspace, account.token)
                ActionResult.Done(buildJsonObject { if (code == null) put("invite_code", JsonNull) else put("invite_code", code) })
            }
        },
        SyncAction(
            STATUS, "Sync status",
            "Whether the open project syncs with a workspace, which, and how many changes wait to be sent or were refused",
            Risk.VIEW, NO_PARAMS, "{}",
        ) { _ ->
            val project = workspace.current ?: return@SyncAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val account = accounts.of(project.id)
            val (waiting, rejected) = project.data(Sending::counts)
            val files = project.data(Outbox::filesWaiting)
            ActionResult.Done(
                buildJsonObject {
                    put("linked", account != null)
                    account?.let {
                        put("server", it.server)
                        put("workspace", it.workspace)
                        it.pin?.let { pin -> put("fingerprint", pin) }
                    }
                    put("waiting", waiting)
                    put("rejected", rejected)
                    put("files", files)
                    put("structure", project.data { SharedSchema.unsent(it, project) }.size)
                    put("conflicts", Conflicts.open(project))
                },
            )
        },
        SyncAction(
            FILE, "Fetch attachment file",
            "Fetches the file [file] (its SHA-256) of type [type], an attachment's original or thumbnail, from the workspace the open " +
                "project syncs with into the project's attachment folder, going on from where an earlier try stopped; gives its path. " +
                "not_uploaded: it is not up yet",
            Risk.VIEW, FILE_PARAMS, """{"file": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", "type": "image/jpeg"}""",
        ) { p ->
            val project = workspace.current ?: return@SyncAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val account = accounts.of(project.id) ?: return@SyncAction ActionResult.Failed(NOT_LINKED, "the project syncs with no workspace")
            val sha = p.text("file")!!
            // The hash names the file in the attachment folder: nothing else may.
            if (!HASH.matches(sha)) return@SyncAction ActionResult.Failed(BAD_REQUEST, "file is a SHA-256 in lower-case hex")
            val type = p.text("type")!!
            remote {
                when (ProjectSync(project, SyncClient(transport(account.server, account.pin)), account).fetch(sha, type)) {
                    Received.DONE -> ActionResult.Done(buildJsonObject { put("path", AttachmentFiles.fileOf(project.attachments, sha, type).path) })
                    Received.NOT_UPLOADED -> ActionResult.Failed(NOT_UPLOADED, "the file is not uploaded yet")
                    Received.BROKEN -> ActionResult.Failed(BROKEN, "what came is not the file its hash names")
                }
            }
        },
        SyncAction(
            CONFLICTS, "Conflicts",
            "The conflicts of the open project waiting to be settled (protocol v1 6.5), as their records came: the layer, the feature (fid, " +
                "name), the field (or geometry), and the value kept against the one that lost, each with whose it is and when",
            Risk.VIEW, NO_PARAMS, "{}",
        ) { _ ->
            val project = workspace.current ?: return@SyncAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val account = accounts.of(project.id) ?: return@SyncAction ActionResult.Failed(NOT_LINKED, "the project syncs with no workspace")
            ActionResult.Done(buildJsonObject { put("conflicts", Conflicts.list(project, account.member, accounts.nicknames(project.id))) })
        },
        SyncAction(
            RESOLVE, "Settle conflict",
            "Settles [conflict] on the server: [choose] kept leaves the value as it is, lost puts the losing value back (for a " +
                "refused deletion: deletes the feature). Only the leader and the two members it is between may settle it. A value " +
                "put back comes in at once when nothing is unsaved (pulled is null otherwise)",
            Risk.SEND, RESOLVE_PARAMS, """{"conflict": "k91x", "choose": "lost"}""",
        ) { p ->
            val project = workspace.current ?: return@SyncAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val account = accounts.of(project.id) ?: return@SyncAction ActionResult.Failed(NOT_LINKED, "the project syncs with no workspace")
            val choose = p.text("choose")!!
            remote {
                val client = SyncClient(transport(account.server, account.pin))
                val ended = ArrayList<String>()
                val sync = ProjectSync(project, client, account, changing(ended))
                sync.resolve(p.text("conflict")!!, choose)
                val pulled = if (choose == SyncClient.LOST && unsaved() == null) sync.pull(client.info().maxPullBatch) else null
                ActionResult.Done(
                    buildJsonObject {
                        put("open_conflicts", Conflicts.open(sync.project))
                        if (pulled == null) put("pulled", JsonNull) else put("pulled", pulled.rows)
                        renamed(sync)
                    },
                    endsUndo = ended.distinct(),
                )
            }
        },
    )

    // The fields of this phone's that sync gave other names, the team having theirs (protocol v1 7.4), to tell the user.
    private fun JsonObjectBuilder.renamed(sync: ProjectSync) = putJsonArray("renamed") {
        for (r in sync.renamed) add(buildJsonObject { put("layer", r.layer); put("from", r.from); put("to", r.to) })
    }

    // Fields changed for sync (protocol v1 7.4), the project opened again after; the undo histories that end go into [ended].
    private fun changing(ended: MutableList<String>): (Project, () -> Unit) -> Project? = { p, change ->
        workspace.changeFieldsForSync(p, change)?.let { (again, closed) ->
            ended += closed
            again
        }
    }

    // The members' nicknames for conflict records, as the server lists them now; when that fails, the last ones stay.
    private fun keepNicknames(project: Project, client: SyncClient, account: SyncAccount) {
        val members = runCatching { client.members(account.workspace, account.token) }.getOrNull() ?: return
        accounts.putNicknames(project.id, members.associate { it.id to it.nickname })
    }

    // Unsaved edits in the open project, which a pull must not write under.
    private fun unsaved(): ActionResult.Failed? {
        val layers = workspace.editing.layersBeingEdited.filter(workspace.editing::isModified)
        return if (layers.isEmpty()) null else ActionResult.Failed(UNSAVED, "save or discard the edits of ${layers.joinToString()} first")
    }

    // A call to the server: a refusal and a network failure each become a failure of the action, with what to tell the user.
    private inline fun remote(block: () -> ActionResult): ActionResult = try {
        block()
    } catch (e: ServerError) {
        ActionResult.Failed(e.code, e.message ?: "the server refused")
    } catch (e: Untrusted) {
        ActionResult.Failed(UNTRUSTED, "the server's certificate is not vouched for: check its key (sync.key) with the leader, then give it as the pin")
    } catch (e: KeyChanged) {
        ActionResult.Failed(KEY_CHANGED, "the server's key changed to ${e.seen}: never taken without the leader's word")
    } catch (e: ProjectSync.Unsupported) {
        ActionResult.Failed(MIXED, e.message ?: "a layer the workspace cannot take")
    } catch (e: IOException) {
        ActionResult.Failed(OFFLINE, e.message ?: "the server cannot be reached")
    }

    private class SyncAction(
        id: String,
        name: String,
        description: String,
        risk: Risk,
        params: JsonObject,
        example: String,
        private val body: suspend (JsonObject) -> ActionResult,
    ) : Action {
        override val spec = ActionSpec(id, name, description, params, risk, Stability.PREVIEW, examples = listOf(schema(example)))

        override suspend fun run(caller: Caller, params: JsonObject): ActionResult = body(params)
    }

    companion object {
        const val SHARE = "sync.share"
        const val JOIN = "sync.join"
        const val INVITE = "sync.invite"
        const val NOW = "sync.now"
        const val STATUS = "sync.status"
        const val KEY = "sync.key"
        const val CONFLICTS = "sync.conflicts"
        const val FILE = "sync.file"
        const val RESOLVE = "sync.resolve"

        /** [RESOLVE]'s choices (protocol v1 8.3): keep the value as it is, or take the one that lost. */
        const val KEEP = SyncClient.KEPT
        const val TAKE_LOST = SyncClient.LOST

        /** Failure codes besides the server's own (protocol v1 3.2). */
        const val NO_PROJECT = "no_project"
        const val LINKED = "linked"
        const val NOT_LINKED = "not_linked"
        const val UNSUPPORTED = "unsupported"
        const val MIXED = "mixed_geometry"
        const val OFFLINE = "offline"
        const val UNSAVED = "unsaved"
        const val BAD_REQUEST = "invalid"
        const val UNTRUSTED = "untrusted"
        const val KEY_CHANGED = "key_changed"
        const val NOT_UPLOADED = "not_uploaded"
        const val BROKEN = "broken"

        private val HASH = Regex("[0-9a-f]{64}")

        private fun schema(s: String) = Json.parseToJsonElement(s).jsonObject

        private val SHARE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["server", "nickname", "device"],
                "properties": {"server": {"type": "string"}, "pin": {"type": "string"}, "name": {"type": "string"}, "nickname": {"type": "string"},
                "device": {"type": "string"}}}""",
        )
        private val JOIN_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["server", "device"],
                "properties": {"server": {"type": "string"}, "pin": {"type": "string"}, "invite_code": {"type": "string"}, "transfer_code": {"type": "string"},
                "nickname": {"type": "string"}, "device": {"type": "string"}, "language": {"enum": ["zh", "en"]}}}""",
        )
        private val KEY_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["server"], "properties": {"server": {"type": "string"}}}""",
        )
        private val FILE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["file", "type"],
                "properties": {"file": {"type": "string"}, "type": {"type": "string"}}}""",
        )
        private val RESOLVE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["conflict", "choose"],
                "properties": {"conflict": {"type": "string"}, "choose": {"enum": ["kept", "lost"]}}}""",
        )
        private val NO_PARAMS = schema("""{"type": "object", "additionalProperties": false, "properties": {}}""")
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
