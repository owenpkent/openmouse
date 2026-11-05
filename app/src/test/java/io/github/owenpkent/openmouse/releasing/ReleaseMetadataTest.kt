package io.github.owenpkent.openmouse.releasing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the places a release version is written to each other, so a bump that
 * forgets one fails here and not at release time (the Polaris version test, for
 * the files this project has):
 *
 * - `versionName` and `versionCode` in app/build.gradle.kts,
 * - a `## [versionName]` entry in CHANGELOG.md,
 * - fastlane/metadata/android/en-US/changelogs/<versionCode>.txt (F-Droid and
 *   Play read it; Play caps it at 500 characters),
 * - the F-Droid metadata's CurrentVersion, CurrentVersionCode and build entry,
 * - at least one key in release-signers, or no tag can verify.
 *
 * Gradle runs unit tests with the module directory as the working directory, so
 * the repository root is found by walking up to CHANGELOG.md.
 */
class ReleaseMetadataTest {

    private val root = findRoot()
    private val gradle = root.resolve("app/build.gradle.kts").readText()
    private val versionName = Regex("""versionName\s*=\s*"([^"]+)"""").find(gradle)!!.groupValues[1]
    private val versionCode = Regex("""versionCode\s*=\s*(\d+)""").find(gradle)!!.groupValues[1].toInt()

    @Test
    fun versionNameIsPlainMajorMinorPatch() {
        // A prerelease or build suffix is not a version: F-Droid's "Version v%v"
        // update check and the release tag both want vX.Y.Z.
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(versionName), "versionName '$versionName' is not X.Y.Z")
        assertTrue(versionCode >= 1)
    }

    @Test
    fun changelogHasAnEntryForTheCurrentVersion() {
        val changelog = root.resolve("CHANGELOG.md").readText()
        assertTrue(
            changelog.lines().any { it.startsWith("## [$versionName]") },
            "CHANGELOG.md has no '## [$versionName]' entry for versionName $versionName",
        )
    }

    @Test
    fun fastlaneChangelogExistsForTheCurrentVersionCode() {
        val file = root.resolve("fastlane/metadata/android/en-US/changelogs/$versionCode.txt")
        assertTrue(file.isFile, "missing ${file.relativeTo(root)} for versionCode $versionCode")
        val text = file.readText().trim()
        assertTrue(text.isNotEmpty(), "${file.relativeTo(root)} is empty")
        assertTrue(text.length <= 500, "${file.relativeTo(root)} is ${text.length} characters; Play caps it at 500")
    }

    @Test
    fun fdroidMetadataNamesTheCurrentVersion() {
        val yml = root.resolve("docs/fdroid/io.github.owenpkent.openmouse.yml").readText()
        fun value(key: String) = Regex("""^$key:\s*(\S+)\s*$""", RegexOption.MULTILINE).find(yml)?.groupValues?.get(1)
        assertEquals(versionName, value("CurrentVersion"), "F-Droid CurrentVersion")
        assertEquals(versionCode.toString(), value("CurrentVersionCode"), "F-Droid CurrentVersionCode")
        assertTrue(
            Regex("""- versionName: ${Regex.escape(versionName)}\s*\n\s*versionCode: $versionCode\s*\n\s*commit: v${Regex.escape(versionName)}\b""").containsMatchIn(yml),
            "F-Droid Builds has no entry for versionName $versionName, versionCode $versionCode, commit v$versionName",
        )
    }

    @Test
    fun releaseSignersHasAtLeastOneKey() {
        val keys = root.resolve("release-signers").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(keys.isNotEmpty(), "release-signers has no key: no release tag could verify")
        keys.forEach { line ->
            assertTrue(
                Regex("""^\S+ namespaces="git" (ssh-ed25519|ssh-rsa|ecdsa-sha2-\S+|sk-\S+) \S+""").containsMatchIn(line),
                "release-signers line is not '<principal> namespaces=\"git\" <keytype> <key>': $line",
            )
        }
    }

    private companion object {
        fun findRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "CHANGELOG.md").isFile && File(dir, "app/build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("repository root (CHANGELOG.md next to app/) not found above ${System.getProperty("user.dir")}")
        }
    }
}
