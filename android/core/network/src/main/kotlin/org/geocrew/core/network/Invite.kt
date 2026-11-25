package org.geocrew.core.network

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * An invitation to a workspace: the server's root address and an invite
 * code. Encoded as `geocrew://join?server=…&code=…` (protocol v0 §2), which
 * is what invite QR codes and shared links carry.
 */
data class Invite(val server: String, val code: String) {
    fun toUri(): String = "$SCHEME://$HOST?server=${enc(server)}&code=${enc(code)}"

    companion object {
        const val SCHEME = "geocrew"
        const val HOST = "join"

        private val CODE = Regex("^[A-Z0-9]{4,32}$")
        private val LINK = Regex("""geocrew://join\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        private val SERVER_LINE = Regex("""(?:服务器|server)\s*[:：]\s*(\S+)""", RegexOption.IGNORE_CASE)
        private val CODE_LINE = Regex("""(?:邀请码|invite(?:\s*code)?)\s*[:：]\s*([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

        /** Normalizes and validates; returns null for anything that is not a usable invite. */
        fun of(server: String?, code: String?): Invite? {
            val c = code?.trim()?.uppercase()?.takeIf { CODE.matches(it) } ?: return null
            val s = server?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val url = runCatching { GeoCrewApi.normalize(s) }.getOrNull() ?: return null
            if (!url.isHttps) return null
            return Invite(url.toString().removeSuffix("/"), c)
        }

        /** Parses a `geocrew://join` URI. */
        fun fromUri(uri: String): Invite? {
            val u = runCatching { URI(uri.trim()) }.getOrNull() ?: return null
            if (!u.scheme.equals(SCHEME, true) || !u.host.equals(HOST, true)) return null
            val q = u.rawQuery?.split('&').orEmpty().mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else dec(it.substring(0, i)) to dec(it.substring(i + 1))
            }.toMap()
            return of(q["server"], q["code"])
        }

        /**
         * Finds an invite in free text, e.g. a message pasted from a chat app:
         * either a `geocrew://join` link or "服务器：… / 邀请码：…" lines.
         */
        fun find(text: String): Invite? {
            LINK.find(text)?.let { m -> fromUri(m.value)?.let { return it } }
            val server = SERVER_LINE.find(text)?.groupValues?.get(1)
            val code = CODE_LINE.find(text)?.groupValues?.get(1)
            return of(server, code)
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
