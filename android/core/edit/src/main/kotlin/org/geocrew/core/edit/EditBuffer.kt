package org.geocrew.core.edit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

private val NO_VALUES = JsonObject(emptyMap())

/**
 * The unsaved state of one feature: a new feature ([added], with a negative
 * fid), or a stored one that is [deleted] or has a new geometry
 * ([geometryChanged]) and new [values]; either with its [attachments] when
 * they changed (a new feature's, when it has any). The empty slot means
 * nothing unsaved.
 */
@Serializable
data class Slot(
    val added: Feature? = null,
    val deleted: Boolean = false,
    val geometryChanged: Boolean = false,
    val geometry: JsonObject? = null,
    val values: JsonObject = NO_VALUES,
    val attachments: List<Attachment>? = null,
) {
    val isEmpty: Boolean get() = this == EMPTY

    /** Stored feature [f] as this slot changes it. */
    internal fun over(f: Feature): Feature = f.copy(
        geometry = if (geometryChanged) geometry else f.geometry,
        properties = if (values.isEmpty()) f.properties else JsonObject(f.properties + values),
    )

    companion object {
        val EMPTY = Slot()
    }
}

/** One change: feature [fid] went from [before] to [after]. Undo puts [before] back. */
data class Edit(val fid: Long, val before: Slot, val after: Slot)

/** A line of the journal. */
@Serializable
sealed interface JournalEntry {
    /** Feature [fid]'s slot became [slot]. */
    @Serializable
    @SerialName("set")
    data class Set(val fid: Long, val slot: Slot) : JournalEntry

    /** A save began while the store's highest fid was [lastFid]. */
    @Serializable
    @SerialName("saving")
    data class Saving(val lastFid: Long) : JournalEntry

    /** That save failed; the entries before and after this one tell what is unsaved. */
    @Serializable
    @SerialName("failed")
    data object Failed : JournalEntry
}

/**
 * Keeps a layer's unsaved edits on disk so that they outlive a crash
 * (ADR 0005). Each call must have reached the disk when it returns, and
 * [rewrite] must replace the contents in one step.
 */
interface Journal {
    fun append(entry: JournalEntry)

    fun entries(): List<JournalEntry>

    fun rewrite(entries: List<JournalEntry>)

    fun clear() = rewrite(emptyList())
}

/** A journal in memory only: for tests, and for layers nothing needs to outlive a crash. */
class MemoryJournal : Journal {
    private var list = listOf<JournalEntry>()

    override fun append(entry: JournalEntry) {
        list = list + entry
    }

    override fun entries(): List<JournalEntry> = list

    override fun rewrite(entries: List<JournalEntry>) {
        list = entries.toList()
    }
}

/** What [EditBuffer.recover] found. */
enum class Recovery {
    /** Nothing was left unsaved. */
    NOTHING,

    /** Unsaved edits are back in the buffer. */
    RESTORED,

    /** The app stopped during a save that did go through; nothing is left unsaved. */
    SAVED,
}

/**
 * A layer's unsaved edits, the QGIS way (ADR 0005): changes wait here until
 * [save] writes them to the [store] or [discard] drops them, and reads see the
 * layer as edited. New features have negative fids until they are saved.
 *
 * Each change reaches the [journal] before the buffer, so [recover] can bring
 * unsaved edits back after a crash. The undo and redo stacks belong to the
 * action registry (ADR 0010): each change returns an [Edit], which [revert]
 * takes back and [reapply] makes again.
 *
 * Not thread-safe; the action registry runs one action at a time.
 */
class EditBuffer(private val store: LayerStore, private val journal: Journal) {
    private val slots = LinkedHashMap<Long, Slot>()
    private var nextNew = -1L
    private var stale = false // the journal still holds edits a save wrote

    /** Whether there is anything to save. */
    val isModified: Boolean get() = slots.isNotEmpty()

    /** Feature [fid] as edited; null when there is none or it is deleted. */
    fun feature(fid: Long): Feature? {
        val slot = slot(fid)
        if (fid < 0) return slot.added
        if (slot.deleted) return null
        return store.feature(fid)?.let(slot::over)
    }

    /** Every feature as edited: the stored ones, then the new ones in the order they were added. */
    fun features(): List<Feature> {
        val stored = store.features().mapNotNull { f ->
            val slot = slots[f.fid]
            when {
                slot == null -> f
                slot.deleted -> null
                else -> slot.over(f)
            }
        }
        return stored + newOnes().mapNotNull { slots[it]?.added }
    }

