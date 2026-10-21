package app.olauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.helper.blocker.Blocker

class MyAccessibilityService : AccessibilityService() {

    private val blocker by lazy { Blocker(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        Prefs(applicationContext).lockModeOn = true
        connected = this
        super.onServiceConnected()
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Kept separate from the lock gesture below, which returns early on most events.
        try {
            blocker.onEvent(event)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val source: AccessibilityNodeInfo = event.source ?: return
            if (source.className != "android.widget.FrameLayout") return

            when (source.contentDescription) {
                getString(R.string.lock_layout_description) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                // Home button for recents feature disabled
                // getString(R.string.recents_layout_description) -> {
                //     performGlobalAction(GLOBAL_ACTION_RECENTS)
                // }
            }
        } catch (e: Exception) {
            return
        }
    }

    override fun onInterrupt() {

    }

    companion object {
        /**
         * The bound service, or null when the permission is off.
         *
         * Holding a service in a static field is safe here in a way it would not normally be: a
         * service lives as long as its process, and this one now shares the launcher's process,
         * so there is nothing to leak that would otherwise have been collected. It is cleared in
         * [onDestroy] regardless.
         */
        private var connected: MyAccessibilityService? = null

        /**
         * Locks the screen, returning false if it could not. Callers fall back to device admin.
         *
         * The alternative is the trick the home screen's double tap uses - clicking an invisible
         * view that this service recognises by its content description - but a synthetic
         * performClick does not reliably produce an accessibility event from every layout, and a
         * lock that silently does nothing is worse than a direct call.
         */
        fun lockScreen(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val service = connected ?: return false
            return runCatching { service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }.getOrDefault(false)
        }
    }
}