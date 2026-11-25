package org.geocrew.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An image overlay as the workspace shares it (protocol v0 §2.2, key
 * `overlays`): where the picture lies, and the attachment hash under which
 * the leader uploaded it. Showing it or not is up to each phone.
 */
@Serializable
data class SharedOverlay(
    val id: String,
    val name: String,
    /** SHA-256 of the image, fetched like any attachment. */
    val file: String,
    val type: String,
    val width: Int,
    val height: Int,
    /** [lng, lat] of the top left, top right, bottom right and bottom left corners. */
    val corners: List<List<Double>>,
    val opacity: Float = 0.6f,
    val placement: String = VIEW,
    @SerialName("control_points") val controlPoints: Int = 0,
) {
    /** This phone's copy; [visible] is kept from what the user chose here. */
    fun toOverlay(visible: Boolean = true) = ImageOverlay(
        id = id,
        name = name,
        file = file + if (type == PNG) ".png" else ".jpg",
        width = width,
        height = height,
        corners = corners,
        opacity = opacity.coerceIn(0.1f, 1f),
        visible = visible,
        placement = if (placement == WORLD_FILE) ImageOverlay.Placement.WORLD_FILE else ImageOverlay.Placement.VIEW,
        controlPoints = controlPoints.coerceAtLeast(0),
        shared = true,
    )

    companion object {
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"
        const val VIEW = "view"
        const val WORLD_FILE = "world_file"
        private val HASH = Regex("^[0-9a-f]{64}$")

        /** What a leader publishes for [o]; null when its file name is not a stored image. */
        fun of(o: ImageOverlay): SharedOverlay? {
            if (!ImageOverlay.isFileName(o.file)) return null
            return SharedOverlay(
                id = o.id,
                name = o.name,
                file = o.sha256,
                type = o.mimeType,
                width = o.width,
                height = o.height,
                corners = o.corners,
                opacity = o.opacity,
                placement = if (o.placement == ImageOverlay.Placement.WORLD_FILE) WORLD_FILE else VIEW,
                controlPoints = o.controlPoints,
            )
        }

        /** True when [s] can be drawn: a hash, a known image type, a size and four sane corners. */
        fun valid(s: SharedOverlay): Boolean =
            s.id.isNotBlank() && s.id.length <= 64 && HASH.matches(s.file) && (s.type == JPEG || s.type == PNG) &&
                s.width > 0 && s.height > 0 && s.corners.size == 4 &&
                s.corners.all { c -> c.size == 2 && c[0].isFinite() && c[1].isFinite() && c[0] in -180.0..180.0 && c[1] in -85.0..85.0 }

        /**
         * This phone's overlays after the workspace shared [remote]: its own
         * stay as they are, shared ones follow the workspace (in its order,
         * where the first shared one was) and keep this phone's visibility.
         */
        fun merge(local: List<ImageOverlay>, remote: List<SharedOverlay>): List<ImageOverlay> {
            val shown = local.filter { it.shared }.associate { it.id to it.visible }
            val incoming = remote.filter(::valid).distinctBy { it.id }
                .filter { r -> local.none { !it.shared && it.id == r.id } } // never replace one of this phone's own
                .map { it.toOverlay(shown[it.id] ?: true) }
            val at = local.indexOfFirst { it.shared }.let { if (it < 0) local.size else local.subList(0, it).count { o -> !o.shared } }
            val own = local.filter { !it.shared }
            return own.subList(0, at) + incoming + own.subList(at, own.size)
        }

        /** What a leader's phone shares: its shared overlays, in drawing order. */
        fun published(local: List<ImageOverlay>): List<SharedOverlay> = local.filter { it.shared }.mapNotNull(::of)
    }
}