    /** Adds a new feature, with [attachments] if any; fields left out of [properties] are null. */
    fun add(geometry: JsonObject?, properties: JsonObject = NO_VALUES, attachments: List<Attachment> = emptyList()): Edit {
        require(store.capabilities.add) { "this layer does not take new features" }
        checkValues(properties)
        checkAttachments(attachments)
        val fid = nextNew--
        val all = JsonObject(store.fields.associate { it.name to (properties[it.name] ?: JsonNull) })
        return change(fid, Slot(added = Feature(fid, geometry, all), attachments = attachments.ifEmpty { null }))
    }

    /** Feature [fid]'s attachments as edited. */
    fun attachments(fid: Long): List<Attachment> {
        val slot = slot(fid)
        slot.attachments?.let { return it }
        return if (fid >= 0 && !slot.deleted) store.attachments(fid) else emptyList()
    }

    /** Gives feature [fid] [attachments] in place of the ones it has, as one change. */
    fun changeAttachments(fid: Long, attachments: List<Attachment>): Edit {
        checkAttachments(attachments)
        val before = existing(fid)
        return change(fid, before.copy(attachments = attachments))
    }

    /** Gives feature [fid] a new geometry, or none. */
    fun changeGeometry(fid: Long, geometry: JsonObject?): Edit {
        val before = existing(fid)
        val added = before.added
        val after = if (added != null) {
            before.copy(added = added.copy(geometry = geometry))
        } else {
            before.copy(geometryChanged = true, geometry = geometry)
        }
        return change(fid, after)
    }

    /** Sets the fields named in [values] on feature [fid]. */
    fun changeValues(fid: Long, values: JsonObject): Edit {
        checkValues(values)
        val before = existing(fid)
        val added = before.added
        val after = if (added != null) {
            before.copy(added = added.copy(properties = JsonObject(added.properties + values)))
        } else {
            before.copy(values = JsonObject(before.values + values))
        }
        return change(fid, after)
    }

    /** Deletes feature [fid]; a new one simply goes away. */
    fun delete(fid: Long): Edit {
        existing(fid, allowed = store.capabilities.delete, what = "deleting")
        return change(fid, if (fid < 0) Slot.EMPTY else Slot(deleted = true))
    }

    /** Takes [edit] back. It must be the latest change to its feature that is still in effect. */
    fun revert(edit: Edit) {
        check(slot(edit.fid) == edit.after) { "feature ${edit.fid} changed after this edit" }
        set(edit.fid, edit.before)
    }

    /** Makes [edit] again after [revert]. */
    fun reapply(edit: Edit) {
        check(slot(edit.fid) == edit.before) { "feature ${edit.fid} changed since this edit was undone" }
        set(edit.fid, edit.after)
    }

