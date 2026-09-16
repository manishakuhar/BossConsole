package ai.rever.boss.services.bookmarks

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkOpenResult
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One capability-aware opening route. It never replaces tabs or consumes a pending split. */
internal class BookmarkOpeningService(
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val checkPath: suspend (String, Boolean) -> Boolean = { path, directory ->
        withContext(Dispatchers.IO) {
            val file = File(path)
            file.isAbsolute && (if (directory) file.isDirectory else file.isFile) && file.canRead()
        }
    },
) {
    companion object {
        val shared = BookmarkOpeningService()
    }

    private data class OpeningKey(
        val window: SplitViewState,
        val panelId: String,
        val bookmarkId: String,
        val config: TabConfig,
        val forceNewTab: Boolean,
    )

    private val inFlight = mutableMapOf<OpeningKey, CompletableDeferred<BookmarkOpenResult>>()
    private val lock = Any()

    suspend fun open(
        splitView: SplitViewState,
        bookmark: Bookmark,
        panelId: String? = null,
        forceNewTab: Boolean = false,
        targetStillAvailable: () -> Boolean = { true },
    ): BookmarkOpenResult =
        withContext(uiDispatcher) {
            val target = panelId ?: splitView.activePanelId
            val panel = splitView.getPanel(target)
            if (panel == null || !targetStillAvailable()) {
                return@withContext failure("The target pane closed. Open the bookmark again in an existing pane.")
            }
            val key = OpeningKey(splitView, target, bookmark.id, bookmark.tabConfig, forceNewTab)
            val completion = CompletableDeferred<BookmarkOpenResult>()
            val existing = synchronized(lock) { inFlight.putIfAbsent(key, completion) }
            if (existing != null) return@withContext existing.await()
            try {
                val result =
                    openOnce(splitView, target, bookmark.tabConfig, forceNewTab) {
                        targetStillAvailable() && splitView.getPanel(target) === panel
                    }
                linkTerminal(bookmark, result)
                completion.complete(result)
                result
            } catch (cancelled: CancellationException) {
                completion.completeExceptionally(cancelled)
                throw cancelled
            } catch (_: LinkageError) {
                failure("The bookmark tool was unloaded. Enable it in Tools and retry.").also(completion::complete)
            } catch (_: Exception) {
                failure("Unable to open bookmark. Check its saved target and try again.").also(completion::complete)
            } finally {
                synchronized(lock) { inFlight.remove(key, completion) }
            }
        }

    private fun linkTerminal(
        bookmark: Bookmark,
        result: BookmarkOpenResult,
    ) {
        val tabId = result.tabId
        if (bookmark.tabConfig.type == "terminal" && result.success && tabId != null) {
            TerminalBookmarkLinks.bind(tabId, bookmark.id)
        }
    }

    private suspend fun openOnce(
        splitView: SplitViewState,
        panelId: String,
        config: TabConfig,
        forceNewTab: Boolean,
        targetStillAvailable: () -> Boolean,
    ): BookmarkOpenResult {
        val type = bookmarkTabType(config.type)
        val problem =
            bookmarkTargetProblem(config) ?: if (type == null || !splitView.tabRegistry.isRegistered(type)) {
                missingTool(config.type).message
            } else {
                validate(config)
            }
        return when {
            problem != null -> failure(problem)
            !targetStillAvailable() -> failure("The target pane was closed or changed. Open the bookmark again.")
            type == null || !splitView.tabRegistry.isRegistered(type) -> missingTool(config.type)
            else -> openValidated(splitView, panelId, config, forceNewTab)
        }
    }

    private fun openValidated(
        splitView: SplitViewState,
        panelId: String,
        config: TabConfig,
        forceNewTab: Boolean,
    ): BookmarkOpenResult {
        val component = requireNotNull(splitView.getPanel(panelId)).tabsComponent
        val existing =
            if (forceNewTab) {
                -1
            } else {
                component.tabsState.value.tabs
                    .indexOfFirst { matchesResource(config, it) }
            }
        return if (existing >= 0) {
            val tabId =
                component.tabsState.value.tabs[existing]
                    .id
            component.selectTab(existing)
            splitView.setActivePanel(panelId)
            BookmarkOpenResult(success = true, tabId = tabId, reused = true)
        } else {
            val tab = createTab(config)
            val index = component.addTab(tab)
            if (index < 0) {
                missingTool(config.type)
            } else {
                splitView.setActivePanel(panelId)
                BookmarkOpenResult(success = true, tabId = tab.id)
            }
        }
    }

    private suspend fun validate(config: TabConfig): String? =
        when (config.type) {
            "browser" -> {
                if (config.url.isNullOrBlank()) "No saved web address. Edit this bookmark before opening." else null
            }

            "editor", "jupyter" -> {
                when {
                    config.filePath.isNullOrBlank() -> "No saved file path. Save the file and update this bookmark."

                    !checkPath(
                        requireNotNull(config.filePath),
                        false,
                    ) -> "The saved file is missing or inaccessible. Edit the bookmark to select an existing file."

                    else -> null
                }
            }

            "terminal" -> {
                val directory = config.workingDirectory
                if (!directory.isNullOrBlank() && !checkPath(directory, true)) {
                    "The terminal's saved startup folder is missing or inaccessible. Edit the bookmark before opening."
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }

    private fun createTab(config: TabConfig): TabInfo {
        val id = "bookmark-${UUID.randomUUID()}"
        return when (config.type) {
            "browser" -> {
                FluckTabInfo(
                    id = id,
                    typeId = FluckTabType.typeId,
                    _title = config.title,
                    url = requireNotNull(config.url),
                    faviconCacheKey = config.faviconCacheKey,
                )
            }

            "editor" -> {
                EditorTabInfo(id = id, title = config.title, filePath = requireNotNull(config.filePath))
            }

            "terminal" -> {
                TerminalTabInfo(
                    id = id,
                    title = config.title,
                    initialCommand = config.initialCommand,
                    workingDirectory = config.workingDirectory,
                )
            }

            "jupyter" -> {
                JupyterTabInfo.create(requireNotNull(config.filePath), config.title)
            }

            else -> {
                error("Unsupported bookmark type")
            }
        }
    }

    private fun missingTool(type: String): BookmarkOpenResult {
        val name =
            when (type) {
                "browser" -> "Fluck Browser"
                "editor" -> "Editor"
                "terminal" -> "Terminal"
                else -> "Jupyter Notebook"
            }
        return failure("$name is unavailable. Enable or install it in Tools, then open the bookmark again.")
    }

    private fun failure(message: String) = BookmarkOpenResult(success = false, message = message)
}

internal fun bookmarkTabType(type: String): TabTypeId? =
    when (type) {
        "browser" -> FluckTabType.typeId
        "editor" -> CodeEditorTabType.typeId
        "terminal" -> TerminalTabType.typeId
        "jupyter" -> JupyterTabInfo.TYPE_ID
        else -> null
    }

/** Terminal startup configuration is not the identity of a live process; never reuse it. */
internal fun matchesResource(
    config: TabConfig,
    tab: TabInfo,
): Boolean =
    when (config.type) {
        "browser" -> tab is FluckTabInfo && tab.currentUrl == config.url
        "editor" -> tab is EditorTabInfo && tab.filePath == config.filePath
        "jupyter" -> tab is JupyterTabInfo && tab.filePath == config.filePath
        else -> false
    }

/** Synchronous save eligibility; opening additionally checks current provider and path availability. */
internal fun bookmarkTargetProblem(config: TabConfig): String? =
    when {
        bookmarkTabType(config.type) == null -> {
            "This tab type cannot be restored from a bookmark."
        }

        config.type == "browser" && config.url.isNullOrBlank() -> {
            "Save a web address before bookmarking this tab."
        }

        config.type in setOf("editor", "jupyter") && config.filePath.isNullOrBlank() -> {
            "Save this file before creating a bookmark."
        }

        config.type in setOf("editor", "jupyter") && !File(requireNotNull(config.filePath)).isAbsolute -> {
            "Choose an absolute saved file path for this bookmark."
        }

        config.type == "terminal" && !config.workingDirectory.isNullOrBlank() &&
            !File(
                requireNotNull(config.workingDirectory),
            ).isAbsolute
        -> {
            "Choose an absolute startup folder for this terminal bookmark."
        }

        else -> {
            null
        }
    }
