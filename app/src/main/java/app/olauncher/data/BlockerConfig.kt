package app.olauncher.data

/**
 * The parts of the blocker that are not worth a settings row.
 *
 * Everything the user picks - which apps are blocked, which are limited, the window, the
 * limit, the home Wi-Fi, the teleport app - lives in [Prefs] and is edited in Settings.
 * What is left here is the browser plumbing and the constants behind the reflection screen.
 */
object BlockerConfig {

    /**
     * Domains blocked in the browser during the window. Matched against the host as
     * `host == domain || host.endsWith(".$domain")`, so "youtube.com" also covers
     * "m.youtube.com" and "www.youtube.com".
     *
     * Not in settings: typing domains on a phone keyboard is worse than editing this list.
     */
    val blockedDomains = listOf(
        "youtube.com",
        "youtu.be",
        "reddit.com",
        "redd.it",
    )

    /**
     * Browsers whose address bar is read, mapped to the view id of that address bar.
     * A browser that is not listed here is not watched at all.
     *
     * Find the view id of another browser with:
     *     adb shell uiautomator dump && adb shell cat /sdcard/window_dump.xml
     */
    val watchedBrowsers = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
    )

    const val DEFAULT_FROM_HOUR = 16
    const val DEFAULT_UNTIL_HOUR = 4
    const val DEFAULT_LIMIT_MINUTES = 10

    /** Length of every allowance the reflection screen grants. */
    const val UNLOCK_MINUTES = 5

    /** How long the opening reflection screen refuses to open anything, in milliseconds. */
    const val REFLECT_DELAY_MS = 10_000L

    /** Shortest answer accepted by either reflection screen. Raise it if one-word answers slip through. */
    const val MIN_REASON_CHARS = 15

    /** Values offered by the settings row for the shared time limit. */
    val LIMIT_MINUTES_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)
}
