package io.github.owenpkent.openmouse.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.owenpkent.openmouse.BuildConfig
import io.github.owenpkent.openmouse.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * The Android half of the updater: talks to GitHub, writes the APK to the cache,
 * checks its signing certificate against our own and hands it to the system
 * installer. The decisions (what counts as a release, which URLs are allowed,
 * which version is newer) live in the pure [ReleaseFeed].
 *
 * Safety rules, in the order they apply:
 * - Nothing here runs except when the user presses "Check for updates now", or
 *   once per process when the app opens if the launch check is on (the default,
 *   see [LaunchCheck]; the user can turn it off). There is no background job.
 * - The release list comes from `api.github.com` and the APK only from
 *   `github.com/owenpkent/openmouse/releases/download/`, over https, following
 *   at most five redirects and only onto GitHub's own hosts.
 * - The downloaded archive must declare our package name and be signed by
 *   exactly the certificates this installation carries, or it is thrown away.
 *   The installer would refuse a mismatched signature anyway; checking first
 *   gives a clear message instead of a system error and never shows a stranger's
 *   APK to the install flow.
 * - Installation goes through the system package installer, which asks the user.
 *
 * All callbacks are delivered on the main thread.
 */
class UpdateChecker(context: Context) {

    sealed class Result {
        data class UpToDate(val current: String) : Result()
        data class Available(val release: Release) : Result()
        data class Failed(val message: String) : Result()
    }

    sealed class DownloadResult {
        data class Done(val file: File) : DownloadResult()
        data class Failed(val message: String) : DownloadResult()
    }

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The APK lands here; `res/xml/file_paths.xml` exposes this folder to the installer. */
    val updateFile: File get() = File(File(context.cacheDir, UPDATE_DIR), UPDATE_FILE)

