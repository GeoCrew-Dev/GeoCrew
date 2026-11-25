package org.geocrew.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A reference layer as the workspace shares it (protocol v0 §2.2, key
 * `reference_layers`): its name, how it is coloured, and the attachment
 * hash of its shapes as a GeoJSON FeatureCollection. Whether to show it is
 * up to each phone.
 */
@Serializable
data class SharedReference(
    val id: String,
    val name: String,
    /** SHA-256 of the GeoJSON file, fetched like any attachment. */
    val file: String,
    @SerialName("color_by") val colorBy: String? = null,
    val count: Int = 0,
    val opacity: Float = 0.45f,
) {
    /** This phone's copy; [visible] is kept from what the user chose here. */
    fun toLayer(visible: Boolean = true) = ReferenceLayer(
        id = id,
        name = name.take(60),
        colorBy = colorBy?.takeIf { it.isNotBlank() }?.take(60),
        visible = visible,
        opacity = opacity.coerceIn(0.1f, 1f),
        count = count.coerceAtLeast(0),
        shared = true,
        file = file,
    )

    companion object {
        const val TYPE = "application/geo+json"
        private val HASH = Regex("^[0-9a-f]{64}$")
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")

        fun valid(s: SharedReference): Boolean = ID.matches(s.id) && HASH.matches(s.file) && s.name.isNotBlank()

        /** What a leader publishes for [l]; null until its file hash is known. */
        fun of(l: ReferenceLayer): SharedReference? {
            val f = l.file?.takeIf { HASH.matches(it) } ?: return null
            return SharedReference(l.id, l.name, f, l.colorBy, l.count, l.opacity)
        }

        /** What a leader's phone shares: its shared layers, in drawing order. */
        fun published(local: List<ReferenceLayer>): List<SharedReference> = local.filter { it.shared }.mapNotNull(::of)

        /**
         * This phone's layers after the workspace shared [remote]: its own
         * stay as they are and under the shared ones, which follow the
         * workspace and keep this phone's choice of showing them.
         */
        fun merge(local: List<ReferenceLayer>, remote: List<SharedReference>): List<ReferenceLayer> {
            val shown = local.filter { it.shared }.associate { it.id to it.visible }
            val own = local.filter { !it.shared }
            val incoming = remote.filter(::valid).distinctBy { it.id }
                .filter { r -> own.none { it.id == r.id } } // never replace one of this phone's own
                .map { it.toLayer(shown[it.id] ?: true) }
            return own + incoming
        }
    }
}
