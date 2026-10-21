package app.olauncher.helper.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.olauncher.data.BlockerConfig
import app.olauncher.data.Prefs
import app.olauncher.helper.usageStats.EventLogWrapper
import app.olauncher.ui.ReflectActivity

/**
 * Decides, from accessibility events, whether what is on screen should be interrupted by
 * [ReflectActivity]. Driven by MyAccessibilityService.
 *
 * Two different interruptions:
 * - **Entry gate**, for an app or site with no free allowance that has not been unlocked
 *   yet tonight. Ten seconds and a written reason buy five minutes.
 * - **Allowance over**, when a limited app's budget is spent or a five-minute allowance has
 *   expired. Asks whether the time was well spent, then offers teleport, stop, or five more.
 */
class Blocker(private val service: AccessibilityService) {

    private val prefs by lazy { Prefs(service) }
    private val eventLog by lazy { EventLogWrapper(service) }
    private val handler = Handler(Looper.getMainLooper())

    private var lastEvaluateAt = 0L
    private var lastBlockAt = 0L
    private var usageCacheAt = 0L
    private var usageCacheStart = 0L
    private var usageRefreshing = false
    private val usageExecutor = Executors.newSingleThreadExecutor()
    private var usageCache: Map<String, Long> = emptyMap()
    private var foregroundPackage: String? = null
    private var lastKeywordScanAt = 0L
    private var lastKeywordMatch: String? = null

    private val recheck = Runnable { foregroundPackage?.let { evaluate(it, force = true) } }

    fun onEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (isWatched(packageName).not()) {
            foregroundPackage = null
            handler.removeCallbacks(recheck)
            return
        }

        foregroundPackage = packageName
        evaluate(packageName, force = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    /**
     * The accessibility service receives events from every package, since the watched set is
     * now whatever is ticked in Settings. Keep this cheap - it runs on every event the phone
     * produces.
     */
    private fun isWatched(packageName: String): Boolean =
        BlockerConfig.watchedBrowsers.containsKey(packageName) ||
                prefs.blockerBlockedApps.contains(packageName) ||
                prefs.blockerLimitedApps.contains(packageName)

    private fun evaluate(packageName: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (force.not() && now - lastEvaluateAt < EVALUATE_THROTTLE_MS) return
        lastEvaluateAt = now

        // Accessibility events stop arriving while the screen sits still, but a budget still
        // runs out and an allowance still expires, so keep re-checking while a watched app is up.
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, RECHECK_MS)

        val windowStart =
            BlockerRules.windowStart(now, prefs.blockerFromHour, prefs.blockerUntilHour) ?: return
        if (prefs.blockerWindowStartedAt != windowStart) {
            prefs.blockerClearSession()
            prefs.blockerWindowStartedAt = windowStart
        }

        // Sites are keyed by domain so that unlocking youtube.com also covers m.youtube.com;
        // apps are keyed by package. A limited app is the only one with free minutes.
        //
        // An app ticked in Settings is judged as an app even when it is also a watched browser.
        // See BlockerRules.appBudgetMs for why the precedence runs this way.
        val appBudget = BlockerRules.appBudgetMs(
            packageName,
            prefs.blockerBlockedApps,
            prefs.blockerLimitedApps,
            prefs.blockerLimitMinutes,
        )
        val key = if (appBudget != null) packageName else browserTrigger(packageName) ?: return
        val budgetMs = appBudget ?: 0L

        if (prefs.blockerUnlockedUntil(key) > now) return

        val unlockCount = prefs.blockerUnlockCount(key)
        if (budgetMs > 0L && unlockCount == 0) {
            // Null means the figure is still being fetched; the refresh calls back into here.
            val used = foregroundMillis(packageName, windowStart, now) ?: return
            if (used < budgetMs) return
            gate(key, packageName, ReflectActivity.MODE_ALLOWANCE_OVER, (used / 60_000L).toInt(), now)
            return
        }

        if (unlockCount == 0) {
            // An entry gate needs no usage figure, so it still works without usage access.
            gate(key, packageName, ReflectActivity.MODE_ENTRY, null, now)
        } else {
            val spent = foregroundMillis(packageName, prefs.blockerUnlockedAt(key), now) ?: return
            gate(key, packageName, ReflectActivity.MODE_ALLOWANCE_OVER, (spent / 60_000L).toInt(), now)
        }
    }

    /**
     * What makes this browser page blockable: a blocked domain, or failing that a blocked keyword
     * somewhere on it. The domain check is one node lookup, the keyword scan walks the page, so
     * the cheap one runs first and the expensive one only when it has to.
     */
    private fun browserTrigger(packageName: String): String? {
        val viewId = BlockerConfig.watchedBrowsers[packageName] ?: return null
        val host = BlockerRules.hostOf(readAddressBar(viewId))
        if (host != null)
            BlockerRules.matchedDomain(host, BlockerConfig.blockedDomains)?.let { return it }
        return matchedKeyword()
    }