    /** Fetches `releases/latest` and compares it with the running version. */
    fun check(callback: (Result) -> Unit) {
        executor.execute {
            val result = try {
                val body = fetchLatest()
                val release = ReleaseFeed.parse(body)
                when {
                    release == null -> Result.Failed(context.getString(R.string.update_error_no_release))
                    ReleaseFeed.isNewer(release.version, BuildConfig.VERSION_NAME) -> Result.Available(release)
                    else -> Result.UpToDate(BuildConfig.VERSION_NAME)
                }
            } catch (e: Exception) {
                // IOException for the network, SecurityException if the platform
                // ever denies the socket; anything else must not take the process
                // down over an optional feature.
                Result.Failed(e.localizedMessage ?: e.javaClass.simpleName)
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun fetchLatest(): String {
        val connection = open(URL(ReleaseFeed.LATEST_URL))
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val bytes = connection.inputStream.use { readCapped(it, MAX_FEED_BYTES) }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams the release's APK into [updateFile]. [onProgress] gets 0..100 as
     * the download advances (only when the size is known up front).
     */
    fun download(release: Release, onProgress: (Int) -> Unit, callback: (DownloadResult) -> Unit) {
        executor.execute {
            val result = try {
                downloadBlocking(release) { percent -> mainHandler.post { onProgress(percent) } }
            } catch (e: Exception) {
                updateFile.delete()
                DownloadResult.Failed(e.localizedMessage ?: e.javaClass.simpleName)
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun downloadBlocking(release: Release, onProgress: (Int) -> Unit): DownloadResult {
        if (!ReleaseFeed.isAllowedDownloadUrl(release.apkUrl)) {
            return DownloadResult.Failed(context.getString(R.string.update_error_untrusted_url))
        }
        if (release.apkSize > MAX_APK_BYTES) {
            return DownloadResult.Failed(context.getString(R.string.update_error_too_large))
        }

        val target = updateFile
        target.parentFile?.mkdirs()
        target.delete()

        val connection = openFollowingRedirects(URL(release.apkUrl))
        try {
            val expected = if (release.apkSize > 0) release.apkSize else connection.contentLengthLong
            if (expected > MAX_APK_BYTES) {
                return DownloadResult.Failed(context.getString(R.string.update_error_too_large))
            }
            var written = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > MAX_APK_BYTES) {
                            return DownloadResult.Failed(context.getString(R.string.update_error_too_large))
                        }
                        output.write(buffer, 0, n)
                        if (expected > 0) {
                            val percent = ((written * 100) / expected).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && written != release.apkSize) {
                target.delete()
                return DownloadResult.Failed(context.getString(R.string.update_error_size_mismatch))
            }
            return DownloadResult.Done(target)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens [start] and follows up to [MAX_REDIRECTS] redirects by hand so each
     * hop can be checked: https only, and only onto hosts GitHub serves release
     * assets from. The platform's automatic redirect following would happily
     * leave for any host the server named.
     */
    private fun openFollowingRedirects(start: URL): HttpsURLConnection {
        var url = start
        for (hop in 0..MAX_REDIRECTS) {
            val connection = open(url)
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> return connection
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308,
                -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (hop == MAX_REDIRECTS || location.isNullOrEmpty()) throw IOException("Too many redirects")
                    val next = URL(url, location)
                    if (next.protocol != "https" || !ReleaseFeed.isAllowedRedirectHost(next.host)) {
                        throw IOException(context.getString(R.string.update_error_untrusted_url))
                    }
                    url = next
                }
                else -> {
                    connection.disconnect()
                    throw IOException("HTTP $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun open(url: URL): HttpsURLConnection {
        if (url.protocol != "https") throw IOException("Not https")
        val connection = url.openConnection() as? HttpsURLConnection ?: throw IOException("Not https")
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        // GitHub rejects requests without a User-Agent. The version is the only
        // thing in it; nothing identifies the device or the user.
        connection.setRequestProperty("User-Agent", "OpenMouse/${BuildConfig.VERSION_NAME}")
        return connection
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("Response too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * True only when [file] is an APK for this package signed with exactly the
     * same certificates as the installed app. Any failure to read either side
     * counts as a mismatch.
     */
    fun signatureMatches(file: File): Boolean {
        return try {
            val pm = context.packageManager
            val archive = archiveInfo(pm, file) ?: return false
            if (archive.packageName != context.packageName) return false
            val installed = installedInfo(pm) ?: return false
            val ours = signaturesOf(installed)
            val theirs = signaturesOf(archive)
            ours.isNotEmpty() && ours == theirs
        } catch (e: Exception) {
            false
        }
    }

    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? {
        val flags = signatureFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, flags)
        }
    }

    private fun installedInfo(pm: PackageManager): PackageInfo? {
        val flags = signatureFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /** The signing certificates as a set of their encoded forms. */
    private fun signaturesOf(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // apkContentsSigners is the set of certificates the APK is
                // actually signed with, ignoring any rotation history, which is
                // the strict comparison we want.
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
        if (signatures == null) return emptySet()
        return signatures.mapTo(HashSet()) { it.toCharsString() }
    }

    /**
     * Hands [file] to the system installer. Returns false (after opening the
     * relevant settings page) when this app is not yet allowed to request
     * installs; the caller then asks the user to try again.
     */
    fun install(activity: Activity, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
        return true
    }

    companion object {
        /**
         * One worker for the whole process, shared by every checker (the launch
         * check and each Settings screen create their own). Its single daemon
         * thread exits after [IDLE_TIMEOUT_S] idle, so nothing lingers between
         * checks and nothing is left to shut down when an activity goes away.
         * Work is serialized, which also keeps two downloads from writing
         * [updateFile] at once. Results still go through each activity's
         * liveness check (`UpdatePrompt.shouldDeliver`), so a task that
         * outlives its screen is simply dropped.
         */
        private val executor = ThreadPoolExecutor(1, 1, IDLE_TIMEOUT_S, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
            Thread(task, "openmouse-update").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }

        private const val IDLE_TIMEOUT_S = 30L
        private const val UPDATE_DIR = "updates"
        private const val UPDATE_FILE = "openmouse-update.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val TIMEOUT_MS = 10_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_FEED_BYTES = 1 * 1024 * 1024
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
}
