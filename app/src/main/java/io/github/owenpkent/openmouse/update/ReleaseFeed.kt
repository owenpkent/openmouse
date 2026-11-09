package io.github.owenpkent.openmouse.update

import org.json.JSONException
import org.json.JSONObject

/**
 * One published release as far as the updater cares: the tag, the bare version
 * number, the release notes and the single APK asset to fetch.
 */
data class Release(
    val tag: String,
    val version: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
)

/**
 * Pure helpers for the GitHub Releases feed: parsing the `releases/latest`
 * payload, comparing version numbers and deciding which URLs the downloader may
 * touch. No Android imports, so every rule here is JVM unit-tested
 * (`ReleaseFeedTest`). `org.json` is part of the Android platform; the tests
 * bring their own copy.
 *
 * The allow-lists are the safety core of the updater: an APK is fetched only
 * from this repository's release downloads on `github.com`, and a redirect may
 * only land on GitHub's own hosts. Everything else is refused before a byte is
 * read.
 */
object ReleaseFeed {

    const val REPO = "owenpkent/openmouse"
    const val LATEST_URL = "https://api.github.com/repos/owenpkent/openmouse/releases/latest"

    private const val DOWNLOAD_HOST = "github.com"
    private const val DOWNLOAD_PATH_PREFIX = "/owenpkent/openmouse/releases/download/"

    private val RELEASE_TAG = Regex("^v\\d+\\.\\d+\\.\\d+$")
    private val VERSION = Regex("^\\d+(\\.\\d+)*$")

    /**
     * Parses a `releases/latest` JSON payload. Returns null for anything the
     * updater must not offer: a draft or prerelease, a tag that is not a plain
     * `vX.Y.Z`, a release with no asset named exactly `openmouse-<tag>.apk` (what
     * `scripts/publish-release.ps1` uploads; a debug or otherwise stray APK on the
     * release is never offered), or a body that is not valid JSON at all.
     * Never throws.
     */
    fun parse(json: String): Release? {
        return try {
            val root = JSONObject(json)
            if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return null
            val tag = root.optString("tag_name", "")
            if (!RELEASE_TAG.matches(tag)) return null
            val version = versionOf(tag) ?: return null
            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name != "openmouse-$tag.apk") continue
                val url = asset.optString("browser_download_url", "")
                if (url.isEmpty()) return null
                return Release(
                    tag = tag,
                    version = version,
                    // optString would turn a JSON null into the text "null".
                    notes = if (root.isNull("body")) "" else root.optString("body", ""),
                    apkName = name,
                    apkUrl = url,
                    apkSize = asset.optLong("size", 0L),
                )
            }
            null
        } catch (e: JSONException) {
            null
        }
    }

    /** `v1.2.3` -> `1.2.3`; null unless the remainder is dotted digits. */
    fun versionOf(tag: String): String? {
        val bare = tag.removePrefix("v")
        return if (VERSION.matches(bare)) bare else null
    }

    /**
     * True when [candidate] is strictly newer than [current]. Components are
     * compared as integers (`0.10` is newer than `0.9`), a missing component
     * reads as 0 (`1.0` equals `1.0.0`), and a leading `v` on either side is
     * ignored. Anything that does not parse compares as not newer, so a garbled
     * feed can never produce an update prompt.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = components(candidate) ?: return false
        val b = components(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun components(version: String): List<Int>? {
        val bare = version.trim().removePrefix("v")
        if (bare.isEmpty()) return null
        return bare.split('.').map { part ->
            if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
            part.toIntOrNull() ?: return null
        }
    }

    /**
     * The only URLs the downloader will open directly: https, exactly
     * `github.com`, and inside this repository's release downloads.
     */
    fun isAllowedDownloadUrl(url: String): Boolean {
        val parsed = Url.parse(url) ?: return false
        return parsed.scheme == "https" &&
            parsed.host == DOWNLOAD_HOST &&
            parsed.path.startsWith(DOWNLOAD_PATH_PREFIX)
    }

    /**
     * Hosts a release download may redirect to: GitHub itself or its asset
     * CDN (`objects.githubusercontent.com`, `release-assets.githubusercontent.com`
     * and friends).
     */
    fun isAllowedRedirectHost(host: String): Boolean {
        val h = host.lowercase()
        return h == DOWNLOAD_HOST || h.endsWith(".githubusercontent.com")
    }

    /**
     * A minimal URL splitter for the allow-list checks. `java.net.URL` is
     * avoided here because its `host` parsing is lenient about user-info and
     * backslashes in ways that have bitten other updaters; this one takes the
     * strict reading of `scheme://host[:port]/path` and rejects anything odd.
     */
    internal data class Url(val scheme: String, val host: String, val path: String) {
        companion object {
            fun parse(url: String): Url? {
                val schemeEnd = url.indexOf("://")
                if (schemeEnd <= 0) return null
                val scheme = url.substring(0, schemeEnd).lowercase()
                val rest = url.substring(schemeEnd + 3)
                val pathStart = rest.indexOfAny(charArrayOf('/', '?', '#'))
                val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
                val path = if (pathStart < 0) "/" else rest.substring(pathStart)
                if (authority.isEmpty() || authority.any { it == '@' || it == '\\' || it.isWhitespace() }) return null
                val host = authority.substringBefore(':').lowercase()
                if (host.isEmpty()) return null
                return Url(scheme, host, path)
            }
        }
    }
}
