package io.github.owenpkent.openmouse.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure release-feed helpers. The payloads are trimmed copies
 * of what `GET /repos/{owner}/{repo}/releases/latest` really returns, with the
 * fields the parser does not read left in so a stray extra key cannot trip it.
 */
class ReleaseFeedTest {

    private val downloadBase = "https://github.com/owenpkent/openmouse/releases/download"

    private fun payload(
        tag: String = "v0.2.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String? = "## Changes\\n\\n- Faster scrolling",
        assets: String = defaultAssets,
    ): String {
        val bodyJson = if (body == null) "null" else "\"$body\""
        return """
            {
              "url": "https://api.github.com/repos/owenpkent/openmouse/releases/1",
              "id": 1,
              "tag_name": "$tag",
              "target_commitish": "main",
              "name": "OpenMouse $tag",
              "draft": $draft,
              "prerelease": $prerelease,
              "created_at": "2026-11-01T10:00:00Z",
              "published_at": "2026-11-01T10:05:00Z",
              "author": { "login": "owenpkent", "id": 1 },
              "assets": [ $assets ],
              "body": $bodyJson
            }
        """.trimIndent()
    }

    private val defaultAssets = """
        {
          "name": "openmouse-0.2.0.apk.sha256",
          "content_type": "text/plain",
          "size": 95,
          "browser_download_url": "$downloadBase/v0.2.0/openmouse-0.2.0.apk.sha256"
        },
        {
          "name": "openmouse-0.2.0.apk",
          "content_type": "application/vnd.android.package-archive",
          "size": 2345678,
          "browser_download_url": "$downloadBase/v0.2.0/openmouse-0.2.0.apk"
        },
        {
          "name": "openmouse-0.2.0-debug.apk",
          "content_type": "application/vnd.android.package-archive",
          "size": 3456789,
          "browser_download_url": "$downloadBase/v0.2.0/openmouse-0.2.0-debug.apk"
        }
    """.trimIndent()

    @Nested
    inner class Parse {

        @Test
        fun picksTheFirstOpenmouseApkAmongSeveralAssets() {
            val release = ReleaseFeed.parse(payload())
            assertNotNull(release)
            release!!
            assertEquals("v0.2.0", release.tag)
            assertEquals("0.2.0", release.version)
            assertEquals("openmouse-0.2.0.apk", release.apkName)
            assertEquals("$downloadBase/v0.2.0/openmouse-0.2.0.apk", release.apkUrl)
            assertEquals(2_345_678L, release.apkSize)
            assertTrue(release.notes.startsWith("## Changes"))
        }

        @Test
        fun nullBodyReadsAsEmptyNotes() {
            val release = ReleaseFeed.parse(payload(body = null))
            assertEquals("", release?.notes)
        }

        @Test
        fun draftsAndPrereleasesAreRejected() {
            assertNull(ReleaseFeed.parse(payload(draft = true)))
            assertNull(ReleaseFeed.parse(payload(prerelease = true)))
        }

        @Test
        fun releaseWithoutAnApkIsRejected() {
            val sha = """
                {
                  "name": "openmouse-0.2.0.apk.sha256",
                  "size": 95,
                  "browser_download_url": "$downloadBase/v0.2.0/openmouse-0.2.0.apk.sha256"
                }
            """.trimIndent()
            assertNull(ReleaseFeed.parse(payload(assets = sha)))
            assertNull(ReleaseFeed.parse(payload(assets = "")))
        }

        @Test
        fun apkNotNamedOpenmouseIsRejected() {
            val other = """
                { "name": "app-release.apk", "size": 10, "browser_download_url": "$downloadBase/v0.2.0/app-release.apk" }
            """.trimIndent()
            assertNull(ReleaseFeed.parse(payload(assets = other)))
        }

        @Test
        fun malformedTagIsRejected() {
            assertNull(ReleaseFeed.parse(payload(tag = "0.2.0")))
            assertNull(ReleaseFeed.parse(payload(tag = "v0.2")))
            assertNull(ReleaseFeed.parse(payload(tag = "v0.2.0-rc1")))
            assertNull(ReleaseFeed.parse(payload(tag = "nightly")))
        }

        @Test
        fun garbageIsRejectedWithoutThrowing() {
            assertNull(ReleaseFeed.parse(""))
            assertNull(ReleaseFeed.parse("<html>rate limited</html>"))
            assertNull(ReleaseFeed.parse("{ \"tag_name\": "))
            assertNull(ReleaseFeed.parse("[1, 2, 3]"))
            assertNull(ReleaseFeed.parse("{}"))
        }
    }

