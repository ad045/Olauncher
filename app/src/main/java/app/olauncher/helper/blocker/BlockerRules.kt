package app.olauncher.helper.blocker

import java.util.Calendar

/**
 * The decisions the blocker makes, with no Android dependencies, so they can be
 * unit tested on the JVM. See BlockerRulesTest.
 */
object BlockerRules {

    /**
     * Start of the blocking window that [now] falls into, or null if [now] is outside it.
     *
     * The returned timestamp is what app budgets are counted from: at 02:00 with a
     * 16-04 window, that is yesterday 16:00, not today 00:00.
     */
    fun windowStart(now: Long, fromHour: Int, untilHour: Int): Long? {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val crossesMidnight = untilHour <= fromHour

        val inWindow =
            if (crossesMidnight) hour >= fromHour || hour < untilHour
            else hour >= fromHour && hour < untilHour
        if (inWindow.not()) return null

        calendar.set(Calendar.HOUR_OF_DAY, fromHour)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (hour < fromHour) calendar.add(Calendar.DAY_OF_YEAR, -1)
        return calendar.timeInMillis
    }

    /**
     * Pulls a host out of whatever the browser's address bar happens to show. Chrome
     * shows anything from "youtube.com" to a full URL to the "Search or type web
     * address" placeholder to a half-typed search query, so anything without a dot or
     * with whitespace in it is treated as "not a host" and returns null.
     */
    fun hostOf(addressBarText: String?): String? {
        val text = addressBarText?.trim()?.lowercase()
        if (text.isNullOrEmpty()) return null

        val host = text
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .removePrefix("www.")

        if (host.contains('.').not()) return null
        if (host.endsWith('.')) return null
        if (host.any { it.isWhitespace() }) return null
        return host
    }

    /**
     * The first keyword that appears anywhere in the text read off the page, or null.
     *
     * This is the text the page actually renders, not its HTML source: a word that only exists
     * in a meta tag, a script or an unopened menu is not there to be found. The flip side is that
     * it matches the word wherever it shows up, including in an article that merely mentions it.
     */
    fun matchedKeyword(visibleText: String, keywords: Set<String>): String? {
        if (visibleText.isEmpty() || keywords.isEmpty()) return null
        val haystack = visibleText.lowercase()
        return keywords
            .map { it.trim().lowercase() }
            .firstOrNull { it.isNotEmpty() && haystack.contains(it) }
    }

    /**
     * Which rule applies to the package on screen, as a budget in milliseconds, or null when
     * none does. Pulled out of Blocker so the precedence is testable without an Android device.
     *
     * Precedence, both of which cost real debugging to get right:
     * - In both lists means blocked. The stricter rule wins.
     * - A ticked app is judged as an app even when it is also a watched browser. Otherwise
     *   putting Chrome on a list does nothing, because the domain check runs first, finds the
     *   current page is not blocked, and gives up before the app rule is ever consulted.
     */
    fun appBudgetMs(
        packageName: String,
        blockedApps: Set<String>,
        limitedApps: Set<String>,
        limitMinutes: Int,
    ): Long? = when {
        blockedApps.contains(packageName) -> 0L
        limitedApps.contains(packageName) -> limitMinutes * 60_000L
        else -> null
    }

    /** The blocked domain [host] belongs to, or null. The domain is the unlock key, so unlocking "youtube.com" also unlocks "m.youtube.com". */
    fun matchedDomain(host: String, blockedDomains: List<String>): String? =
        blockedDomains.firstOrNull { host == it || host.endsWith(".$it") }
}
