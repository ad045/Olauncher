package app.olauncher.helper.blocker

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every reflection answer, appended as a row of TSV.
 *
 * Kept in external app storage rather than the private data dir so it can actually be read
 * back - the Files app can reach it, and Settings offers to share it. Nothing sensitive is
 * in it that is not already on the screen when it is written.
 *
 *     adb pull /sdcard/Android/data/app.olauncher/files/excuses.tsv
 */
class ExcuseLog(context: Context) {

    private val appContext = context.applicationContext

    val file: File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, FILE_NAME)

    /**
     * @param app the app's own label, or the domain for a site
     * @param kind [KIND_OPEN] when talking your way in, [KIND_SESSION_END] when the allowance ran out
     * @param unlockNumber how many allowances this app has had tonight, including this one
     * @param minutesUsed minutes spent in the app during the allowance that just ended, or null on open
     * @param action what was chosen: [ACTION_OPEN], [ACTION_CONTINUE], [ACTION_TELEPORT], [ACTION_STOP]
     */
    fun append(
        app: String,
        kind: String,
        unlockNumber: Int,
        minutesUsed: Int?,
        action: String,
        answer: String,
    ) {
        runCatching {
            if (file.exists().not()) file.appendText(HEADER)
            val row = listOf(
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                SimpleDateFormat("HH:mm", Locale.US).format(Date()),
                app,
                kind,
                unlockNumber.toString(),
                minutesUsed?.toString().orEmpty(),
                action,
                answer.replace('\n', ' ').replace('\t', ' ').trim(),
            )
            file.appendText(row.joinToString("\t") + "\n")
        }
    }

    companion object {
        const val FILE_NAME = "excuses.tsv"

        const val KIND_OPEN = "open"
        const val KIND_SESSION_END = "session_end"

        const val ACTION_OPEN = "opened"
        const val ACTION_CONTINUE = "continued"
        const val ACTION_TELEPORT = "teleported"
        const val ACTION_STOP = "stopped"

        private const val HEADER = "date\ttime\tapp\tkind\tunlock_no\tminutes_used\taction\tanswer\n"
    }
}
