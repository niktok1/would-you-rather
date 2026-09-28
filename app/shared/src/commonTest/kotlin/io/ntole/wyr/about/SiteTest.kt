package io.ntole.wyr.about

import io.ntole.wyr.language.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where the game's links to its site go (CLAUDE.md §8d, *About*). */
class SiteTest {
    @Test
    fun `each page is under the one base in Serbian and under en in English`() {
        assertEquals("https://ntole.com/wyr/privacy.html", Site.url(SitePage.PRIVACY, Language.SERBIAN_CYRILLIC))
        assertEquals("https://ntole.com/wyr/terms.html", Site.url(SitePage.TERMS, Language.SERBIAN_LATIN))
        assertEquals("https://ntole.com/wyr/en/delete.html", Site.url(SitePage.DELETE_ACCOUNT, Language.ENGLISH))
        assertEquals("https://ntole.com/wyr/en/contact.html", Site.url(SitePage.CONTACT, Language.ENGLISH))
    }

    @Test
    fun `the version shows its build number when there is one`() {
        assertEquals("1.2.3 (10203)", versionText(AppVersion("1.2.3", 10203)))
        assertEquals("1.2.3", versionText(AppVersion("1.2.3", null)))
    }
}
