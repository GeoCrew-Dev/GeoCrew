package org.geocrew.storage

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The workspace a project syncs with, on server [server], and how this phone
 * signs in to it: as [member], with the device's [token] (protocol v1 3.4).
 * [pin] is the server's key the phone takes, when no certificate authority
 * vouches for it (ADR 0014).
 */
data class SyncAccount(val server: String, val workspace: String, val member: String, val token: String, val pin: String? = null)

/**
 * Each project's [SyncAccount], by project id, in [dir]: the app's own
 * storage, left out of backups. The token is a secret (threat model), so it is
 * never written into the project, which people copy and share.
 */
internal class SyncAccounts(private val dir: File) {
    fun of(project: String): SyncAccount? {
        val file = file(project)
        if (!file.isFile) return null
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
        return SyncAccount(text("server") ?: return null, text("workspace") ?: return null, text("member") ?: return null, text("token") ?: return null, text("pin"))
    }

    // Written under another name first: a file cut short never stands for an account.
    fun put(project: String, account: SyncAccount) {
        dir.mkdirs()
        val json = buildJsonObject {
            put("server", account.server)
            put("workspace", account.workspace)
            put("member", account.member)
            put("token", account.token)
            account.pin?.let { put("pin", it) }
        }
        val part = File(dir, "${file(project).name}.part")
        part.writeText(json.toString())
        check(part.renameTo(file(project))) { "cannot keep the sync account" }
    }

    fun remove(project: String) {
        file(project).delete()
        members(project).delete()
    }

    /**
     * The nicknames of the workspace's members by id, as the last sync listed
     * them: for conflict records, which name members by id. Kept here with the
     * account, not in the project people copy and share.
     */
    fun nicknames(project: String): Map<String, String> {
        val file = members(project)
        if (!file.isFile) return emptyMap()
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return emptyMap()
        return o.entries.mapNotNull { (id, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { id to it } }.toMap()
    }

    fun putNicknames(project: String, nicknames: Map<String, String>) {
        dir.mkdirs()
        val part = File(dir, "${members(project).name}.part")
        part.writeText(buildJsonObject { nicknames.forEach { (id, name) -> put(id, name) } }.toString())
        check(part.renameTo(members(project))) { "cannot keep the members" }
    }

    private fun file(project: String) = File(dir, safe(project) + ".json")

    private fun members(project: String) = File(dir, safe(project) + ".members.json")

    private fun safe(project: String) = project.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