    @Nested
    inner class IsNewer {

        @Test
        fun comparesComponentsAsIntegersNotStrings() {
            assertTrue(ReleaseFeed.isNewer("0.10", "0.9"))
            assertTrue(ReleaseFeed.isNewer("0.10.0", "0.9.9"))
            assertFalse(ReleaseFeed.isNewer("0.9", "0.10"))
        }

        @Test
        fun equalIsNotNewer() {
            assertFalse(ReleaseFeed.isNewer("0.1.0", "0.1.0"))
            assertFalse(ReleaseFeed.isNewer("1.0", "1.0.0"))
            assertFalse(ReleaseFeed.isNewer("1.0.0", "1.0"))
        }

        @Test
        fun olderIsNotNewer() {
            assertFalse(ReleaseFeed.isNewer("0.1.0", "0.2.0"))
            assertFalse(ReleaseFeed.isNewer("0.1.9", "1.0.0"))
        }

        @Test
        fun nextMinorIsNewer() {
            assertTrue(ReleaseFeed.isNewer("0.2.0", "0.1.0"))
            assertTrue(ReleaseFeed.isNewer("0.1.1", "0.1.0"))
            assertTrue(ReleaseFeed.isNewer("1.0.0", "0.9.9"))
            assertTrue(ReleaseFeed.isNewer("1.0.1", "1.0"))
        }

        @Test
        fun leadingVIsToleratedOnEitherSide() {
            assertTrue(ReleaseFeed.isNewer("v0.2.0", "0.1.0"))
            assertTrue(ReleaseFeed.isNewer("0.2.0", "v0.1.0"))
            assertFalse(ReleaseFeed.isNewer("v0.1.0", "v0.1.0"))
        }

        @Test
        fun malformedInputIsNeverNewer() {
            assertFalse(ReleaseFeed.isNewer("", "0.1.0"))
            assertFalse(ReleaseFeed.isNewer("0.2.0", ""))
            assertFalse(ReleaseFeed.isNewer("0.2.0-rc1", "0.1.0"))
            assertFalse(ReleaseFeed.isNewer("0..2", "0.1.0"))
            assertFalse(ReleaseFeed.isNewer("latest", "0.1.0"))
            assertFalse(ReleaseFeed.isNewer("0.2.0", "0.1.0-debug"))
            assertFalse(ReleaseFeed.isNewer("99999999999999999999", "0.1.0"))
        }
    }

    @Nested
    inner class VersionOf {

        @Test
        fun stripsOneLeadingV() {
            assertEquals("0.1.0", ReleaseFeed.versionOf("v0.1.0"))
            assertEquals("0.1.0", ReleaseFeed.versionOf("0.1.0"))
            assertEquals("12", ReleaseFeed.versionOf("v12"))
        }

        @Test
        fun rejectsAnythingThatIsNotDottedDigits() {
            assertNull(ReleaseFeed.versionOf("vv0.1.0"))
            assertNull(ReleaseFeed.versionOf("v0.1.0-rc1"))
            assertNull(ReleaseFeed.versionOf("v"))
            assertNull(ReleaseFeed.versionOf(""))
            assertNull(ReleaseFeed.versionOf("v0.1."))
        }
    }

    @Nested
    inner class DownloadUrl {

        @Test
        fun acceptsThisRepositoryReleaseDownloads() {
            assertTrue(ReleaseFeed.isAllowedDownloadUrl("$downloadBase/v0.2.0/openmouse-0.2.0.apk"))
            assertTrue(ReleaseFeed.isAllowedDownloadUrl("HTTPS://GitHub.com/owenpkent/openmouse/releases/download/v0.2.0/x.apk"))
        }

        @Test
        fun rejectsPlainHttp() {
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("http://github.com/owenpkent/openmouse/releases/download/v0.2.0/x.apk"))
        }

        @Test
        fun rejectsOtherHostsAndLookalikes() {
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("https://evil.com/github.com/owenpkent/openmouse/releases/download/v0.2.0/x.apk"))
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("https://github.com.evil.com/owenpkent/openmouse/releases/download/v0.2.0/x.apk"))
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("https://github.com@evil.com/owenpkent/openmouse/releases/download/v0.2.0/x.apk"))
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("https://objects.githubusercontent.com/owenpkent/openmouse/releases/download/v0.2.0/x.apk"))
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("https://api.github.com/owenpkent/openmouse/releases/download/v0.2.0/x.apk"))
        }

        @Test
        fun rejectsOtherRepositoriesAndPaths() {
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("https://github.com/someone/openmouse/releases/download/v0.2.0/x.apk"))
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("https://github.com/owenpkent/other/releases/download/v0.2.0/x.apk"))
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("https://github.com/owenpkent/openmouse/archive/refs/tags/v0.2.0.zip"))
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("https://github.com/owenpkent/openmouse"))
            assertFalse(ReleaseFeed.isAllowedDownloadUrl(""))
            assertFalse(ReleaseFeed.isAllowedDownloadUrl("not a url"))
        }
    }

    @Nested
    inner class RedirectHost {

        @Test
        fun acceptsGitHubAndItsAssetHosts() {
            assertTrue(ReleaseFeed.isAllowedRedirectHost("github.com"))
            assertTrue(ReleaseFeed.isAllowedRedirectHost("objects.githubusercontent.com"))
            assertTrue(ReleaseFeed.isAllowedRedirectHost("release-assets.githubusercontent.com"))
            assertTrue(ReleaseFeed.isAllowedRedirectHost("GitHub.com"))
        }

        @Test
        fun rejectsEverythingElse() {
            assertFalse(ReleaseFeed.isAllowedRedirectHost("evil.com"))
            assertFalse(ReleaseFeed.isAllowedRedirectHost("githubusercontent.com"))
            assertFalse(ReleaseFeed.isAllowedRedirectHost("github.com.evil.com"))
            assertFalse(ReleaseFeed.isAllowedRedirectHost("evilgithubusercontent.com"))
            assertFalse(ReleaseFeed.isAllowedRedirectHost("www.github.com"))
            assertFalse(ReleaseFeed.isAllowedRedirectHost(""))
        }
    }
}
