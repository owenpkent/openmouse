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
 */
object UpdatePrompt {

    private const val MAX_NOTES_CHARS = 2000

    fun show(activity: Activity, checker: UpdateChecker, release: Release, onStatus: (CharSequence) -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        val notes = release.notes.trim().let { if (it.length > MAX_NOTES_CHARS) it.take(MAX_NOTES_CHARS) + "…" else it }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, release.version))
            .setMessage(notes.ifEmpty { activity.getString(R.string.update_dialog_no_notes) })
            .setPositiveButton(R.string.update_dialog_install) { _, _ -> download(activity, checker, release, onStatus) }
            .setNegativeButton(R.string.update_dialog_not_now, null)
            .show()
    }

    private fun download(activity: Activity, checker: UpdateChecker, release: Release, onStatus: (CharSequence) -> Unit) {
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
            onProgress = { percent ->
                bar.progress = percent
                label.text = activity.getString(R.string.update_status_downloading, percent)
                onStatus(label.text)
            },
            callback = { result ->
                if (!activity.isFinishing && !activity.isDestroyed) progress.dismiss()
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
     * A terminal message goes both to the status sink and to a plain dialog, so
     * the launch path (which has no status line) still tells the user what
     * happened instead of silently doing nothing.
     */
    private fun report(activity: Activity, onStatus: (CharSequence) -> Unit, message: String) {
        onStatus(message)
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
