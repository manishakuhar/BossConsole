package ai.rever.boss.components.windowpanel

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.components.main_window_panels.convertTabInfoToTabConfig
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BookmarkTabConfigTest {
    @Test fun `terminal bookmarks retain directory and launch command`() {
        val tab =
            TerminalTabInfo("one", title = "Server", workingDirectory = "/work/one", initialCommand = "npm run dev")
        val saved = convertTabInfoToTabConfig(tab)
        assertEquals("/work/one", saved.workingDirectory)
        assertEquals("npm run dev", saved.initialCommand)
        assertNotEquals(saved, convertTabInfoToTabConfig(tab.copy(id = "two", workingDirectory = "/work/two")))
    }

    @Test fun `browser bookmark captures the page currently shown`() {
        val tab =
            FluckTabInfo(
                id = "browser",
                typeId = TabTypeId("fluck"),
                _title = "Initial",
                url = "https://initial.example",
            )
        tab.navigateToPage("Current", "https://current.example/path")
        val saved = convertTabInfoToTabConfig(tab)
        assertEquals("https://current.example/path", saved.url)
        assertEquals(tab.title, saved.title)
    }
}
