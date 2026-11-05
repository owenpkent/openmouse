package io.github.owenpkent.openmouse.update

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.owenpkent.openmouse.R

/**
 * The "a new version is available" dialog, shared by the Settings button and
 * the launch check. Pressing "Download and install" runs the whole chain:
 * download (with a progress dialog), signature check, hand-off to the system
 * installer. Every step reports a human-readable status through [onStatus] so
 * the Settings screen can mirror it in its status line; the launch path passes
 * a no-op.
 *
 * Nothing here is automatic: the user sees the release notes and chooses, and
 * the system installer asks once more before anything is replaced.
 *
 * Every callback belongs to the activity that started it. If that activity is
 * finishing or destroyed by the time a result arrives (the screen rotated, or
 * the user left Settings mid-download), the result is dropped: no dialog is
 * shown on it, its views are left alone, and the installer is not launched
 * from a screen the user can no longer see. There is no retained state; the
 * downloaded file stays in the cache and the next "Check for updates now"
 * offers it again.
 */
object UpdatePrompt {

    private const val MAX_NOTES_CHARS = 2000

    /**
     * Whether a result may still reach the activity it was requested from. Kept
     * pure (no Activity) so the rule can be unit tested; [alive] feeds it the
     * real flags.
     */
    internal fun shouldDeliver(isFinishing: Boolean, isDestroyed: Boolean): Boolean = !isFinishing && !isDestroyed

    private fun alive(activity: Activity): Boolean = shouldDeliver(activity.isFinishing, activity.isDestroyed)

    fun show(activity: Activity, checker: UpdateChecker, release: Release, onStatus: (CharSequence) -> Unit = {}) {
        if (!alive(activity)) return
        val notes = release.notes.trim().let { if (it.length > MAX_NOTES_CHARS) it.take(MAX_NOTES_CHARS) + "…" else it }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, release.version))
            .setMessage(notes.ifEmpty { activity.getString(R.string.update_dialog_no_notes) })
            .setPositiveButton(R.string.update_dialog_install) { _, _ -> download(activity, checker, release, onStatus) }
            .setNegativeButton(R.string.update_dialog_not_now, null)
            .show()
    }

    private fun download(activity: Activity, checker: UpdateChecker, release: Release, onStatus: (CharSequence) -> Unit) {
        if (!alive(activity)) return
        // Built in code rather than inflated: a dialog's content view has no
        // parent at inflation time, and this is three widgets.
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(activity).apply {
            text = activity.getString(R.string.update_status_downloading, 0)
            setPadding(0, pad / 2, 0, 0)
        }
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(bar)
            addView(label)
        }
        onStatus(label.text)
        val progress: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, release.version))
            .setView(view)
            .setCancelable(false)
            .show()

        checker.download(
            release,
            onProgress = onProgress@{ percent ->
                if (ownerGone(activity, progress)) return@onProgress
                bar.progress = percent
                label.text = activity.getString(R.string.update_status_downloading, percent)
                onStatus(label.text)
            },
            callback = callback@{ result ->
                if (ownerGone(activity, progress)) return@callback
                progress.dismiss()
                when (result) {
                    is UpdateChecker.DownloadResult.Failed -> {
                        report(activity, onStatus, activity.getString(R.string.update_status_failed, result.message))
                    }
                    is UpdateChecker.DownloadResult.Done -> {
                        if (!checker.signatureMatches(result.file)) {
                            result.file.delete()
                            report(activity, onStatus, activity.getString(R.string.update_status_signature_mismatch))
                        } else if (!checker.install(activity, result.file)) {
                            report(activity, onStatus, activity.getString(R.string.update_status_allow_installs))
                        } else {
                            onStatus(activity.getString(R.string.update_status_installing))
                        }
                    }
                }
            },
        )
    }

    /**
     * True when the activity that started the download is gone, in which case
     * the calling callback must stop here: a destroyed activity drops the
     * result (see the class doc). Takes the progress dialog down first while
     * its window still exists. A finishing activity keeps its window until it
     * is destroyed; a destroyed one has already lost it, and dismissing then
     * would throw.
     */
    private fun ownerGone(activity: Activity, progress: AlertDialog): Boolean {
        if (alive(activity)) return false
        if (!activity.isDestroyed && progress.isShowing) progress.dismiss()
        return true
    }

    /**
     * A terminal message goes both to the status sink and to a plain dialog, so
     * the launch path (which has no status line) still tells the user what
     * happened instead of silently doing nothing.
     */
    private fun report(activity: Activity, onStatus: (CharSequence) -> Unit, message: String) {
        onStatus(message)
        if (!alive(activity)) return
        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
