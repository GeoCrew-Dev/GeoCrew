package org.geocrew.core.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** How far a permission reaches: plain permissions are [NONE] or [ALL]; edit_rows and delete_rows also take [OWN] (rows one created). */
enum class Level {
    NONE, OWN, ALL;

    companion object {
        /** false, true, "none", "own" or "all", as the protocol writes a level. */
        fun of(json: JsonElement): Level {
            val p = json as? JsonPrimitive ?: throw IllegalArgumentException("permission level $json")
            if (!p.isString) return when (p.booleanOrNull) {
                true -> ALL
                false -> NONE
                null -> throw IllegalArgumentException("permission level $json")
            }
            return when (p.content) {
                "none" -> NONE
                "own" -> OWN
                "all" -> ALL
                else -> throw IllegalArgumentException("permission level $json")
            }
        }
    }
}

/** A named set of permissions; a higher [position] is more senior. */
data class Role(val position: Int, val permissions: Map<String, Level>)

/**
 * What a member may do in a workspace (protocol v1 4; ADR 0009), as the server
 * decides it: the same as server/internal/perm, checked against the cases in
 * protocol/testdata/permissions, so that offline the phone reaches the answers
 * the server will. [members] gives each member's roles besides the base role
 * ([BASE]); [layers] each layer's overrides, by role and permission.
 */
data class Workspace(
    val leader: String,
    val roles: Map<String, Role>,
    val members: Map<String, List<String>>,
    val layers: Map<String, Map<String, Map<String, Level>>> = emptyMap(),
) {
    private fun rolesOf(member: String) = listOf(BASE) + members[member].orEmpty()

    /**
     * [member]'s level of [perm], on [layer] when given. Layer overrides work
     * as in Discord: the base role's override replaces the combined role
     * permissions; if other roles of the member override the same permission,
     * the highest of those replaces it again.
     */
    fun level(member: String, perm: String, layer: String? = null): Level {
        if (member == leader) return Level.ALL
        if (member !in members) return Level.NONE
        var v = if (perm == VIEW_LAYER) Level.ALL else Level.NONE
        for (r in rolesOf(member)) roles[r]?.permissions?.get(perm)?.let { if (it > v) v = it }
        if (layer == null) return v
        val overrides = layers[layer].orEmpty()
        overrides[BASE]?.get(perm)?.let { v = it }
        val own = members[member].orEmpty().mapNotNull { overrides[it]?.get(perm) }
        if (own.isNotEmpty()) v = own.max()
        return v
    }

    /** Whether [member] holds [perm] at all (without layer overrides); safety rights belong to every member. */
    fun has(member: String, perm: String): Boolean =
        if (perm in SAFETY) member in members || member == leader else level(member, perm) > Level.NONE

    /** Whether [member] may edit (edit_rows) or delete (delete_rows) a row on [layer] created by [owner]; seeing the layer is needed too. */
    fun canTouchRow(member: String, perm: String, layer: String, owner: String): Boolean {
        if (level(member, VIEW_LAYER, layer) == Level.NONE) return false
        return when (level(member, perm, layer)) {
            Level.ALL -> true
            Level.OWN -> owner == member
            Level.NONE -> false
        }
    }

    /** The position of [member]'s most senior role; the leader outranks everyone. */
    fun rank(member: String): Int {
        if (member == leader) return Int.MAX_VALUE
        var best = roles[BASE]?.position ?: 0
        for (r in members[member].orEmpty()) roles[r]?.position?.let { if (it > best) best = it }
        return best
    }

    /** Whether [actor] may edit or delete [role] (via manage_roles), or give it to someone (via manage_members): it must sit below [actor]'s most senior role. */
    fun canManageRole(actor: String, role: String, via: String): Boolean {
        if (actor == leader) return true
        val r = roles[role] ?: return false
        return has(actor, via) && r.position < rank(actor)
    }

    /** Whether [actor] may set [perm] to [level] on [role]: able to manage the role, and holding [perm] at that level or higher. */
    fun canGrant(actor: String, role: String, perm: String, level: Level): Boolean =
        actor == leader || (canManageRole(actor, role, MANAGE_ROLES) && level(actor, perm) >= level)

    /** Whether [actor] may change [target]'s roles or remove them: manage_members, and outranking [target]. Nobody so manages the leader or themselves. */
    fun canManageMember(actor: String, target: String): Boolean {
        if (target !in members || target == leader || actor == target) return false
        if (actor == leader) return true
        return has(actor, MANAGE_MEMBERS) && rank(target) < rank(actor)
    }

    /**
     * Whether [actor] may set [role]'s overrides on a layer to [set]: as for
     * [canGrant], for overridable permissions only. Overrides on the base role
     * bind everyone, those ranked above [actor] too, so only the leader sets them.
     */
    fun canSetOverride(actor: String, role: String, set: Map<String, Level>): Boolean {
        if (actor == leader) return true
        if (role == BASE || !canManageRole(actor, role, MANAGE_ROLES)) return false
        return set.all { (perm, level) -> perm in OVERRIDABLE && level(actor, perm) >= level }
    }

    companion object {
        /** The role every member has. */
        const val BASE = "member"

        const val MANAGE_MEMBERS = "manage_members"
        const val MANAGE_ROLES = "manage_roles"
        const val EDIT_ROWS = "edit_rows"
        const val DELETE_ROWS = "delete_rows"

        /** Set per layer only; without an override everyone sees every layer. */
        const val VIEW_LAYER = "view_layer"

        /** The permissions a layer override may set. */
        val OVERRIDABLE = setOf(VIEW_LAYER, EDIT_ROWS, DELETE_ROWS)

        /** Rights every member has, which no setting takes away. */
        val SAFETY = setOf("send_sos", "answer_rollcall", "report_outing", "leave", "share_location")
    }
}
