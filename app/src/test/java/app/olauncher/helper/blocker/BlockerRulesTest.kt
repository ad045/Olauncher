package app.olauncher.helper.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BlockerRulesTest {

    private val fromHour = 16
    private val untilHour = 4

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `inside the window after the start hour, the budget counts from today 16h`() {
        assertEquals(at(10, 16), BlockerRules.windowStart(at(10, 22, 30), fromHour, untilHour))
    }

    @Test
    fun `after midnight, the budget still counts from yesterday 16h`() {
        assertEquals(at(10, 16), BlockerRules.windowStart(at(11, 2, 15), fromHour, untilHour))
    }

    @Test
    fun `outside the window there is no window start`() {
        assertNull(BlockerRules.windowStart(at(10, 4), fromHour, untilHour))
        assertNull(BlockerRules.windowStart(at(10, 11), fromHour, untilHour))
        assertNull(BlockerRules.windowStart(at(10, 15, 59), fromHour, untilHour))
    }

    @Test
    fun `the start hour is inclusive and the end hour exclusive`() {
        assertEquals(at(10, 16), BlockerRules.windowStart(at(10, 16), fromHour, untilHour))
        assertEquals(at(9, 16), BlockerRules.windowStart(at(10, 3, 59), fromHour, untilHour))
    }

    @Test
    fun `a window that does not cross midnight still works`() {
        assertEquals(at(10, 9), BlockerRules.windowStart(at(10, 12), 9, 17))
        assertNull(BlockerRules.windowStart(at(10, 20), 9, 17))
        assertNull(BlockerRules.windowStart(at(10, 2), 9, 17))
    }

    @Test
    fun `hosts are pulled out of whatever the address bar shows`() {
        assertEquals("youtube.com", BlockerRules.hostOf("youtube.com"))
        assertEquals("youtube.com", BlockerRules.hostOf("www.youtube.com"))
        assertEquals("m.youtube.com", BlockerRules.hostOf("https://m.youtube.com/watch?v=abc"))
        assertEquals("old.reddit.com", BlockerRules.hostOf("OLD.REDDIT.COM/r/all"))
        assertEquals("localhost.dev", BlockerRules.hostOf("http://localhost.dev:8080/x"))
    }

    @Test
    fun `placeholders and search queries are not hosts`() {
        assertNull(BlockerRules.hostOf(null))
        assertNull(BlockerRules.hostOf(""))
        assertNull(BlockerRules.hostOf("   "))
        assertNull(BlockerRules.hostOf("Search or type web address"))
        assertNull(BlockerRules.hostOf("how to stop watching youtube"))
        assertNull(BlockerRules.hostOf("youtube"))
    }

    @Test
    fun `subdomains match the blocked domain, lookalikes do not`() {
        val blocked = listOf("youtube.com", "reddit.com")
        assertEquals("youtube.com", BlockerRules.matchedDomain("youtube.com", blocked))
        assertEquals("youtube.com", BlockerRules.matchedDomain("m.youtube.com", blocked))
        assertEquals("reddit.com", BlockerRules.matchedDomain("old.reddit.com", blocked))
        assertNull(BlockerRules.matchedDomain("notyoutube.com", blocked))
        assertNull(BlockerRules.matchedDomain("youtube.com.evil.net", blocked))
        assertNull(BlockerRules.matchedDomain("nytimes.com", blocked))
    }

    @Test
    fun `a blocked app gets no free minutes, a limited app gets the shared limit`() {
        val blocked = setOf("com.nytimes.android")
        val limited = setOf("net.faz.FAZAndroid")
        assertEquals(0L, BlockerRules.appBudgetMs("com.nytimes.android", blocked, limited, 10))
        assertEquals(600_000L, BlockerRules.appBudgetMs("net.faz.FAZAndroid", blocked, limited, 10))
    }

    @Test
    fun `an app on neither list has no app rule, so the domain rule can apply`() {
        assertNull(BlockerRules.appBudgetMs("com.android.chrome", emptySet(), emptySet(), 10))
    }

    @Test
    fun `an app on both lists counts as blocked`() {
        val both = setOf("com.android.chrome")
        assertEquals(0L, BlockerRules.appBudgetMs("com.android.chrome", both, both, 10))
    }

    @Test
    fun `a ticked browser is judged as an app, not by the page it is showing`() {
        // The bug this pins: with the domain check first, ticking Chrome did nothing, because
        // example.com is not a blocked domain and the app rule was never reached.
        assertEquals(
            0L,
            BlockerRules.appBudgetMs("com.android.chrome", setOf("com.android.chrome"), emptySet(), 10)
        )
    }

    @Test
    fun `a keyword anywhere in the page text blocks it, case-insensitively`() {
        val keywords = setOf("instagram", "tiktok")
        assertEquals("instagram", BlockerRules.matchedKeyword("Log in to Instagram", keywords))
        assertEquals("instagram", BlockerRules.matchedKeyword("INSTAGRAM", keywords))
        assertEquals("tiktok", BlockerRules.matchedKeyword("Watch on TikTok now", keywords))
    }

    @Test
    fun `a page without any of the keywords is left alone`() {
        val keywords = setOf("instagram")
        assertNull(BlockerRules.matchedKeyword("Example Domain", keywords))
        assertNull(BlockerRules.matchedKeyword("", keywords))
        assertNull(BlockerRules.matchedKeyword("Instagram", emptySet()))
    }

    @Test
    fun `stored keywords are trimmed, and blank ones never match everything`() {
        // A stray comma would otherwise leave an empty string in the set, and "".contains("")
        // is true, which would block every page in the browser.
        assertNull(BlockerRules.matchedKeyword("Example Domain", setOf("", "   ")))
        assertEquals("instagram", BlockerRules.matchedKeyword("open instagram", setOf("  Instagram  ")))
    }
}