    /**
     * Writes the edits to the store the way QGIS orders them: changed features,
     * then deletions, then new features. With transactions this is one
     * transaction, so a failure writes nothing and keeps every edit; without,
     * what was written leaves the buffer and the rest stays. Edits to stored
     * features that have since gone are dropped. Returns the fid the store gave
     * each new feature.
     */
    fun save(): Map<Long, Long> {
        for (fid in slots.keys.filter { it >= 0 && store.feature(it) == null }) set(fid, Slot.EMPTY)
        if (slots.isEmpty()) return emptyMap()
        journal.append(JournalEntry.Saving(store.lastFid()))
        val transaction = store.capabilities.transactions
        val written = mutableListOf<Long>()
        val ids = LinkedHashMap<Long, Long>()
        try {
            if (transaction) store.begin()
            for ((fid, slot) in slots) {
                if (fid < 0 || slot.deleted) continue
                if (slot.values.isNotEmpty() || slot.geometryChanged) store.update(fid, slot.values, slot.geometryChanged, slot.geometry)
                slot.attachments?.let { store.setAttachments(fid, it) }
                written += fid
            }
            for ((fid, slot) in slots) {
                if (!slot.deleted) continue
                store.setAttachments(fid, emptyList()) // a deleted feature's attachments go with it
                store.delete(fid)
                written += fid
            }
            for (fid in newOnes()) {
                val slot = slots.getValue(fid)
                val f = slot.added ?: continue
                val stored = store.add(f.geometry, f.properties)
                ids[fid] = stored
                slot.attachments?.takeIf { it.isNotEmpty() }?.let { store.setAttachments(stored, it) }
                written += fid
            }
            if (transaction) store.commit()
        } catch (e: Exception) {
            runCatching {
                if (transaction) store.rollback() else for (fid in written) set(fid, Slot.EMPTY)
            }.exceptionOrNull()?.let(e::addSuppressed)
            runCatching { journal.append(JournalEntry.Failed) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        slots.clear()
        // The edits are saved even if the journal cannot be cleared now. Its
        // "saving" mark lets a recovery see that; the next change clears it first.
        stale = runCatching { journal.clear() }.isFailure
        return ids
    }

    /** Drops every unsaved edit. */
    fun discard() {
        journal.clear()
        stale = false
        slots.clear()
    }

    /**
     * Brings back the unsaved edits the journal kept from before a crash; their
     * undo history is not kept. When the app stopped during a save, what the
     * store has decides what was saved: new fids above the journal's mark, and
     * deleted features gone. That holds while no other program writes the layer,
     * as for synced projects (ADR 0004).
     */
    fun recover(): Recovery {
        check(slots.isEmpty()) { "recover before editing" }
        var saving: JournalEntry.Saving? = null
        for (entry in journal.entries()) {
            when (entry) {
                is JournalEntry.Set -> {
                    if (entry.slot.isEmpty) slots.remove(entry.fid) else slots[entry.fid] = entry.slot
                    if (entry.fid <= nextNew) nextNew = entry.fid - 1
                }
                is JournalEntry.Saving -> saving = entry
                JournalEntry.Failed -> saving = null
            }
        }
        if (saving != null) settleInterruptedSave(saving.lastFid)
        journal.rewrite(slots.map { (fid, slot) -> JournalEntry.Set(fid, slot) })
        return when {
            slots.isNotEmpty() -> Recovery.RESTORED
            saving != null -> Recovery.SAVED
            else -> Recovery.NOTHING
        }
    }

    // A save writes changed features, then deletions, then new features in
    // order, so the new fids the store has show how far it got.
    private fun settleInterruptedSave(lastFid: Long) {
        val addsWritten = (store.lastFid() - lastFid).coerceAtLeast(0)
        val deletedGone = slots.filter { it.value.deleted }.keys.filter { store.feature(it) == null }
        if (store.capabilities.transactions) {
            // It committed. A save of changed features only cannot be told from one
            // that did not; those stay, and saving them again writes the same values.
            if (addsWritten > 0 || deletedGone.isNotEmpty()) slots.clear()
            return
        }
        if (addsWritten > 0) slots.keys.removeAll { it >= 0 } else slots.keys.removeAll(deletedGone.toSet())
        for (fid in newOnes().take(addsWritten.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())) slots.remove(fid)
    }

    private fun slot(fid: Long): Slot = slots[fid] ?: Slot.EMPTY

    /** The new features' fids, in the order they were added. */
    private fun newOnes(): List<Long> = slots.keys.filter { it < 0 }.sortedDescending()

    /** The slot of a feature that exists as edited; for a stored one the store must also allow the change. */
    private fun existing(fid: Long, allowed: Boolean = store.capabilities.update, what: String = "changing"): Slot {
        val slot = slot(fid)
        val exists = if (fid < 0) slot.added != null else !slot.deleted && store.feature(fid) != null
        require(exists) { "there is no feature $fid" }
        require(fid < 0 || allowed) { "this layer does not allow $what its features" }
        return slot
    }

    // The fields as the store has them now: a field's range can change while its layer is being edited.
    private fun checkValues(values: JsonObject) {
        val fields = store.fields.associateBy { it.name }
        for ((name, value) in values) {
            val field = requireNotNull(fields[name]) { "there is no field $name" }
            require(field.type.accepts(value)) { "field $name (${field.type.name.lowercase()}) does not take $value" }
            val range = field.range
            require(range == null || range.accepts(value)) { "field $name takes numbers ${range?.describe()}, not $value" }
        }
    }

    private fun checkAttachments(attachments: List<Attachment>) {
        require(attachments.map { it.id }.toSet().size == attachments.size) { "an attachment is on a feature once" }
    }

    private fun change(fid: Long, after: Slot): Edit = Edit(fid, slot(fid), after).also { set(fid, after) }

    // The journal first: a crash after it loses nothing.
    private fun set(fid: Long, slot: Slot) {
        if (stale) {
            journal.clear()
            stale = false
        }
        journal.append(JournalEntry.Set(fid, slot))
        if (slot.isEmpty) slots.remove(fid) else slots[fid] = slot
    }
}
