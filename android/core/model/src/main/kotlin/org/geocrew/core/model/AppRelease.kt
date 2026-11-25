package org.geocrew.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * One published app version, read from a GitHub-style release feed
 * (`GET /repos/{owner}/{repo}/releases/latest`). Its assets include the
 * signed APK named by [AppReleases.apkName] and a `SHA256SUMS` listing.
 */
data class AppRelease(
    /** Without a leading v, e.g. "0.3.0". */
    val version: String,
    val tag: String,
    val name: String = "",
    val publishedAt: Instant? = null,
    /** Release notes as written (Markdown). */
    val notes: String = "",
    /** The release's web page. */
    val url: String = "",
    /** Asset file name to download address. */
    val assets: Map<String, String> = emptyMap(),
) {
    val apk: String? get() = assets[AppReleases.apkName(version)]
    val sums: String? get() = assets[AppReleases.SUMS]
}

object AppReleases {
    const val SUMS = "SHA256SUMS"

    /** The signed APK of [version]: geocrew-android-0.3.0.apk. */
    fun apkName(version: String) = "geocrew-android-$version.apk"

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads a release feed's answer; null for a draft or an answer without a tag. */
    fun parse(text: String): AppRelease? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        fun s(obj: JsonObject, k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        if ((o["draft"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true) return null
        val tag = s(o, "tag_name")?.takeIf { it.isNotBlank() } ?: return null
        val assets = runCatching { o["assets"]?.jsonArray }.getOrNull().orEmpty().mapNotNull { a ->
            val obj = a as? JsonObject ?: return@mapNotNull null
            val n = s(obj, "name") ?: return@mapNotNull null
            val u = s(obj, "browser_download_url") ?: return@mapNotNull null
            n to u
        }.toMap()
        return AppRelease(
            version = tag.removePrefix("v"),
            tag = tag,
            name = s(o, "name").orEmpty(),
            publishedAt = s(o, "published_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            notes = s(o, "body").orEmpty().replace("\r\n", "\n").trim(),
            url = s(o, "html_url").orEmpty(),
            assets = assets,
        )
    }

    /** Finds [name]'s lower-case hash in a SHA256SUMS listing. */
    fun sumFor(sums: String, name: String): String? = sums.lineSequence()
        .map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }
        ?.get(0)?.lowercase()
        ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private data class Ver(val n: List<Int>, val pre: String)

    private fun parseVersion(v: String): Ver? {
        var s = v.trim().removePrefix("v").substringBefore('+')
        val pre = if ('-' in s) s.substringAfter('-').also { s = s.substringBefore('-') } else ""
        val parts = s.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val n = parts.map { it.toIntOrNull()?.takeIf { x -> x >= 0 } ?: return null }
        return Ver(n + List(3 - n.size) { 0 }, pre)
    }

    /** Orders versions like 1.2.3 and 1.2.3-rc.1; a pre-release sorts before its release, an unparsable one before all. */
    fun compare(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        if (pa == null || pb == null) return compareValues(pa != null, pb != null)
        for (i in 0 until 3) if (pa.n[i] != pb.n[i]) return pa.n[i].compareTo(pb.n[i])
        return when {
            pa.pre == pb.pre -> 0
            pa.pre.isEmpty() -> 1
            pb.pre.isEmpty() -> -1
            else -> pa.pre.compareTo(pb.pre).coerceIn(-1, 1)
        }
    }

    /** Whether [latest] should be offered over [current]; a development build is never offered one. */
    fun newer(latest: String, current: String): Boolean =
        parseVersion(current) != null && compare(latest, current) > 0

    /**
     * The versionCode a release build of [version] gets, so a newer version
     * always installs over an older one: 1.2.3 → 1020399, 1.2.3-rc.2 → 1020302.
     * Kept in step with app/build.gradle.kts.
     */
    fun versionCode(version: String): Int? {
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z]+\.?(\d+)?)?$""").matchEntire(version) ?: return null
        val (maj, min, pat) = m.destructured
        val pre = when {
            '-' !in version -> 99
            else -> (m.groupValues[4].toIntOrNull() ?: 0).coerceIn(0, 98)
        }
        return ((maj.toInt() * 100 + min.toInt()) * 100 + pat.toInt()) * 100 + pre
    }

    /** Release notes trimmed for a dialog: no link to the full diff, at most [maxLines] lines. */
    fun shortNotes(notes: String, maxLines: Int = 30): String {
        val lines = notes.lines().filterNot { it.startsWith("完整改动") || it.startsWith("**Full Changelog**") }
            .dropLastWhile { it.isBlank() }
        val kept = lines.take(maxLines).joinToString("\n") { it.replace(Regex("^#+\\s*"), "").replace("**", "") }
        return if (lines.size > maxLines) "$kept\n…" else kept
    }
}
