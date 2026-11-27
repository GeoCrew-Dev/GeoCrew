package org.geocrew.storage

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
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
import org.geocrew.core.sync.ServerError
import org.geocrew.core.sync.SyncClient
import org.geocrew.core.sync.Transport

/**
 * Syncing the open project with a team workspace (protocol v1, ADR 0008):
 * sharing it on a server, sending its saved edits, and how far it is. Each
 * project's account is kept in [accountsDir] on this phone; [transport] reaches a
 * server by its address (HTTP, or a fake in tests).
 */
class SyncActions(private val workspace: Workspace, accountsDir: File, private val transport: (String) -> Transport = { HttpTransport(it) }) {
    private val accounts = SyncAccounts(accountsDir)

    val actions: List<Action> = listOf(
        SyncAction(
            SHARE, "Share project",
            "Creates a team workspace for the open project on the server at [server], with the user as its leader ([nickname], on " +
                "[device]), sends it the project's layers and features, and syncs the project with it from then on; gives the invite code",
            Risk.SEND, SHARE_PARAMS, """{"server": "https://geo.example.org", "nickname": "张三", "device": "Galaxy S24"}""",
        ) { p ->
            val project = workspace.current ?: return@SyncAction ActionResult.Failed(NO_PROJECT, "no project is open")
            if (accounts.of(project.id) != null) return@SyncAction ActionResult.Failed(LINKED, "the project already syncs with a workspace")
            val server = p.text("server")!!.trim()
            remote {
                val client = SyncClient(transport(server))
                val info = client.info()
                if (info.protocol != 1 || "sync" !in info.capabilities) return@remote ActionResult.Failed(UNSUPPORTED, "the server does not speak protocol v1 sync")
                if ("create" !in info.capabilities) return@remote ActionResult.Failed(UNSUPPORTED, "the server takes no new workspaces")
                // Every layer is checked before the workspace is made: none is left behind for a project that cannot go.
                ProjectSync.specs(project)
                val joined = client.create(p.text("name")?.takeIf { it.isNotBlank() } ?: project.name, p.text("nickname")!!, p.text("device")!!)
                val account = SyncAccount(server, joined.workspace, joined.member, joined.token)
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
            NOW, "Sync now",
            "Sends the open project's saved edits to the workspace it syncs with; a share cut short is finished first",
            Risk.SEND, NO_PARAMS, "{}",
        ) { _ ->
            val project = workspace.current ?: return@SyncAction ActionResult.Failed(NO_PROJECT, "no project is open")
            val account = accounts.of(project.id) ?: return@SyncAction ActionResult.Failed(NOT_LINKED, "the project syncs with no workspace")
            remote {
                val client = SyncClient(transport(account.server))
                val sync = ProjectSync(project, client, account)
                if (!project.data(Outbox::linked)) sync.share()
                val pushed = sync.push(client.info().maxPushBatch)
                ActionResult.Done(buildJsonObject { put("sent", pushed.sent); put("rejected", pushed.rejected); put("conflicts", pushed.conflicts) })
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
                    }
                    put("waiting", waiting)
                    put("rejected", rejected)
                },
            )
        },
    )

    // A call to the server: a refusal and a network failure each become a failure of the action, with what to tell the user.
    private inline fun remote(block: () -> ActionResult): ActionResult = try {
        block()
    } catch (e: ServerError) {
        ActionResult.Failed(e.code, e.message ?: "the server refused")
    } catch (e: ProjectSync.Unsupported) {
        ActionResult.Failed(UNSUPPORTED, e.message ?: "a layer the workspace cannot take")
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
        const val NOW = "sync.now"
        const val STATUS = "sync.status"

        /** Failure codes besides the server's own (protocol v1 3.2). */
        const val NO_PROJECT = "no_project"
        const val LINKED = "linked"
        const val NOT_LINKED = "not_linked"
        const val UNSUPPORTED = "unsupported"
        const val OFFLINE = "offline"

        private fun schema(s: String) = Json.parseToJsonElement(s).jsonObject

        private val SHARE_PARAMS = schema(
            """{"type": "object", "additionalProperties": false, "required": ["server", "nickname", "device"],
                "properties": {"server": {"type": "string"}, "name": {"type": "string"}, "nickname": {"type": "string"}, "device": {"type": "string"}}}""",
        )
        private val NO_PARAMS = schema("""{"type": "object", "additionalProperties": false, "properties": {}}""")
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