    /**
     * Scans the page for blocked keywords, at most every [KEYWORD_SCAN_MS]. Between scans the
     * previous answer stands, which is why the interval is short: the cost of being briefly wrong
     * after navigating away is one gate that the next scan would not have raised.
     */
    private fun matchedKeyword(): String? {
        if (prefs.blockerKeywords.isEmpty()) return null
        val now = System.currentTimeMillis()
        if (now - lastKeywordScanAt < KEYWORD_SCAN_MS) return lastKeywordMatch
        lastKeywordScanAt = now
        lastKeywordMatch = BlockerRules.matchedKeyword(visibleText(), prefs.blockerKeywords)
        return lastKeywordMatch
    }

    /**
     * The text the page is rendering, read off the accessibility tree.
     *
     * Capped on both node count and length: this runs on the main thread, a busy page can carry
     * thousands of nodes, and an uncapped walk here is the same mistake as querying usage stats
     * inline. A long article past the cap is only partly searched, which is the right way round -
     * a missed keyword costs nothing, a janky phone costs every app on it.
     */
    private fun visibleText(): String = runCatching {
        val root = service.rootInActiveWindow ?: return@runCatching ""
        val text = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES && text.length < MAX_SCAN_CHARS) {
            val node = queue.removeFirst()
            visited++
            node.text?.let { text.append(it).append('\n') }
            node.contentDescription?.let { text.append(it).append('\n') }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        text.toString()
    }.getOrDefault("")

    private fun gate(key: String, packageName: String, mode: Int, minutesUsed: Int?, now: Long) {
        if (now - lastBlockAt < BLOCK_DEBOUNCE_MS) return
        lastBlockAt = now
        runCatching {
            service.startActivity(
                ReflectActivity.intent(service, key, packageName, mode, minutesUsed)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Chrome keeps the current host in its address bar node even when the bar is scrolled off
     * screen. It is still the fragile part of this whole thing: a Chrome update that renames
     * the view id silently stops browser blocking.
     */
    private fun readAddressBar(viewId: String): String? = runCatching {
        service.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(viewId)
            ?.firstOrNull { it.text != null }
            ?.text
            ?.toString()
    }.getOrNull()

    /**
     * Foreground time from the cache, refreshing it in the background when stale, or null while
     * no figure for [start] has been fetched yet.
     *
     * Never queries inline: the service shares the launcher's process, and querying the event log
     * over a whole evening takes long enough that doing it in onAccessibilityEvent stalls
     * accessibility dispatch and makes the foreground app miss frames or ANR.
     *
     * Callers treat null as "ask again later" rather than as zero, because reporting zero minutes
     * on a screen that exists to say how long you have been in an app is worse than showing it a
     * beat late. A query that comes back empty is a real zero and does not return null, so a
     * missing usage-access permission cannot wedge this permanently.
     */
    private fun foregroundMillis(packageName: String, start: Long, now: Long): Long? {
        if (start <= 0L || start >= now) return 0L
        if (usageCacheStart != start || now - usageCacheAt > USAGE_CACHE_MS) refreshUsage(start, now)
        // ponytail: one cache slot, so two watched apps with different allowance starts take turns
        // refreshing. The 20s re-check covers it; key the cache by start if it ever matters.
        if (usageCacheStart != start) return null
        return usageCache[packageName] ?: 0L
    }

    private fun refreshUsage(start: Long, now: Long) {
        if (usageRefreshing) return
        usageRefreshing = true
        usageExecutor.execute {
            val fresh = runCatching {
                eventLog.aggregateForegroundStats(eventLog.getForegroundStatsByTimestamps(start, now))
                    .associate { it.applicationId to it.timeUsed }
            }.getOrDefault(emptyMap())
            handler.post {
                usageCache = fresh
                usageCacheStart = start
                usageCacheAt = System.currentTimeMillis()
                usageRefreshing = false
                // The numbers just changed, so a budget may have turned out to be spent.
                foregroundPackage?.let { evaluate(it, force = true) }
            }
        }
    }

    private companion object {
        /** Chrome fires content-changed events constantly; only one evaluation per interval. */
        const val EVALUATE_THROTTLE_MS = 400L

        const val RECHECK_MS = 20_000L

        /** Keeps an app from being interrupted again while the reflection screen is coming up. */
        const val BLOCK_DEBOUNCE_MS = 5_000L

        /** Querying the event log is not free; a budget cannot be overspent by more than this. */
        const val USAGE_CACHE_MS = 10_000L

        /** Walking the page is not free either, so keyword scanning runs at most this often. */
        const val KEYWORD_SCAN_MS = 1_500L

        const val MAX_SCAN_NODES = 1_500
        const val MAX_SCAN_CHARS = 40_000
    }
}
