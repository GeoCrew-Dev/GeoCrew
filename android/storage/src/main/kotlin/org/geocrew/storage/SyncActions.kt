package org.geocrew.storage

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.geocrew.core.action.Action
import org.geocrew.core.action.ActionResult
import org.geocrew.core.action.ActionSpec
import org.geocrew.core.action.Caller
import org.geocrew.core.action.Risk
import org.geocrew.core.action.Stability
import org.geocrew.core.sync.HttpTransport
import org.geocrew.core.sync.KeyChanged
import org.geocrew.core.sync.Pins
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
                val sync = ProjectSync(project, client, account)
                sync.share()
                val pushed = sync.push(info.maxPushBatch)
                ActionResult.Done(
                    buildJsonObject {
                        put("workspace", joined.workspace)
                        joined.inviteCode?.let { put("invite_code", it) }
                        put("sent", pushed.sent)
                        put("rejected", pushed.rejected)
                    },
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
                val pulled = ProjectSync(project, client, account).pull(info.maxPullBatch)
                keepNicknames(project, client, account)
                ActionResult.Done(
                    buildJsonObject {
                        put("project", project.name)
                        put("workspace", joined.workspace)
                        put("pulled", pulled.rows)
                        put("layers", pulled.layers)
                    },
                    endsUndo = opened.endsUndo,
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
                val sync = ProjectSync(project, client, account)
                if (!project.data(Outbox::linked)) sync.share()
                val pushed = sync.push(info.maxPushBatch)
                // Rows written into the file under an edit buffer with unsaved edits could clash with them on save.
                val pulled = if (unsaved() == null) sync.pull(info.maxPullBatch) else null
                keepNicknames(project, client, account)
                ActionResult.Done(
                    buildJsonObject {
                        put("sent", pushed.sent)
                        put("rejected", pushed.rejected)
                        put("conflicts", pushed.conflicts + (pulled?.conflicts ?: 0))
                        put("open_conflicts", Conflicts.open(project))
                        if (pulled == null) put("pulled", JsonNull) else put("pulled", pulled.rows)
                        put("layers", pulled?.layers ?: 0)
                    },
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
                    put("conflicts", Conflicts.open(project))
                },
            )
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
                val sync = ProjectSync(project, client, account)
                sync.resolve(p.text("conflict")!!, choose)
                val pulled = if (choose == SyncClient.LOST && unsaved() == null) sync.pull(client.info().maxPullBatch) else null
                ActionResult.Done(
                    buildJsonObject {
                        put("open_conflicts", Conflicts.open(project))
                        if (pulled == null) put("pulled", JsonNull) else put("pulled", pulled.rows)
                    },
                )
            }
        },
    )

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
        private val RESOLVE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["conflict", "choose"],
                "properties": {"conflict": {"type": "string"}, "choose": {"enum": ["kept", "lost"]}}}""",
        )
        private val NO_PARAMS = schema("""{"type": "object", "additionalProperties": false, "properties": {}}""")
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
