package editor.ui

import editor.codeintel.CodeIntelService
import editor.codeintel.CompletionRequest
import editor.codeintel.DefinitionRequest
import editor.codeintel.EditorIntelligenceService
import editor.codeintel.NavigationTarget
import editor.codeintel.ReferenceRequest
import editor.codeintel.TextPosition
import editor.codeintel.TextRange
import editor.codeintel.TokensRequest
import editor.lsp.LspService
import editor.lib.FoundToken
import editor.lib.Position
import editor.lib.SelectionRange
import editor.lib.TextBuffer
import editor.lib.handleKeyForBuffer
import editor.lib.handleMouseToBuffer
import editor.mime.MimeTypeResult
import editor.lib.PositionState
import editor.grammars.SyntaxProvider
import editor.app.EditorSessionState
import react.BaseComponent
import react.ClippedCanvasRenderer
import react.Color
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import editor.ui.SearchReplaceBar.SearchCommand

class CodeEditorView(
    styleSheet: StyleSheet,
    private val buffer: TextBuffer = TextBuffer(),
    private val syntaxProvider: SyntaxProvider? = null,
    private val codeIntelIndexer: CodeIntelService? = null,
    private val codeIntel: EditorIntelligenceService? = null,
    private val lsp: LspService? = null,
    private val navigationHandler: ((String, Position) -> Unit)? = null,
    private val projectRootProvider: () -> java.nio.file.Path = {
        Paths.get("").toAbsolutePath().normalize()
    }
) : BaseComponent(styleSheet) {
    private companion object {
        private const val HOVER_INFO_DELAY_MS = 500L
        private const val MAX_DEFINITION_PREVIEW_LINES = 24
        private const val MAX_USAGE_PATH_LENGTH = 48
        private const val MAX_USAGE_VISIBLE_ROWS = 8
        private const val MAX_USAGE_POPUP_HEIGHT = 10
        private const val TAB_WIDTH = 4
    }

    private var localStyleSheet: StyleSheet = styleSheet
    private var filePath: String = ""
    private var mime: String? = null
    private var language: String? = null
    private var grammarAvailable: Boolean = false
    private var grammarLanguage: String? = null
    private var scrollTop: Int = 0
    private var lastLayout: VisualLayout? = null
    private var lastLayoutVersion: Long = -1L
    private var lastLayoutWidth: Int = -1
    private var dragging = false
    private var lastCols: Int = 0
    private var lastTotalCols: Int = 0
    private var lastRows: Int = 0
    private var searchVisible = false
    private var searchHasFocus = false
    private var searchBar: SearchReplaceBar = SearchReplaceBar(styleSheet, this::handleSearchAction)
    private var readOnly: Boolean = false
    private var lastIndexedVersion: Long = -1
    private var usagePopup: UsagePopup? = null
    private var renderedPopup: RenderedPopup? = null
    private var usageScrollOffset: Int = 0
    private var usageDragging = false
    private var usageDragStartY = 0
    private var usageDragStartOffset = 0
    private var suggestionPopup: SuggestionPopup? = null
    private var renderedSuggestion: RenderedPopup? = null
    private var infoPopup: InfoPopup? = null
    private var renderedInfo: RenderedPopup? = null
    private var hoveredDefinitionControl: DefinitionControl? = null
    private var hoveredDefinitionArrow: Arrow? = null
    private var hoverInfoCandidate: HoverInfoCandidate? = null
    private var hoveredUsageIndex: Int = -1
    private var selectedUsageIndex: Int = -1
    private var hoveredSuggestionIndex: Int = -1
    private var hoveredUsage: HoveredUsage? = null
    private var rerenderOnce: Boolean = false
    private val previewLineNumbers: MutableList<Int> = mutableListOf()
    private var cachedCodeIntelTokensByLine: Map<Int, List<editor.grammars.Token>> = emptyMap()
    private var cachedCodeIntelTokenVersion: Long = -1L
    private var paintedText: String = ""
    private var observedSemanticRevision: Long? = null

    fun textContent(): String = buffer.text()

    fun loadTextContent(text: String) {
        buffer.loadText(text)
        scrollTop = 0
        lastIndexedVersion = -1
        clearCodeIntelTokenCache()
        triggerCodeIntel(force = true, immediate = true)
        syncLsp(open = true)
        rerenderOnce = true
    }

    private fun triggerCodeIntel(force: Boolean = false, immediate: Boolean = false) {
        val service = codeIntelIndexer ?: return
        if (filePath.isEmpty()) return
        val version = buffer.version()
        if (!force && version == lastIndexedVersion) return
        lastIndexedVersion = version
        val lang = grammarLanguage ?: language
        if (!lang.isNullOrBlank()) {
            if (immediate) {
                service.indexDocumentNow(filePath, lang, buffer.text(), version)
            } else {
                service.indexDocument(filePath, lang, buffer.text(), version)
            }
        }
        lsp?.changeDocument(filePath, buffer.text(), version.toInt())
    }

    private fun syncLsp(open: Boolean) {
        val lang = grammarLanguage ?: language
        if (filePath.isEmpty() || lang.isNullOrBlank()) return
        lsp?.startForLanguage(lang)
        if (open) {
            lsp?.openDocument(filePath, lang, buffer.text(), buffer.version().toInt())
        } else {
            lsp?.changeDocument(filePath, buffer.text(), buffer.version().toInt())
        }
    }

    fun captureState(lastModifiedMillis: Long? = null): EditorSessionState? {
        if (filePath.isEmpty()) return null
        return EditorSessionState(
            path = filePath,
            buffer = buffer.exportState(),
            scrollTop = scrollTop,
            mime = mime,
            language = language,
            grammarLanguage = grammarLanguage,
            grammarAvailable = grammarAvailable,
            lastModifiedMillis = lastModifiedMillis
        )
    }

    fun restoreState(state: EditorSessionState) {
        filePath = state.path
        mime = state.mime
        language = state.language
        grammarAvailable = state.grammarAvailable
        grammarLanguage = state.grammarLanguage ?: state.language
        if ((language.isNullOrBlank() || grammarLanguage.isNullOrBlank()) && filePath.isNotBlank()) {
            val ext = filePath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val inferred = ext.takeIf { it.isNotEmpty() }?.let { syntaxProvider?.languageForExtension(it) }
            if (!inferred.isNullOrBlank()) {
                if (language.isNullOrBlank()) language = inferred
                if (grammarLanguage.isNullOrBlank()) grammarLanguage = inferred
                grammarAvailable = grammarAvailable || syntaxProvider?.languages()?.contains(inferred) == true
            }
        }
        buffer.restoreState(state.buffer)
        scrollTop = state.scrollTop.coerceAtLeast(0)
        lastIndexedVersion = -1
        clearCodeIntelTokenCache()
        triggerCodeIntel(force = true, immediate = true)
        syncLsp(open = true)
        rerenderOnce = true
    }

    fun loadVirtualContent(label: String, content: String, language: String? = null) {
        filePath = label
        mime = "text/plain"
        this.language = language
        grammarLanguage = language
        grammarAvailable = language != null && syntaxProvider?.languages()?.contains(language) == true
        buffer.loadText(content)
        scrollTop = 0
        lastIndexedVersion = -1
        clearCodeIntelTokenCache()
        triggerCodeIntel(force = true)
        syncLsp(open = true)
    }

    fun setReadOnly(value: Boolean) {
        readOnly = value
    }

    fun currentPath(): String = filePath

    fun openFile(
        path: String,
        detection: MimeTypeResult? = null,
        grammarAvailable: Boolean = false,
        grammarLanguage: String? = null
    ) {
        val content = try {
            File(path).readText()
        } catch (_: Exception) {
            ""
        }
        buffer.loadText(content)
        filePath = path
        this.mime = detection?.mime
        this.language = detection?.language
        this.grammarAvailable = grammarAvailable
        this.grammarLanguage = grammarLanguage ?: detection?.language
        scrollTop = 0
        lastIndexedVersion = -1
        clearCodeIntelTokenCache()
        triggerCodeIntel(force = true)
        syncLsp(open = true)
    }

    override fun render(canvas: CanvasRenderer) {
        val rows = canvas.rows().coerceAtLeast(1)
        val totalCols = canvas.cols().coerceAtLeast(1)
        val previewCols = computePreviewWidth(totalCols)
        val cols = (totalCols - previewCols).coerceAtLeast(1)
        lastCols = cols
        lastTotalCols = totalCols
        lastRows = rows
        val headerStyle = localStyleSheet.getStyle("code-header").withDefaults()
        val baseBody = localStyleSheet.getStyle("code-body").withDefaults()
        val gutterStyle = localStyleSheet.getStyle("code-gutter").withDefaults(baseBody.fg, baseBody.bg)
        val selectionStyle = localStyleSheet.getStyle("code-selection")
            .withDefaults(fg = baseBody.bg ?: gutterStyle.bg, bg = baseBody.fg ?: gutterStyle.fg)
        val cursorStyle = localStyleSheet.getStyle("code-cursor")
            .withDefaults(fg = baseBody.bg ?: gutterStyle.bg, bg = baseBody.fg ?: gutterStyle.fg)
        val searchMatchStyle = localStyleSheet.getStyle("code-search-match").withDefaults(baseBody.fg, baseBody.bg)
        val searchActiveMatchStyle =
            localStyleSheet.getStyle("code-search-active").withDefaults(searchMatchStyle.fg, searchMatchStyle.bg)
        val bodyStyle = baseBody
        val tabMarkerStyle = localStyleSheet.getStyle("code-tab").withDefaults(
            fg = baseBody.bg?.let { bg ->
                val fg = baseBody.fg ?: bg
                Color((bg.r + fg.r) / 2, (bg.g + fg.g) / 2, (bg.b + fg.b) / 2)
            } ?: baseBody.fg,
            bg = baseBody.bg
        )
        val effectiveSearchVisible = searchVisible
        val searchHeight = if (effectiveSearchVisible) searchBar.preferredHeight().coerceAtMost(rows - 1) else 0
        val bodyStartRow = 1 + searchHeight

        // Header bar with file path and mime
        canvas.withStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[unknown]"
            val grammarInfo = grammarLabel()
            val langLabel = language?.let { "· $it$grammarInfo" } ?: grammarInfo
            val saveMarker = if (buffer.isDirty()) "*" else " "
            val label = "$saveMarker${filePath.ifEmpty { "[no file]" }} $mimeLabel $langLabel"
                .take(totalCols)
            drawText(0, 0, label.padEnd(totalCols, ' '))
        }

        if (effectiveSearchVisible && searchHeight > 0) {
            val clipped = ClippedCanvasRenderer(
                base = canvas,
                offsetX = 0,
                offsetY = 1,
                width = cols,
                height = searchHeight
            )
            val state = buffer.searchState()
            searchBar.updateMatchLabel(state.activeIndex, state.matchCount)
            searchBar.render(clipped)
        }

        val bodyRows = (rows - bodyStartRow).coerceAtLeast(0)
        if (bodyRows == 0) return

        val gutterWidth = computeGutterWidth()
        val contentCols = (cols - gutterWidth).coerceAtLeast(0)
        val layout = cachedLayout(contentCols)
        val lines = layout.rawLines
        val visualLines = layout.visualLines
        val currentText = buffer.text()
        if (paintedText != currentText) {
            cachedCodeIntelTokensByLine = remapHighlightTokens(
                paintedText, currentText, cachedCodeIntelTokensByLine.values.flatten()
            ).groupBy { it.line }
            paintedText = currentText
        }

        val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
        scrollTop = scrollTop.coerceIn(0, maxOffset)

        val visibleStart = scrollTop.coerceIn(0, layout.wrapped.size)
        val visibleEnd = (visibleStart + bodyRows).coerceAtMost(layout.wrapped.size)
        val visibleRows = layout.wrapped.subList(visibleStart, visibleEnd)
        if (visibleRows.isEmpty()) return

        val selection = buffer.selectionRange()
        val searchTokensByLine = buffer.foundTokens().groupBy { it.line }
        val firstVisibleLine = visibleRows.first().lineIndex
        val lastVisibleLine = visibleRows.last().lineIndex
        val tokensByLine = if (grammarAvailable && syntaxProvider != null && grammarLanguage != null) {
            val sliceEnd = (lastVisibleLine + 1).coerceAtMost(lines.size)
            val lineSlice = lines.subList(firstVisibleLine, sliceEnd)
            syntaxProvider.tokensForLines(firstVisibleLine, lineSlice, grammarLanguage!!).groupBy { it.line }
        } else {
            emptyMap()
        }
        val codeIntelTokensByLine = if (codeIntel != null && filePath.isNotEmpty()) {
            val sliceEnd = (lastVisibleLine + 1).coerceAtMost(lines.size)
            val lineSlice = lines.subList(firstVisibleLine, sliceEnd)
            val freshTokens = codeIntel.tokens(
                TokensRequest(
                    filePath = filePath,
                    language = grammarLanguage ?: language,
                    startLine = firstVisibleLine,
                    lines = lineSlice,
                    version = buffer.version()
                )
            )
            val semanticReady = codeIntelIndexer?.semanticVersion(filePath) == buffer.version()
            val visibleLineRange = firstVisibleLine..lastVisibleLine
            val tokens = when {
                freshTokens.isNotEmpty() || semanticReady -> {
                    cachedCodeIntelTokensByLine = cachedCodeIntelTokensByLine
                        .filterKeys { it !in visibleLineRange } + freshTokens.groupBy { it.line }
                    cachedCodeIntelTokenVersion = buffer.version()
                    freshTokens
                }
                cachedCodeIntelTokenVersion >= 0L && cachedCodeIntelTokenVersion < buffer.version() -> {
                    // Keep the previous semantic paint while the debounced index catches up.
                    // Tree-sitter/regex lexical tokens continue to render immediately.
                    visibleLineRange.flatMap { cachedCodeIntelTokensByLine[it].orEmpty() }
                }
                else -> freshTokens
            }
            tokens.groupBy { it.line }
        } else {
            emptyMap()
        }

        canvas.withStyle(bodyStyle) {
            drawRect(0, bodyStartRow, cols, bodyRows)
            visibleRows.forEachIndexed { idx, wrapped ->
                val lineNumber = wrapped.lineIndex
                val y = bodyStartRow + idx
                val lineText = lines.getOrElse(lineNumber) { "" }
                val visualLine = visualLines.getOrElse(lineNumber) { VisualLine("") }

                canvas.withStyle(gutterStyle) {
                    val g = if (wrapped.startColumn == 0) {
                        (lineNumber + 1).toString().padStart(gutterWidth - 1, ' ') + " "
                    } else {
                        " ".repeat(gutterWidth)
                    }
                    drawText(0, y, g.take(gutterWidth))
                }

                if (contentCols <= 0) return@forEachIndexed
                val visualStart = visualLine.visualColumn(wrapped.startColumn)
                val visualEnd = visualLine.visualColumn(wrapped.endColumn)
                val chunkText = visualLine.visualText.substring(
                    visualStart.coerceIn(0, visualLine.visualText.length),
                    visualEnd.coerceIn(visualStart, visualLine.visualText.length)
                )
                val tokens = tokensByLine[lineNumber] ?: emptyList()
                val chunkTokens = sliceVisualTokens(tokens, visualLine, wrapped.startColumn, wrapped.endColumn)
                val intelTokens = codeIntelTokensByLine[lineNumber] ?: emptyList()
                val chunkIntelTokens = sliceVisualTokens(intelTokens, visualLine, wrapped.startColumn, wrapped.endColumn)
                val selectionCols = selectionRangeForLine(selection, lineNumber, lineText)
                val chunkSelection = selectionCols?.let {
                    trimVisualRangeToChunk(it, visualLine, wrapped.startColumn, wrapped.endColumn)
                }
                val chunkTabRanges = visualLine.tabRanges.mapNotNull { range ->
                    val start = maxOf(range.first, visualStart)
                    val end = minOf(range.last + 1, visualEnd)
                    if (start >= end) null else start - visualStart until end - visualStart
                }
                val highlights = searchTokensByLine[lineNumber]?.mapNotNull {
                    trimVisualHighlightToChunk(it, visualLine, wrapped.startColumn, wrapped.endColumn)
                } ?: emptyList()
                val hoveredUsageRange = hoveredUsage?.takeIf { it.line == lineNumber }?.let { hu ->
                    val start = maxOf(hu.startColumn, wrapped.startColumn)
                    val end = minOf(hu.endColumn, wrapped.endColumn)
                    if (start < end) {
                        visualLine.visualColumn(start) - visualStart until visualLine.visualColumn(end) - visualStart
                    } else null
                }
                renderLineWithTokens(
                    canvas = this,
                    text = chunkText,
                    y = y,
                    startX = gutterWidth,
                    maxCols = contentCols,
                    tokens = chunkTokens,
                    baseStyle = bodyStyle,
                    overlayTokens = chunkIntelTokens,
                    selection = chunkSelection,
                    selectionStyle = selectionStyle,
                    highlights = highlights,
                    highlightStyle = searchMatchStyle,
                    activeHighlightStyle = searchActiveMatchStyle,
                    hoveredUsageRange = hoveredUsageRange,
                    tabRanges = chunkTabRanges,
                    tabStyle = tabMarkerStyle
                )
            }
        }

        val cursor = buffer.cursorPosition()
        val cursorRow = visualRowForPosition(cursor, layout)
        val cursorChunk = chunkForPosition(cursor, layout)
        val cy = bodyStartRow + (cursorRow - scrollTop)
        if (cursorChunk != null && cy in bodyStartRow until rows) {
            val cursorLine = layout.visualLines.getOrElse(cursorChunk.lineIndex) { VisualLine("") }
            val cursorCol = (cursorLine.visualColumn(cursor.column) - cursorLine.visualColumn(cursorChunk.startColumn))
                .coerceAtLeast(0)
            val cx = (gutterWidth + cursorCol).coerceAtMost(cols - 1)
            val visualColumn = cursorLine.visualColumn(cursor.column)
            val cursorEnd = if (cursor.column < cursorLine.rawText.length) {
                cursorLine.visualColumn(cursor.column + 1)
            } else {
                visualColumn
            }
            val ch = if (cursorEnd > visualColumn) {
                cursorLine.visualText.substring(visualColumn, cursorEnd)
            } else {
                " "
            }
            canvas.withStyle(cursorStyle) {
                drawText(cx, cy, ch)
            }
        }

        renderUsagePopup(canvas, bodyStartRow, gutterWidth, cols, rows, layout)
        renderSuggestionPopup(canvas, bodyStartRow, gutterWidth, cols, rows, layout)
        renderInfoPopup(canvas, bodyStartRow, gutterWidth, cols, rows, layout)

        if (previewCols > 0) {
            previewLineNumbers.clear()
            val previewCanvas = ClippedCanvasRenderer(
                base = canvas,
                offsetX = cols,
                offsetY = 0,
                width = previewCols,
                height = rows
            )
            val previewGutter = 0
            val cursorLine = buffer.cursorPosition().line
            val cursorBlock = cursorLine / 4
            val previewOffset = (cursorBlock - (bodyRows / 2)).coerceAtLeast(0)
            val previewHighlight =
                localStyleSheet.getStyle("code-selection")
                    .withDefaults(fg = bodyStyle.bg ?: gutterStyle.bg, bg = bodyStyle.fg ?: gutterStyle.fg)
            if (bodyStartRow > 1) {
                previewCanvas.withStyle(bodyStyle) {
                    drawRect(0, 1, previewCols, bodyStartRow - 1)
                }
            }
            renderBrailleBlocks(
                previewCanvas,
                lines,
                previewGutter,
                bodyStartRow,
                bodyRows,
                gutterStyle,
                bodyStyle,
                previewOffset,
                cursorLine,
                previewHighlight
            ) { rowIdx, lineNumber ->
                if (rowIdx >= 0) {
                    while (previewLineNumbers.size <= rowIdx) previewLineNumbers.add(-1)
                    previewLineNumbers[rowIdx] = (lineNumber - 1).coerceAtLeast(0)
                }
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") {
            val now = event.timeMs ?: System.currentTimeMillis()
            val infoShown = maybeShowHoverInfo(now)
            if (rerenderOnce) {
                rerenderOnce = false
                lastLayout = null
                return true
            }
            val semanticRevision = codeIntelIndexer?.semanticRevision(filePath)
            if (semanticRevision != observedSemanticRevision) {
                observedSemanticRevision = semanticRevision
                return true
            }
            if (infoShown) return true
        }
        val totalCols = (event.cols ?: lastTotalCols).coerceAtLeast(1)
        val previewCols = computePreviewWidth(totalCols)
        val cols = (totalCols - previewCols).coerceAtLeast(1)
        val rows = (event.rows ?: lastRows).coerceAtLeast(1)
        val gutterWidth = computeGutterWidth()
        val contentCols = (cols - gutterWidth).coerceAtLeast(0)
        val layout = cachedLayout(contentCols)
        val effectiveSearchVisible = searchVisible
        val searchHeight = if (effectiveSearchVisible) searchBar.preferredHeight().coerceAtMost(rows - 1) else 0
        val bodyRows = (rows - 1 - searchHeight).coerceAtLeast(0)
        val bodyStartRow = 1 + searchHeight

        // Popups own mouse input while open.  In particular, moving from the
        // source token into a popup must not be interpreted as editor input.
        if (event.kind.startsWith("mouse") && handlePopupMouse(event)) return true
        if (event.kind == "key_down" && handlePopupKeys(event.key)) return true

        if (event.kind == "key_down" && event.ctrl && event.key?.lowercase() == "f") {
            val selection = if (buffer.hasSelection()) buffer.selectionText() else ""
            openSearch(selection)
            return true
        }

        if (effectiveSearchVisible && event.kind.startsWith("mouse")) {
            val y = event.y ?: 0
            if (y in 1 until bodyStartRow) {
                val forwarded = event.alterCopy(
                    UIEvent(
                        kind = event.kind,
                        x = event.x,
                        y = (event.y ?: 0) - 1,
                        relX = event.relX,
                        relY = event.relY,
                        button = event.button,
                        scrollDelta = event.scrollDelta,
                        key = event.key,
                        ctrl = event.ctrl,
                        alt = event.alt,
                        shift = event.shift,
                        meta = event.meta,
                        focusId = event.focusId,
                        cols = event.cols,
                        rows = searchHeight,
                        raw = event.raw,
                        timeMs = event.timeMs
                    )
                )
                val handled = searchBar.dispatch(forwarded)
                if (handled) {
                    searchHasFocus = effectiveSearchVisible
                    syncSearchUiFromBuffer()
                    ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
                    return true
                }
            }
        }

        if (effectiveSearchVisible && event.kind == "key_down" && searchHasFocus) {
            val handled = searchBar.dispatch(event)
            if (handled) {
                syncSearchUiFromBuffer()
                ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
            }
            return true
        }

        when (event.kind) {
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: return false
                val multiplier = if (event.alt) 9 else 3
                val scrollDelta = delta * multiplier
                val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
                val prev = scrollTop
                scrollTop = (scrollTop - scrollDelta).coerceIn(0, maxOffset)
                suggestionPopup = null
                renderedSuggestion = null
                return scrollTop != prev
            }
            "mouse_down" -> {
                val y = event.y ?: return false
                if (y == 0) return false // header
                if (effectiveSearchVisible && y in 1 until bodyStartRow) return true
                val ex = event.x ?: return false
                if (ex >= cols) {
                    if (y < bodyStartRow || y >= rows) return false
                    val blockIdx = (y - bodyStartRow).coerceAtLeast(0)
                    val targetLine = previewLineNumbers.getOrNull(blockIdx) ?: return false
                    val targetPos = Position(targetLine, 0)
                    val targetRow = visualRowForPosition(targetPos, layout)
                    val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
                    scrollTop = targetRow.coerceIn(0, maxOffset)
                    buffer.moveCursorTo(targetPos, expand = false)
                    return true
                }
                clearHoverInfo()
                if (handleUsageClick(event)) return true
                if (handleSuggestionClick(event)) return true
                if (event.ctrl && handleCtrlClick(event, bodyStartRow, layout, gutterWidth)) {
                    return true
                }
                searchHasFocus = false
                dragging = true
                return handleMouse(
                    event = event,
                    bodyStartRow = bodyStartRow,
                    layout = layout,
                    gutterWidth = gutterWidth,
                    startSelection = true,
                    extendSelection = false
                )
            }
            "mouse_up" -> {
                dragging = false
                // hide popup on click release outside
                val rp = renderedPopup
                if (rp != null && event.x != null && event.y != null) {
                    if (event.x !in rp.x until (rp.x + rp.width) || event.y !in rp.y until (rp.y + rp.height)) {
                        usagePopup = null
                        renderedPopup = null
                    }
                }
                val rs = renderedSuggestion
                if (rs != null && event.x != null && event.y != null) {
                    if (event.x !in rs.x until (rs.x + rs.width) || event.y !in rs.y until (rs.y + rs.height)) {
                        suggestionPopup = null
                        renderedSuggestion = null
                    }
                }
                return true
            }
            "mouse_move" -> {
                if (!dragging) {
                    if ((event.x ?: 0) >= cols) return false
                    if (updatePopupHover(event)) return true
                    var consumed = false
                    if (updateHoveredUsage(event, bodyStartRow, layout, gutterWidth)) consumed = true
                    if (updateHoverInfo(event, bodyStartRow, layout, gutterWidth)) consumed = true
                    return consumed
                }
                hoveredUsage = null
                clearHoverInfo()
                return handleMouse(
                    event = event,
                    bodyStartRow = bodyStartRow,
                    layout = layout,
                    gutterWidth = gutterWidth,
                    startSelection = false,
                    extendSelection = true
                )
            }
            "key_down" -> {
                val key = event.key?.lowercase()
                if (readOnly) {
                    val mutating = when (key) {
                        "backspace", "delete", "enter" -> true
                        else -> false
                    } || (!event.ctrl && !event.alt && (event.key?.length == 1)) ||
                        (event.alt && (key == "x" || key == "v" || key == "u" || key == "r"))
                    if (mutating) return true
                }
                if (event.ctrl && key == "s") {
                    if (filePath.isNotEmpty()) buffer.saveToFile(filePath)
                    return true
                }
                if (key == "pageup" || key == "pagedown") {
                    val delta = if (key == "pageup") -bodyRows else bodyRows
                    val cursor = buffer.cursorPosition()
                    val currentRow = visualRowForPosition(cursor, layout)
                    val chunk = chunkForPosition(cursor, layout) ?: return true
                    val column = layout.visualLines[cursor.line].visualColumn(cursor.column) -
                        layout.visualLines[cursor.line].visualColumn(chunk.startColumn)
                    val target = layout.wrapped[(currentRow + delta).coerceIn(layout.wrapped.indices)]
                    val targetLine = layout.visualLines[target.lineIndex]
                    buffer.moveCursorTo(Position(target.lineIndex, targetLine.rawColumn(
                        targetLine.visualColumn(target.startColumn) + column
                    ).coerceAtMost(target.endColumn)), expand = event.shift)
                    scrollTop = (scrollTop + delta).coerceIn(0, (layout.wrapped.size - bodyRows).coerceAtLeast(0))
                    ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
                    return true
                }
                if (event.ctrl && (key == " " || key == "space")) {
                    openSuggestions()
                    return true
                }
                if (key == "\u0000") {
                    openSuggestions()
                    return true
                }
                val beforeCursor = buffer.cursorPosition()
                val beforeSelection = if (buffer.hasSelection()) buffer.selectionText() else null
                val beforeText = buffer.text()
                val changed = handleKeyForBuffer(buffer, event, singleLine = false)
                val afterCursor = buffer.cursorPosition()
                val afterSelection = if (buffer.hasSelection()) buffer.selectionText() else null
                val moved = beforeCursor != afterCursor || beforeSelection != afterSelection
                val textChanged = beforeText != buffer.text()
                ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
                if (textChanged) {
                    triggerCodeIntel()
                    suggestionPopup = null
                    renderedSuggestion = null
                }
                return changed || moved || textChanged
            }
        }
        return true
    }

    private fun handleCtrlClick(
        event: UIEvent,
        bodyStartRow: Int,
        layout: VisualLayout,
        gutterWidth: Int
    ): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        val popupBox = renderedPopup
        if (popupBox != null && ex in popupBox.x until (popupBox.x + popupBox.width) &&
            ey in popupBox.y until (popupBox.y + popupBox.height)
        ) {
            val idx = ey - popupBox.y - 1
            val popup = usagePopup
            if (popup != null && idx in popup.entries.indices) {
                val entry = popup.entries[idx]
                if (entry.file.isNotBlank()) {
                    navigationHandler?.invoke(entry.file, Position(entry.line, entry.column))
                }
                usagePopup = null
                renderedPopup = null
            }
            return true
        }

        val relY = ey - bodyStartRow
        val visualIndex = (scrollTop + relY).coerceAtLeast(0)
        val wrapped = layout.wrapped.getOrNull(visualIndex) ?: return false
        val line = wrapped.lineIndex
        val lineText = layout.rawLines.getOrElse(line) { "" }
        val visualLine = layout.visualLines.getOrElse(line) { VisualLine(lineText) }
        val col = visualLine.rawColumn(visualLine.visualColumn(wrapped.startColumn) + (ex - gutterWidth).coerceAtLeast(0))
        val token = identifyCodeIntelToken(line, col, lineText) ?: return false

        usagePopup = null
        renderedPopup = null
        suggestionPopup = null
        renderedSuggestion = null
        infoPopup = null
        renderedInfo = null
        hoverInfoCandidate = null

        val name = token.text
        val isDeclaration = token.scopes.any { it.contains("codeintel.declaration") }
        if (isDeclaration) {
            val refs = codeIntel?.references(
                ReferenceRequest(
                    filePath = filePath,
                    language = grammarLanguage ?: language,
                    position = TextPosition(line, col),
                    symbol = name
                )
            ).orEmpty()
            val sourceCache = mutableMapOf<String, List<String>>()
            val usageEntries = refs.map {
                if (it.filePath.isBlank()) {
                    UsageEntry("", -1, -1, it.name ?: "")
                } else {
                    val label = buildUsageLabel(
                        filePath = it.filePath,
                        line = it.range.start.line,
                        column = it.range.start.column,
                        identifier = name,
                        sourceCache = sourceCache
                    )
                    UsageEntry(it.filePath, it.range.start.line, it.range.start.column, label)
                }
            }
            if (usageEntries.isNotEmpty()) {
                usagePopup = UsagePopup(Position(line, col), usageEntries)
                usageScrollOffset = 0
                hoveredUsageIndex = -1
                selectedUsageIndex = 0
            }
        } else {
            openDefinition(name, Position(line, col))
        }
        return true
    }

    private fun identifyCodeIntelToken(line: Int, column: Int, lineText: String): editor.grammars.Token? {
        val service = codeIntel ?: return null
        if (filePath.isEmpty()) return null
        val tokens = service.tokens(
            TokensRequest(
                filePath = filePath,
                language = grammarLanguage ?: language,
                startLine = line,
                lines = listOf(lineText),
                version = buffer.version()
            )
        )
        return tokens.firstOrNull { column in it.start until it.end }
    }

    private fun openDefinition(name: String, position: Position) {
        val lang = grammarLanguage ?: language
        val hits = codeIntel?.definitions(
            DefinitionRequest(
                filePath = filePath,
                language = lang,
                position = TextPosition(position.line, position.column),
                symbol = name
            )
        ).orEmpty()
        val target = pickBestDefinition(hits, name) ?: return
        navigationHandler?.invoke(target.filePath, Position(target.range.start.line, target.range.start.column))
    }

    private fun pickBestDefinition(defs: List<NavigationTarget>, identifier: String): NavigationTarget? {
        if (defs.isEmpty()) return null
        val lower = identifier.lowercase()
        val currentFile = File(filePath).absoluteFile.normalize()
        fun score(def: NavigationTarget): Int {
            val file = File(def.filePath).absoluteFile.normalize()
            val fileName = file.nameWithoutExtension.lowercase()
            var s = 0
            if (!fileName.contains(lower)) s += 1
            if (file == currentFile) s += 1
            return s
        }
        return defs.minByOrNull { score(it) }
    }

    private fun handleSuggestionClick(event: UIEvent): Boolean {
        val rs = renderedSuggestion ?: return false
        val popup = suggestionPopup ?: return false
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        if (ex !in rs.x until (rs.x + rs.width) || ey !in rs.y until (rs.y + rs.height)) return false
        val idx = ey - rs.y - 1
        if (idx !in popup.entries.indices) return true
        val entry = popup.entries[idx]
        applySuggestion(entry.name, popup.prefix)
        suggestionPopup = null
        renderedSuggestion = null
        return true
    }

    private fun handleUsageClick(event: UIEvent): Boolean {
        val rp = renderedPopup ?: return false
        val popup = usagePopup ?: return false
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        if (ex !in rp.x until (rp.x + rp.width) || ey !in rp.y until (rp.y + rp.height)) return false
        val rel = ey - rp.y
        val idx = if (rel in 1..usageVisibleCount(rp)) usageScrollOffset + rel - 1 else -1
        if (idx !in popup.entries.indices) return true
        selectedUsageIndex = idx
        val entry = popup.entries[idx]
        if (entry.file.isNotBlank()) {
            navigationHandler?.invoke(entry.file, Position(entry.line, entry.column))
        }
        usagePopup = null
        renderedPopup = null
        return true
    }

    private fun applySuggestion(suggestion: String, prefix: String) {
        val cursor = buffer.cursorPosition()
        val startCol = (cursor.column - prefix.length).coerceAtLeast(0)
        val startPos = Position(cursor.line, startCol)
        if (prefix.isNotEmpty()) {
            buffer.startSelection(startPos)
            buffer.selectTo(cursor)
            buffer.deleteBackspace()
        }
        buffer.insertText(suggestion)
        val newCursor = Position(cursor.line, startCol + suggestion.length)
        buffer.moveCursorTo(newCursor, expand = false)
    }

    private fun clearCodeIntelTokenCache() {
        cachedCodeIntelTokensByLine = emptyMap()
        cachedCodeIntelTokenVersion = -1L
    }

    private fun openSuggestions() {
        val prefix = currentPrefix()
        val entries = suggestionEntries(prefix)
        if (entries.isEmpty()) {
            suggestionPopup = null
            renderedSuggestion = null
            return
        }
        hoveredSuggestionIndex = 0
        suggestionPopup = SuggestionPopup(buffer.cursorPosition(), prefix, entries)
    }

    private fun currentPrefix(): String {
        val cursor = buffer.cursorPosition()
        val lines = buffer.text().split("\n")
        val lineText = lines.getOrElse(cursor.line) { "" }
        if (lineText.isEmpty() || cursor.column == 0) return ""
        val start = lineText.take(cursor.column).takeLastWhile { it.isLetterOrDigit() || it == '_' }
        return start
    }

    private fun suggestionEntries(prefix: String): List<SuggestionEntry> {
        val names = mutableSetOf<String>()
        val results = mutableListOf<SuggestionEntry>()

        fun add(name: String, detail: String? = null) {
            if (name.isBlank()) return
            if (names.add(name)) results.add(SuggestionEntry(name, detail))
        }

        val lang = grammarLanguage ?: language
        val completions = codeIntel?.completions(
            CompletionRequest(
                filePath = filePath,
                language = lang,
                position = TextPosition(buffer.cursorPosition().line, buffer.cursorPosition().column),
                prefix = prefix
            )
        ).orEmpty()
        completions.forEach { add(it.label, it.detail) }

        // A provider result is authoritative for its context (notably receiver
        // completion). Text scanning remains only as a compatibility fallback.
        if (completions.isEmpty() && !isMemberAccessAtCursor(prefix)) {
            val locals = buffer.text()
            val regex = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\b")
            regex.findAll(locals).forEach { mr ->
                val name = mr.groupValues[1]
                if (prefix.isEmpty() || name.startsWith(prefix)) add(name, "local")
            }
        }

        return results.take(50)
    }

    private fun isMemberAccessAtCursor(prefix: String): Boolean {
        val cursor = buffer.cursorPosition()
        val line = buffer.text().split("\n").getOrElse(cursor.line) { return false }
        val beforePrefix = line.take((cursor.column - prefix.length).coerceAtLeast(0)).trimEnd()
        return beforePrefix.endsWith('.')
    }

    private fun handlePopupMouse(event: UIEvent): Boolean {
        if (event.kind == "mouse_scroll" && usagePopup != null) {
            val popup = usagePopup ?: return true
            val visible = renderedPopup?.let(::usageVisibleCount) ?: MAX_USAGE_VISIBLE_ROWS
            usageScrollOffset = (usageScrollOffset - (event.scrollDelta ?: 0)).coerceIn(
                0, (popup.entries.size - visible).coerceAtLeast(0)
            )
            hoveredUsageIndex = -1
            clampUsageSelectionToVisible(visible)
            return true
        }
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        val usageBox = renderedPopup
        val infoBox = renderedInfo
        val suggestionBox = renderedSuggestion
        fun inside(box: RenderedPopup?): Boolean =
            box != null && ex in box.x until (box.x + box.width) && ey in box.y until (box.y + box.height)

        if (event.kind == "mouse_scroll" && infoPopup != null) {
            if (inside(infoBox)) return true
            infoPopup = null
            renderedInfo = null
            hoverInfoCandidate = null
            hoveredDefinitionControl = null
            hoveredDefinitionArrow = null
            return false
        }

        if (event.kind == "mouse_move" && usageDragging && usageBox != null) {
            val popup = usagePopup ?: return true
            val visible = usageVisibleCount(usageBox)
            val maxOffset = (popup.entries.size - visible).coerceAtLeast(0)
            val trackHeight = visible.coerceAtLeast(1)
            val delta = (ey - usageDragStartY) * maxOffset / (trackHeight - 1).coerceAtLeast(1)
            usageScrollOffset = (usageDragStartOffset + delta).coerceIn(0, maxOffset)
            hoveredUsageIndex = -1
            clampUsageSelectionToVisible(visible)
            return true
        }
        if (event.kind == "mouse_move" && inside(usageBox)) {
            updatePopupHover(event)
            return true
        }
        if (event.kind == "mouse_move" && usagePopup != null) {
            hoveredUsageIndex = -1
            return true
        }
        if (event.kind == "mouse_move" && inside(infoBox)) {
            updatePopupHover(event)
            return true
        }
        if (event.kind == "mouse_move" && inside(suggestionBox)) {
            updatePopupHover(event)
            return true
        }
        if (event.kind == "mouse_down") {
            if (inside(usageBox)) {
                if (ex == usageBox!!.x + usageBox.width - 1) {
                    val rel = ey - usageBox.y
                    val visible = usageVisibleCount(usageBox)
                    if (rel in 1..visible) {
                        handleUsageScrollbarClick(event)
                        usageDragging = true
                        usageDragStartY = ey
                        // Track clicks reposition immediately; drag deltas start
                        // from that new position rather than the old offset.
                        usageDragStartOffset = usageScrollOffset
                    }
                    return true
                }
                return handleUsageClick(event)
            }
            if (inside(infoBox)) return handleInfoClick(event)
            if (inside(suggestionBox)) return handleSuggestionClick(event)
            if (usagePopup != null || infoPopup != null || suggestionPopup != null) {
                usagePopup = null
                renderedPopup = null
                infoPopup = null
                renderedInfo = null
                suggestionPopup = null
                renderedSuggestion = null
                hoverInfoCandidate = null
                hoveredDefinitionControl = null
                hoveredDefinitionArrow = null
                selectedUsageIndex = -1
                return true
            }
        }
        if (event.kind == "mouse_up" && usageDragging) {
            usageDragging = false
            return true
        }
        if (event.kind == "mouse_up" && (inside(usageBox) || inside(infoBox) || inside(suggestionBox))) return true
        return false
    }

    private fun usageVisibleCount(box: RenderedPopup): Int = (box.height - 2).coerceAtLeast(0)

    private fun clampUsageSelectionToVisible(visible: Int) {
        val popup = usagePopup ?: return
        if (popup.entries.isEmpty() || visible <= 0) return
        selectedUsageIndex = selectedUsageIndex.coerceIn(
            usageScrollOffset,
            minOf(popup.entries.lastIndex, usageScrollOffset + visible - 1)
        )
    }

    private fun handleUsageScrollbarClick(event: UIEvent): Boolean {
        val box = renderedPopup ?: return false
        val popup = usagePopup ?: return false
        if (event.x != box.x + box.width - 1) return false
        val visible = usageVisibleCount(box)
        val maxOffset = (popup.entries.size - visible).coerceAtLeast(0)
        if (maxOffset == 0) return true
        val trackHeight = visible.coerceAtLeast(1)
        val pos = ((event.y ?: box.y) - box.y - 1).coerceIn(0, trackHeight - 1)
        usageScrollOffset = (pos * maxOffset / (trackHeight - 1).coerceAtLeast(1)).coerceIn(0, maxOffset)
        clampUsageSelectionToVisible(visible)
        return true
    }

    private fun handleInfoClick(event: UIEvent): Boolean {
        val box = renderedInfo ?: return false
        val popup = infoPopup ?: return false
        val y = (event.y ?: return true) - box.y
        if (y == 1 && popup.targets.size > 1) {
            val nav = "<< ${popup.selectedIndex + 1}/${popup.targets.size} >>"
            val navStart = box.x + 1
            val x = event.x ?: box.x
            val left = navStart until (navStart + 2)
            val rightStart = navStart + nav.length - 2
            val right = rightStart until (rightStart + 2)
            if (x in left) popup.selectedIndex = (popup.selectedIndex - 1).coerceAtLeast(0)
            if (x in right) popup.selectedIndex = (popup.selectedIndex + 1).coerceAtMost(popup.targets.lastIndex)
            return true
        }
        if (y == 2) {
            val location = popup.targets.getOrNull(popup.selectedIndex)?.let {
                "${relativePath(it.target.filePath)}:${it.target.range.start.line + 1}"
            }.orEmpty()
            val locationStart = box.x + 1
            val locationEnd = locationStart + location.length
            if ((event.x ?: box.x) !in locationStart until locationEnd) return true
            val target = popup.targets.getOrNull(popup.selectedIndex)?.target
            if (target != null) {
                navigationHandler?.invoke(target.filePath, Position(target.range.start.line, target.range.start.column))
                infoPopup = null
                renderedInfo = null
                hoverInfoCandidate = null
            }
            return true
        }
        return true
    }

    private fun updatePopupHover(event: UIEvent): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        val rp = renderedPopup
        val sp = renderedSuggestion
        var consumed = false
        if (rp != null && ey in rp.y until (rp.y + rp.height) && ex in rp.x until (rp.x + rp.width)) {
            val rel = ey - rp.y
            val visible = usageVisibleCount(rp)
            val popup = usagePopup
            val rowIndex = if (rel in 1..visible) usageScrollOffset + rel - 1 else -1
            val entry = popup?.entries?.getOrNull(rowIndex)
            val needsScrollbar = popup != null && popup.entries.size > visible
            val selected = rowIndex == selectedUsageIndex
            val textWidth = (rp.width - (if (needsScrollbar) 3 else 2) - (if (selected) 1 else 0)).coerceAtLeast(0)
            val textStart = rp.x + if (selected) 2 else 1
            hoveredUsageIndex = if (
                entry != null && entry.file.isNotBlank() && textWidth > 0 &&
                ex in textStart until (textStart + entry.label.length.coerceAtMost(textWidth))
            ) rowIndex else -1
            consumed = true
        } else {
            hoveredUsageIndex = -1
        }
        if (sp != null && ey in sp.y until (sp.y + sp.height) && ex in sp.x until (sp.x + sp.width)) {
            hoveredSuggestionIndex = (ey - sp.y - 1).coerceIn(0, (suggestionPopup?.entries?.lastIndex ?: -1))
            consumed = true
        } else {
            hoveredSuggestionIndex = -1
        }
        val ip = renderedInfo
        if (ip != null && ey in ip.y until (ip.y + ip.height) && ex in ip.x until (ip.x + ip.width)) {
            val relY = ey - ip.y
            val popup = infoPopup
            val nav = popup?.let { "<< ${it.selectedIndex + 1}/${it.targets.size} >>" }.orEmpty()
            val navStart = ip.x + 1
            val location = popup?.targets?.getOrNull(popup.selectedIndex)?.let {
                "${relativePath(it.target.filePath)}:${it.target.range.start.line + 1}"
            }.orEmpty()
            val leftHit = ex in navStart until (navStart + 2)
            val rightHit = ex in (navStart + nav.length - 2) until (navStart + nav.length)
            hoveredDefinitionControl = when {
                relY == 1 && popup != null && popup.targets.size > 1 &&
                    (leftHit || rightHit) ->
                    DefinitionControl.NAVIGATION
                relY == 2 && ex in navStart until (navStart + location.length) -> DefinitionControl.LOCATION
                else -> null
            }
            hoveredDefinitionArrow = when {
                hoveredDefinitionControl != DefinitionControl.NAVIGATION -> null
                leftHit -> Arrow.LEFT
                rightHit -> Arrow.RIGHT
                else -> null
            }
            consumed = true
        } else {
            hoveredDefinitionControl = null
            hoveredDefinitionArrow = null
        }
        return consumed
    }

    private fun updateHoveredUsage(event: UIEvent, bodyStartRow: Int, layout: VisualLayout, gutterWidth: Int): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return clearHoveredUsage()
        if (ey < bodyStartRow) return clearHoveredUsage()
        if (ex < gutterWidth) return clearHoveredUsage()
        val relY = ey - bodyStartRow
        val visualIndex = (scrollTop + relY).coerceAtLeast(0)
        val wrapped = layout.wrapped.getOrNull(visualIndex) ?: return clearHoveredUsage()
        val lineText = layout.rawLines.getOrElse(wrapped.lineIndex) { "" }
        val visualLine = layout.visualLines.getOrElse(wrapped.lineIndex) { VisualLine(lineText) }
        val relX = (ex - gutterWidth).coerceAtLeast(0)
        val col = visualLine.rawColumn(visualLine.visualColumn(wrapped.startColumn) + relX)
        val token = identifyCodeIntelToken(wrapped.lineIndex, col, lineText)
        val usageToken = token?.takeIf { t -> t.scopes.any { scope -> scope.contains("codeintel.usage") } }
        val newHover = usageToken?.let { HoveredUsage(wrapped.lineIndex, it.start, it.end) }
        if (newHover == hoveredUsage) return false
        hoveredUsage = newHover
        return true
    }

    private fun clearHoverInfo(): Boolean {
        var changed = false
        if (infoPopup != null || renderedInfo != null) {
            infoPopup = null
            renderedInfo = null
            changed = true
        }
        if (hoverInfoCandidate != null) {
            hoverInfoCandidate = null
            changed = true
        }
        return changed
    }

    private fun updateHoverInfo(event: UIEvent, bodyStartRow: Int, layout: VisualLayout, gutterWidth: Int): Boolean {
        val ex = event.x ?: return clearHoverInfo()
        val ey = event.y ?: return clearHoverInfo()
        if (ey < bodyStartRow || ex < gutterWidth) return clearHoverInfo()
        val relY = ey - bodyStartRow
        val visualIndex = (scrollTop + relY).coerceAtLeast(0)
        val wrapped = layout.wrapped.getOrNull(visualIndex) ?: return clearHoverInfo()
        val lineText = layout.rawLines.getOrElse(wrapped.lineIndex) { "" }
        val visualLine = layout.visualLines.getOrElse(wrapped.lineIndex) { VisualLine(lineText) }
        val relX = (ex - gutterWidth).coerceAtLeast(0)
        val col = visualLine.rawColumn(visualLine.visualColumn(wrapped.startColumn) + relX)
        val token = identifyCodeIntelToken(wrapped.lineIndex, col, lineText) ?: return clearHoverInfo()
        val key = Triple(wrapped.lineIndex, token.start, token.end)
        val now = event.timeMs ?: System.currentTimeMillis()
        val candidate = hoverInfoCandidate
        if (candidate == null || candidate.key != key) {
            hoverInfoCandidate = HoverInfoCandidate(key, token.text, now, Position(wrapped.lineIndex, token.start))
            infoPopup = null
            renderedInfo = null
            return true
        }
        if (infoPopup != null) return false
        if (now - candidate.startedAt < HOVER_INFO_DELAY_MS) return false
        val popup = buildInfoPopup(candidate)
        if (popup != null) {
            infoPopup = popup
            return true
        }
        return false
    }

    private fun buildInfoPopup(candidate: HoverInfoCandidate): InfoPopup? {
        val lang = grammarLanguage ?: language
        val defs = codeIntel?.definitions(
            DefinitionRequest(
                filePath = filePath,
                language = lang,
                position = TextPosition(candidate.anchor.line, candidate.anchor.column),
                symbol = candidate.name
            )
        ).orEmpty()
        val distinct = defs.distinctBy { target ->
            listOf(
                File(target.filePath).absoluteFile.normalize().toString(),
                target.range.start.line,
                target.range.start.column,
                target.range.end.line,
                target.range.end.column
            )
        }
        if (distinct.isEmpty()) return null
        return InfoPopup(
            anchor = candidate.anchor,
            targets = distinct.map { DefinitionTarget(it, loadDefinitionPreview(it)) }
        )
    }

    private fun loadDefinitionPreview(target: NavigationTarget): DefinitionPreview? {
        val sameFile = runCatching {
            Paths.get(target.filePath).toAbsolutePath().normalize() == Paths.get(filePath).toAbsolutePath().normalize()
        }.getOrDefault(false)
        val source = if (sameFile) buffer.text() else {
            runCatching { Files.readString(Paths.get(target.filePath)) }.getOrNull()
        } ?: return null
        // Legacy providers only report the identifier span. Expand that fallback
        // to the complete declaration line so hover remains useful for languages
        // without a semantic adapter yet.
        val range = target.definitionRange ?: TextRange(
            start = TextPosition(target.range.start.line, 0),
            end = TextPosition(target.range.start.line, Int.MAX_VALUE)
        )
        val lines = source.split('\n')
        if (lines.isEmpty()) return null
        val firstLine = range.start.line.coerceIn(0, lines.lastIndex)
        val lastLine = (if (range.end.column == 0 && range.end.line > firstLine) range.end.line - 1 else range.end.line)
            .coerceIn(firstLine, lines.lastIndex)
        val selected = lines.subList(firstLine, (lastLine + 1).coerceAtMost(firstLine + MAX_DEFINITION_PREVIEW_LINES))
        if (selected.isEmpty()) return null
        return DefinitionPreview(
            filePath = target.filePath,
            language = target.tsLanguage ?: language,
            version = codeIntelIndexer?.semanticVersion(target.filePath) ?: 0L,
            startLine = firstLine,
            lines = selected
        )
    }

    private fun maybeShowHoverInfo(now: Long): Boolean {
        if (infoPopup != null || usagePopup != null) return false
        val candidate = hoverInfoCandidate ?: return false
        if (now - candidate.startedAt < HOVER_INFO_DELAY_MS) return false
        val popup = buildInfoPopup(candidate) ?: return false
        infoPopup = popup
        return true
    }

    private fun clearHoveredUsage(): Boolean {
        if (hoveredUsage == null) return false
        hoveredUsage = null
        return true
    }

    private fun handlePopupKeys(key: String?): Boolean {
        val k = key?.lowercase() ?: return false
        val hasUsage = usagePopup != null
        val hasSuggestion = suggestionPopup != null && renderedSuggestion != null
        val hasInfo = infoPopup != null
        if (!hasUsage && !hasSuggestion && !hasInfo) return false
        if (hasInfo && !hasUsage && !hasSuggestion && k != "escape") return false

        fun clampUsage(delta: Int) {
            val popup = usagePopup ?: return
            val max = popup.entries.lastIndex
            if (max < 0) return
            selectedUsageIndex = (if (selectedUsageIndex < 0) 0 else selectedUsageIndex + delta).coerceIn(0, max)
            hoveredUsageIndex = -1
        }

        fun clampSuggestion(delta: Int) {
            val popup = suggestionPopup ?: return
            val max = popup.entries.lastIndex
            if (max < 0) return
            hoveredSuggestionIndex = (if (hoveredSuggestionIndex < 0) 0 else hoveredSuggestionIndex + delta).coerceIn(0, max)
        }

        when (k) {
            "escape" -> {
                usagePopup = null
                renderedPopup = null
                suggestionPopup = null
                renderedSuggestion = null
                infoPopup = null
                renderedInfo = null
                hoverInfoCandidate = null
                hoveredUsageIndex = -1
                hoveredSuggestionIndex = -1
                selectedUsageIndex = -1
                hoveredDefinitionControl = null
                hoveredDefinitionArrow = null
                return true
            }
            "up" -> {
                if (hasUsage) {
                    clampUsage(-1)
                    usageScrollOffset = usageScrollOffset.coerceAtMost(selectedUsageIndex)
                }
                if (hasSuggestion) clampSuggestion(-1)
                return true
            }
            "down" -> {
                if (hasUsage) {
                    clampUsage(1)
                    val visible = renderedPopup?.let(::usageVisibleCount) ?: MAX_USAGE_VISIBLE_ROWS
                    if (selectedUsageIndex >= usageScrollOffset + visible) usageScrollOffset = selectedUsageIndex - visible + 1
                }
                if (hasSuggestion) clampSuggestion(1)
                return true
            }
            "enter", "return" -> {
                if (hasUsage && selectedUsageIndex >= 0) {
                    val popup = usagePopup
                    if (popup != null && selectedUsageIndex in popup.entries.indices) {
                        val entry = popup.entries[selectedUsageIndex]
                        if (entry.file.isNotBlank()) {
                            navigationHandler?.invoke(entry.file, Position(entry.line, entry.column))
                        }
                        usagePopup = null
                        renderedPopup = null
                        return true
                    }
                }
                if (hasSuggestion && hoveredSuggestionIndex >= 0) {
                    val popup = suggestionPopup
                    if (popup != null && hoveredSuggestionIndex in popup.entries.indices) {
                        val entry = popup.entries[hoveredSuggestionIndex]
                        applySuggestion(entry.name, popup.prefix)
                        suggestionPopup = null
                        renderedSuggestion = null
                        return true
                    }
                }
            }
            "pageup" -> {
                if (hasUsage) {
                    val visible = renderedPopup?.let(::usageVisibleCount) ?: MAX_USAGE_VISIBLE_ROWS
                    clampUsage(-visible)
                    usageScrollOffset = (usageScrollOffset - visible).coerceAtLeast(0)
                    return true
                }
            }
            "pagedown" -> {
                if (hasUsage) {
                    val visible = renderedPopup?.let(::usageVisibleCount) ?: MAX_USAGE_VISIBLE_ROWS
                    clampUsage(visible)
                    val maxOffset = ((usagePopup?.entries?.size ?: 0) - visible).coerceAtLeast(0)
                    usageScrollOffset = (usageScrollOffset + visible).coerceAtMost(maxOffset)
                    return true
                }
            }
            "home" -> {
                if (hasUsage) {
                    selectedUsageIndex = 0
                    hoveredUsageIndex = -1
                    usageScrollOffset = 0
                    return true
                }
            }
            "end" -> {
                if (hasUsage) {
                    val last = usagePopup?.entries?.lastIndex ?: 0
                    selectedUsageIndex = last
                    hoveredUsageIndex = -1
                    val visible = renderedPopup?.let(::usageVisibleCount) ?: MAX_USAGE_VISIBLE_ROWS
                    usageScrollOffset = (last - visible + 1).coerceAtLeast(0)
                    return true
                }
            }
        }
        if (hasUsage) return true
        return false
    }

    private fun ensureCursorVisible(
        totalRows: Int,
        searchHeight: Int,
        layout: VisualLayout? = lastLayout,
        gutterWidth: Int = computeGutterWidth()
    ) {
        val bodyRows = (totalRows - 1 - searchHeight).coerceAtLeast(0)
        if (bodyRows == 0) return
        val contentCols = (lastCols - gutterWidth).coerceAtLeast(0)
        val activeLayout = layout ?: cachedLayout(contentCols)
        if (activeLayout.wrapped.isEmpty()) return
        val cursorRow = visualRowForPosition(buffer.cursorPosition(), activeLayout)
        val maxOffset = (activeLayout.wrapped.size - bodyRows).coerceAtLeast(0)
        val newScroll = when {
            cursorRow < scrollTop -> cursorRow
            cursorRow >= scrollTop + bodyRows -> cursorRow - bodyRows + 1
            else -> scrollTop
        }.coerceIn(0, maxOffset)
        scrollTop = newScroll
    }

    private fun handleMouse(
        event: UIEvent,
        bodyStartRow: Int,
        layout: VisualLayout,
        gutterWidth: Int,
        startSelection: Boolean,
        extendSelection: Boolean
    ): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        if (ey < bodyStartRow) return false
        if (layout.wrapped.isEmpty()) return false
        val relX = (ex - gutterWidth).coerceAtLeast(0)
        val relY = ey - bodyStartRow
        val visualIndex = (scrollTop + relY).coerceAtLeast(0)
        val wrapped = layout.wrapped.getOrNull(visualIndex) ?: layout.wrapped.last()
        val lineText = layout.rawLines.getOrElse(wrapped.lineIndex) { "" }
        val visualLine = layout.visualLines.getOrElse(wrapped.lineIndex) { VisualLine(lineText) }
        val targetCol = visualLine.rawColumn(visualLine.visualColumn(wrapped.startColumn) + relX)
        val pos = Position(wrapped.lineIndex, targetCol)
        if (startSelection) {
            buffer.startSelection(pos)
        } else if (extendSelection) {
            buffer.selectTo(pos)
        }
        buffer.moveCursorTo(pos, expand = extendSelection)
        hoveredUsage = null
        ensureCursorVisible((event.rows ?: 0), bodyStartRow - 1, layout, gutterWidth)
        return true
    }

    private fun computeGutterWidth(): Int {
        val digits = buffer.totalLines().coerceAtLeast(1).toString().length
        return (digits + 2).coerceAtMost(12) // number + space; cap to avoid overrun
    }

    private fun grammarLabel(): String {
        val lang = language ?: return ""
        return if (grammarAvailable) " (grammar:${grammarLanguage ?: lang})" else " (grammar:none)"
    }

    private fun scopeToStyleId(scope: String): String =
        scope.replace(' ', '_').replace(":", "-").replace(",", "-")

    private fun styleForToken(token: editor.grammars.Token, base: StyleSet): StyleSet {
        val scoped = token.scopes.fold(base.copy()) { style, scope ->
            style.merged(cachedStyle(scope))
        }.withDefaults(base.fg, base.bg)
        token.fg?.let { color ->
            val copy = scoped.copy()
            copy.fg = color
            if (copy.bg == null) copy.bg = base.bg
            return copy
        }
        val copy = scoped.copy()
        if (copy.bg == null) copy.bg = base.bg
        return copy
    }

    private val styleCache = mutableMapOf<String, StyleSet>()

    private fun cachedStyle(scope: String): StyleSet =
        styleCache.getOrPut(scope) {
            localStyleSheet.rules[scopeToStyleId(scope)] ?: StyleSet()
        }

    fun updateStyleSheet(styleSheet: StyleSheet) {
        this.localStyleSheet = styleSheet
        styleCache.clear()
        val searchState = buffer.searchState()
        searchBar = SearchReplaceBar(styleSheet, this::handleSearchAction).also {
            it.updateFromSearchState(searchState)
            it.setFocusEnabled(true)
        }
    }

    fun setSelection(start: Position, end: Position, center: Boolean = false) {
        buffer.startSelection(start)
        buffer.selectTo(end)
        val currentRows = lastRows.coerceAtLeast(1)
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(currentRows - 1) else 0
        val gutterWidth = computeGutterWidth()
        val layout = cachedLayout((lastCols - gutterWidth).coerceAtLeast(0))
        if (center) {
            val bodyRows = (currentRows - 1 - searchHeight).coerceAtLeast(1)
            val targetRow = visualRowForPosition(start, layout)
            val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
            scrollTop = (targetRow - bodyRows / 2).coerceIn(0, maxOffset)
        }
        ensureCursorVisible(currentRows, searchHeight, layout, gutterWidth)
    }

    fun restoreViewport(cursor: PositionState, scroll: Int) {
        val cols = lastCols.coerceAtLeast(1)
        val rows = lastRows.coerceAtLeast(1)
        val gutterWidth = computeGutterWidth()
        val layout = cachedLayout((cols - gutterWidth).coerceAtLeast(0))
        val lines = layout.rawLines
        val line = cursor.line.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val col = cursor.column.coerceIn(0, lines.getOrElse(line) { "" }.length)
        val pos = Position(line, col)
        buffer.moveCursorTo(pos, expand = false)
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(rows - 1) else 0
        val bodyRows = (rows - 1 - searchHeight).coerceAtLeast(1)
        val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
        scrollTop = scroll.coerceIn(0, maxOffset)
        ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
    }

    fun isDirty(): Boolean = buffer.isDirty()

    fun currentSelectionText(): String? = if (buffer.hasSelection()) buffer.selectionText() else null

    private fun openSearch(selectionText: String? = null) {
        searchVisible = true
        searchHasFocus = true
        if (!selectionText.isNullOrEmpty()) {
            buffer.updateSearch(Regex.escape(selectionText))
        }
        searchBar.updateFromSearchState(buffer.searchState())
    }

    private fun handleSearchAction(action: SearchCommand) {
        when (action) {
            is SearchCommand.Change -> buffer.updateSearch(action.query, action.replacement)
            SearchCommand.FindNext -> buffer.findNext()
            SearchCommand.FindAll -> buffer.findAll()
            SearchCommand.ReplaceOne -> buffer.replaceCurrent()
            SearchCommand.ReplaceAll -> buffer.replaceAll()
            SearchCommand.Close -> {
                searchVisible = false
                searchHasFocus = false
                return
            }
        }
        syncSearchUiFromBuffer()
        val currentRows = lastRows.coerceAtLeast(1)
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(currentRows - 1) else 0
        val gutterWidth = computeGutterWidth()
        val layout = cachedLayout((lastCols - gutterWidth).coerceAtLeast(0))
        triggerCodeIntel()
        ensureCursorVisible(currentRows, searchHeight, layout, gutterWidth)
    }

    private fun syncSearchUiFromBuffer() {
        val state = buffer.searchState()
        searchBar.updateFromSearchState(state)
    }

    private fun renderLineWithTokens(
        canvas: CanvasRenderer,
        text: String,
        y: Int,
        startX: Int,
        maxCols: Int,
        tokens: List<editor.grammars.Token>,
        overlayTokens: List<editor.grammars.Token> = emptyList(),
        baseStyle: StyleSet,
        selection: IntRange?,
        selectionStyle: StyleSet,
        highlights: List<FoundToken>,
        highlightStyle: StyleSet,
        activeHighlightStyle: StyleSet,
        hoveredUsageRange: IntRange? = null,
        tabRanges: List<IntRange> = emptyList(),
        tabStyle: StyleSet = baseStyle
    ) {
        if (maxCols <= 0) return
        val baseSegments = buildSegments(text, tokens, baseStyle)
        val withSemanticTokens = applyTokenOverlays(baseSegments, overlayTokens, baseStyle)
        val withHover = applyUsageHover(withSemanticTokens, hoveredUsageRange, baseStyle)
        val withHighlights = applyHighlights(withHover, highlights, highlightStyle, activeHighlightStyle)
        val withTabs = tabRanges.fold(withHighlights) { segments, range ->
            overlayRange(segments, range.first, range.last + 1, tabStyle)
        }
        val withSelection = applySelection(withTabs, selection, selectionStyle)
        withSelection.forEach { seg ->
            if (seg.start >= maxCols) return
            val drawEnd = minOf(seg.end, maxCols, text.length)
            if (drawEnd <= seg.start) return@forEach
            val part = text.substring(seg.start, drawEnd)
            if (part.isNotEmpty()) {
                canvas.withStyle(seg.style) {
                    drawText(startX + seg.start, y, part)
                }
            }
        }
    }

    private fun selectionRangeForLine(selection: SelectionRange?, lineIdx: Int, lineText: String): IntRange? {
        selection ?: return null
        if (lineIdx < selection.start.line || lineIdx > selection.end.line) return null
        val lineLength = lineText.length
        val startCol = if (lineIdx == selection.start.line) selection.start.column else 0
        val endCol = if (lineIdx == selection.end.line) selection.end.column else lineLength
        val start = startCol.coerceIn(0, lineLength)
        val end = endCol.coerceIn(0, lineLength)
        if (start >= end) return null
        return start until end
    }

    private data class StyledSegment(val start: Int, val end: Int, val style: StyleSet)
    private data class HoveredUsage(val line: Int, val startColumn: Int, val endColumn: Int)

    private fun computePreviewWidth(totalCols: Int): Int {
        if (totalCols < 40) return 0
        return (totalCols / 3).coerceIn(8, 20)
    }

    private fun renderBrailleBlocks(
        canvas: CanvasRenderer,
        lines: List<String>,
        gutterWidth: Int,
        bodyStartRow: Int,
        bodyRows: Int,
        gutterStyle: StyleSet,
        bodyStyle: StyleSet,
        blockOffset: Int,
        highlightLine: Int? = null,
        highlightStyle: StyleSet? = null,
        onRow: (rowIdx: Int, lineNumber: Int) -> Unit = { _, _ -> }
    ) {
        val contentCols = (canvas.cols() - gutterWidth).coerceAtLeast(0)
        if (contentCols <= 0 || bodyRows <= 0) return
        // Each braille cell represents 2 columns (x) and 4 rows (y) of source.
        // Horizontal squashing: pack two source columns into one braille cell.
        val cellsPerRow = contentCols.coerceAtLeast(1)
        val maxBlocks = bodyRows
        val totalBlocks = (lines.size + 3) / 4
        val offset = blockOffset.coerceIn(0, (totalBlocks - maxBlocks).coerceAtLeast(0))

        canvas.withStyle(bodyStyle) {
            drawRect(0, bodyStartRow, canvas.cols(), bodyRows)
        }

        for (blockIdx in 0 until bodyRows) {
            val block = offset + blockIdx
            if (block >= totalBlocks) break
            val y = bodyStartRow + blockIdx
            if (gutterWidth > 0) {
                val lineNumber = block * 4 + 1
                val number = lineNumber.toString().padStart(gutterWidth, ' ')
                canvas.withStyle(gutterStyle) {
                    drawText(0, y, number.take(gutterWidth).padEnd(gutterWidth, ' '))
                }
            }
            val lineNumber = block * 4 + 1
            onRow(blockIdx, lineNumber)
            val rowStyle = if (highlightLine != null && highlightLine in (block * 4) until (block * 4 + 4)) {
                highlightStyle ?: bodyStyle
            } else {
                bodyStyle
            }
            val sb = StringBuilder()
            for (cellX in 0 until cellsPerRow) {
                var mask = 0
                for (dy in 0 until 4) {
                    val srcLineIdx = block * 4 + dy
                    val srcLine = lines.getOrNull(srcLineIdx) ?: ""
                    val x0 = cellX * 2
                    val c1 = srcLine.getOrNull(x0) ?: ' '
                    val c2 = srcLine.getOrNull(x0 + 1) ?: ' '
                    if (c1 != ' ') mask = mask or dotMask(0, dy)
                    if (c2 != ' ') mask = mask or dotMask(1, dy)
                }
                sb.append((0x2800 + mask).toChar())
            }
            canvas.withStyle(rowStyle) {
                drawText(gutterWidth, y, sb.toString())
            }
        }
    }

    private fun dotMask(dx: Int, dy: Int): Int {
        return when (dy) {
            0 -> if (dx == 0) 0x01 else 0x08   // dots 1,4
            1 -> if (dx == 0) 0x02 else 0x10   // dots 2,5
            2 -> if (dx == 0) 0x04 else 0x20   // dots 3,6
            else -> if (dx == 0) 0x40 else 0x80 // dots 7,8
        }
    }

    private fun buildSegments(
        text: String,
        tokens: List<editor.grammars.Token>,
        baseStyle: StyleSet
    ): List<StyledSegment> {
        if (text.isEmpty()) return emptyList()
        if (tokens.isEmpty()) return listOf(StyledSegment(0, text.length, baseStyle))
        val segments = mutableListOf<StyledSegment>()
        var cursor = 0
        tokens.sortedBy { it.start }.forEach { tok ->
            val segStart = tok.start.coerceIn(0, text.length)
            val segEnd = tok.end.coerceIn(0, text.length)
            if (segStart > cursor) {
                segments.add(StyledSegment(cursor, segStart, baseStyle))
            }
            // Syntax providers may return overlapping matches (especially while
            // recovering from incomplete source). Never emit overlapping draw
            // ranges: they cause duplicated glyphs and terminal artifacts.
            val effectiveStart = maxOf(segStart, cursor)
            if (segEnd > effectiveStart) {
                val style = styleForToken(tok, baseStyle)
                segments.add(StyledSegment(effectiveStart, segEnd, style))
            }
            cursor = maxOf(cursor, segEnd)
            if (cursor >= text.length) return@forEach
        }
        if (cursor < text.length) {
            segments.add(StyledSegment(cursor, text.length, baseStyle))
        }
        return segments
    }

    private fun applySelection(
        segments: List<StyledSegment>,
        selection: IntRange?,
        selectionStyle: StyleSet
    ): List<StyledSegment> {
        selection ?: return segments
        if (segments.isEmpty()) return segments
        val selStart = selection.first
        val selEnd = selection.last + 1
        if (selStart >= selEnd) return segments

        val out = mutableListOf<StyledSegment>()
        segments.forEach { seg ->
            if (seg.end <= selStart || seg.start >= selEnd) {
                out.add(seg)
                return@forEach
            }
            if (seg.start < selStart) {
                out.add(StyledSegment(seg.start, selStart, seg.style))
            }
            val selectedStart = maxOf(seg.start, selStart)
            val selectedEnd = minOf(seg.end, selEnd)
            if (selectedStart < selectedEnd) {
                out.add(StyledSegment(selectedStart, selectedEnd, selectionStyle))
            }
            if (seg.end > selEnd) {
                out.add(StyledSegment(selEnd, seg.end, seg.style))
            }
        }
        return out
    }

    private fun applyHighlights(
        segments: List<StyledSegment>,
        highlights: List<FoundToken>,
        highlightStyle: StyleSet,
        activeHighlightStyle: StyleSet
    ): List<StyledSegment> {
        if (highlights.isEmpty()) return segments
        var current = segments
        highlights.sortedBy { it.startColumn }.forEach { token ->
            val style = if (token.active) activeHighlightStyle else highlightStyle
            current = overlayRange(current, token.startColumn, token.endColumn, style)
        }
        return current
    }

    private fun applyUsageHover(
        segments: List<StyledSegment>,
        hovered: IntRange?,
        baseStyle: StyleSet
    ): List<StyledSegment> {
        hovered ?: return segments
        val underlineStyle = baseStyle.copy().also { style ->
            val existing = style.textDecoration
            val parts = (existing?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()).toMutableSet()
            parts += "underline"
            style.textDecoration = parts.joinToString(" ")
        }
        return overlayRange(segments, hovered.first, hovered.last + 1, underlineStyle)
    }

    private fun applyTokenOverlays(
        segments: List<StyledSegment>,
        overlays: List<editor.grammars.Token>,
        baseStyle: StyleSet
    ): List<StyledSegment> {
        if (overlays.isEmpty()) return segments
        var current = segments
        overlays.sortedBy { it.start }.forEach { tok ->
            val style = styleForToken(tok, baseStyle)
            current = overlayRange(current, tok.start, tok.end, style)
        }
        return current
    }

    private fun overlayRange(
        segments: List<StyledSegment>,
        start: Int,
        end: Int,
        style: StyleSet
    ): List<StyledSegment> {
        if (segments.isEmpty() || start >= end) return segments
        val out = mutableListOf<StyledSegment>()
        segments.forEach { seg ->
            if (seg.end <= start || seg.start >= end) {
                out.add(seg)
                return@forEach
            }
            if (seg.start < start) {
                out.add(StyledSegment(seg.start, start, seg.style))
            }
            val overlayStart = maxOf(seg.start, start)
            val overlayEnd = minOf(seg.end, end)
            if (overlayStart < overlayEnd) {
                out.add(StyledSegment(overlayStart, overlayEnd, style))
            }
            if (seg.end > end) {
                out.add(StyledSegment(end, seg.end, seg.style))
            }
        }
        return out
    }

    private fun trimSelectionToChunk(selection: IntRange, chunkStart: Int, chunkEnd: Int): IntRange? {
        val selStart = maxOf(selection.first, chunkStart)
        val selEndExclusive = minOf(selection.last + 1, chunkEnd)
        if (selStart >= selEndExclusive) return null
        return selStart - chunkStart until selEndExclusive - chunkStart
    }

    private fun sliceVisualTokens(
        tokens: List<editor.grammars.Token>,
        line: VisualLine,
        chunkStart: Int,
        chunkEnd: Int
    ): List<editor.grammars.Token> {
        val visualStart = line.visualColumn(chunkStart)
        val visualEnd = line.visualColumn(chunkEnd)
        return tokens.mapNotNull { token ->
            val start = maxOf(token.start, chunkStart)
            val end = minOf(token.end, chunkEnd)
            if (start >= end) return@mapNotNull null
            token.copy(
                start = line.visualColumn(start) - visualStart,
                end = line.visualColumn(end) - visualStart
            )
        }.filter { it.start < it.end && it.start < visualEnd - visualStart }
    }

    private fun trimVisualRangeToChunk(
        range: IntRange,
        line: VisualLine,
        chunkStart: Int,
        chunkEnd: Int
    ): IntRange? {
        val start = maxOf(range.first, chunkStart)
        val end = minOf(range.last + 1, chunkEnd)
        if (start >= end) return null
        val visualStart = line.visualColumn(chunkStart)
        return line.visualColumn(start) - visualStart until line.visualColumn(end) - visualStart
    }

    private fun trimVisualHighlightToChunk(
        token: FoundToken,
        line: VisualLine,
        chunkStart: Int,
        chunkEnd: Int
    ): FoundToken? {
        val start = maxOf(token.startColumn, chunkStart)
        val end = minOf(token.endColumn, chunkEnd)
        if (start >= end) return null
        val visualStart = line.visualColumn(chunkStart)
        return token.copy(
            startColumn = line.visualColumn(start) - visualStart,
            endColumn = line.visualColumn(end) - visualStart
        )
    }

    private fun buildLayout(lines: List<String>, contentCols: Int): VisualLayout {
        val width = contentCols.coerceAtLeast(1)
        val wrapped = mutableListOf<WrappedLine>()
        val visualLines = lines.map { VisualLine(it) }
        val lineOffsets = IntArray(lines.size)
        val wrapCounts = IntArray(lines.size)
        visualLines.forEachIndexed { idx, line ->
            lineOffsets[idx] = wrapped.size
            val len = line.rawText.length
            if (line.visualText.isEmpty()) {
                wrapped.add(WrappedLine(idx, 0, 0))
                wrapCounts[idx] = 1
            } else {
                var start = 0
                var count = 0
                while (start < len) {
                    val visualStart = line.visualColumn(start)
                    val targetVisualEnd = minOf(visualStart + width, line.visualText.length)
                    var end = start + 1
                    while (end < len && line.visualColumn(end + 1) <= targetVisualEnd) end++
                    wrapped.add(WrappedLine(idx, start, end))
                    start = end
                    count++
                }
                wrapCounts[idx] = maxOf(1, count)
            }
        }
        if (lines.isEmpty()) {
            wrapped.add(WrappedLine(0, 0, 0))
        }
        return VisualLayout(lines, visualLines, wrapped, lineOffsets, wrapCounts, width)
    }

    private fun visualRowForPosition(pos: Position, layout: VisualLayout): Int {
        if (layout.wrapped.isEmpty()) return 0
        val line = pos.line.coerceIn(0, layout.rawLines.lastIndex)
        val offset = layout.lineOffsets.getOrElse(line) { 0 }
        val wraps = layout.wrapCounts.getOrElse(line) { 1 }.coerceAtLeast(1)
        var low = 0
        var high = wraps - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (layout.wrapped[offset + mid].startColumn <= pos.column) low = mid else high = mid - 1
        }
        val chunkIndex = low
        return offset + chunkIndex
    }

    private fun visualColumnAt(pos: Position, layout: VisualLayout): Int {
        val line = pos.line.coerceIn(0, (layout.rawLines.size - 1).coerceAtLeast(0))
        return layout.visualLines.getOrElse(line) { VisualLine("") }.visualColumn(pos.column)
    }

    private fun chunkForPosition(pos: Position, layout: VisualLayout): WrappedLine? {
        return layout.wrapped.getOrNull(visualRowForPosition(pos, layout))
    }

    private fun cachedLayout(contentCols: Int): VisualLayout {
        val width = contentCols.coerceAtLeast(1)
        val cached = lastLayout
        if (cached != null && lastLayoutVersion == buffer.version() && lastLayoutWidth == width) {
            return cached
        }
        val layout = buildLayout(buffer.text().split("\n"), width)
        lastLayout = layout
        lastLayoutVersion = buffer.version()
        lastLayoutWidth = width
        return layout
    }

    private fun renderUsagePopup(
        canvas: CanvasRenderer,
        bodyStartRow: Int,
        gutterWidth: Int,
        cols: Int,
        rows: Int,
        layout: VisualLayout
    ) {
        val popup = usagePopup ?: run {
            renderedPopup = null
            return
        }
        val anchorRow = visualRowForPosition(popup.anchor, layout)
        val screenRow = bodyStartRow + (anchorRow - scrollTop)
        val x = (gutterWidth + visualColumnAt(popup.anchor, layout)).coerceAtLeast(gutterWidth)
        val maxLabel = popup.entries.maxOfOrNull { it.label.length } ?: 0
        val height = minOf(MAX_USAGE_POPUP_HEIGHT, rows - bodyStartRow, popup.entries.size + 2)
        if (height < 3) {
            renderedPopup = null
            return
        }
        val visible = usageVisibleCount(RenderedPopup(0, 0, 1, height))
        val needsScrollbar = popup.entries.size > visible
        val width = (maxLabel + 2 + if (needsScrollbar) 1 else 0)
            .coerceAtMost(cols.coerceAtLeast(1))
            .coerceAtLeast(1)
        val finalX = x.coerceIn(0, (cols - width).coerceAtLeast(0))
        val maxY = (rows - height).coerceAtLeast(bodyStartRow)
        val finalY = if (screenRow + height <= rows) {
            screenRow.coerceIn(bodyStartRow, maxY)
        } else {
            (screenRow - height).coerceIn(bodyStartRow, maxY)
        }
        val style = localStyleSheet.getStyle("code-search-bar").withDefaults()
        val titleStyle = localStyleSheet.getStyle("definition-title").withDefaults(style.fg, style.bg)
        val delimiterStyle = localStyleSheet.getStyle("definition-delimiter").withDefaults(style.fg, style.bg)
        val scrollbarStyle = localStyleSheet.getStyle("code-popup-scrollbar").withDefaults(style.fg, style.bg)
        val linkStyle = style.copy().also { it.textDecoration = "underline" }
        usageScrollOffset = usageScrollOffset.coerceIn(0, (popup.entries.size - visible).coerceAtLeast(0))
        clampUsageSelectionToVisible(visible)
        canvas.withStyle(style) {
            drawRect(finalX, finalY, width, height)
            canvas.withStyle(titleStyle) {
                drawText(finalX, finalY, "usage sites".take(width).padEnd(width, ' '))
            }
            val entries = popup.entries.drop(usageScrollOffset).take(visible)
            entries.forEachIndexed { idx, entry ->
                val selected = usageScrollOffset + idx == selectedUsageIndex
                val textWidth = (width - (if (needsScrollbar) 3 else 2) - (if (selected) 1 else 0)).coerceAtLeast(0)
                val text = entry.label.take(textWidth).padEnd(textWidth, ' ')
                val textX = finalX + if (selected) 2 else 1
                if (selected && finalX + 1 < cols) drawText(finalX + 1, finalY + idx + 1, ">")
                if (textWidth > 0) drawText(textX, finalY + idx + 1, text)
                if (usageScrollOffset + idx == hoveredUsageIndex) {
                    if (textWidth > 0) canvas.withStyle(linkStyle) {
                        drawText(textX, finalY + idx + 1, entry.label.take(textWidth))
                    }
                }
            }
            canvas.withStyle(delimiterStyle) {
                drawText(finalX, finalY + height - 1, " ".repeat(width))
            }
            if (needsScrollbar) {
                val trackHeight = visible.coerceAtLeast(1)
                val maxOffset = (popup.entries.size - visible).coerceAtLeast(1)
                val indicatorRow = usageScrollOffset * (trackHeight - 1) / maxOffset
                val scrollbarX = finalX + width - 1
                canvas.withStyle(scrollbarStyle) {
                    (0 until trackHeight).forEach { row ->
                        drawText(scrollbarX, finalY + row + 1, if (row == indicatorRow) "█" else "│")
                    }
                }
            }
        }
        renderedPopup = RenderedPopup(finalX, finalY, width, height)
    }

    private fun renderSuggestionPopup(
        canvas: CanvasRenderer,
        bodyStartRow: Int,
        gutterWidth: Int,
        cols: Int,
        rows: Int,
        layout: VisualLayout
    ) {
        val popup = suggestionPopup ?: run {
            renderedSuggestion = null
            return
        }
        val anchorRow = visualRowForPosition(popup.anchor, layout)
        val screenRow = bodyStartRow + (anchorRow - scrollTop)
        val x = (gutterWidth + visualColumnAt(popup.anchor, layout)).coerceAtLeast(gutterWidth)
        val maxLabel = popup.entries.take(10).maxOfOrNull { it.name.length + (it.detail?.length ?: 0) + 3 } ?: 0
        val width = (maxLabel + 2).coerceAtMost((cols - x).coerceAtLeast(12))
        val height = (popup.entries.size + 1).coerceAtMost((rows - screenRow - 1).coerceAtLeast(2))
        if (height < 2) {
            renderedSuggestion = null
            return
        }
        val finalX = x.coerceIn(0, (cols - width).coerceAtLeast(0))
        val finalY = screenRow.coerceIn(bodyStartRow, (rows - height).coerceAtLeast(bodyStartRow))
        val style = localStyleSheet.getStyle("code-search-bar").withDefaults()
        val hoverStyle = localStyleSheet.getStyle("code-search-active").withDefaults(style.fg, style.bg)
        canvas.withStyle(style) {
            drawRect(finalX, finalY, width, height)
            val entries = popup.entries.take(height - 1)
            entries.forEachIndexed { idx, entry ->
                val label = buildString {
                    append(entry.name)
                    entry.detail?.let { append("  ").append(it) }
                }
                val text = label.take(width - 2).padEnd(width - 2, ' ')
                val rowStyle = if (idx == hoveredSuggestionIndex) hoverStyle else style
                canvas.withStyle(rowStyle) {
                    drawText(finalX + 1, finalY + idx + 1, text)
                }
            }
        }
        renderedSuggestion = RenderedPopup(finalX, finalY, width, height)
    }

    private fun renderInfoPopup(
        canvas: CanvasRenderer,
        bodyStartRow: Int,
        gutterWidth: Int,
        cols: Int,
        rows: Int,
        layout: VisualLayout
    ) {
        val popup = infoPopup ?: run {
            renderedInfo = null
            return
        }
        val anchorRow = visualRowForPosition(popup.anchor, layout)
        val screenRow = bodyStartRow + (anchorRow - scrollTop)
        val selected = popup.targets.getOrNull(popup.selectedIndex)
        val preview = selected?.preview
        val location = selected?.target?.let { "${relativePath(it.filePath)}:${it.range.start.line + 1}" } ?: "source unavailable"
        val nav = "<< ${popup.selectedIndex + 1}/${popup.targets.size} >>"
        val maxLabel = maxOf(
            location.length,
            nav.length,
            preview?.lines?.maxOfOrNull(String::length) ?: 0,
            if (preview == null) "source unavailable".length else 0
        )
        val width = (maxLabel + 2).coerceAtMost((cols - gutterWidth).coerceAtLeast(1)).coerceAtLeast(1)
        val contentRows = 3 + (preview?.lines?.size ?: 1)
        val height = (contentRows + 1).coerceAtMost((rows - bodyStartRow).coerceAtLeast(2))
        if (height < 4) {
            renderedInfo = null
            return
        }
        val x = (gutterWidth + visualColumnAt(popup.anchor, layout)).coerceIn(0, (cols - width).coerceAtLeast(0))
        var finalY = (screenRow - height).coerceAtLeast(bodyStartRow)
        if (finalY + height > rows) finalY = (rows - height).coerceAtLeast(bodyStartRow)
        val style = localStyleSheet.getStyle("code-search-bar").withDefaults()
        val titleStyle = localStyleSheet.getStyle("definition-title").withDefaults(style.fg, style.bg)
        val delimiterStyle = localStyleSheet.getStyle("definition-delimiter").withDefaults(style.fg, style.bg)
        val linkStyle = style.copy().also { it.textDecoration = "underline" }
        canvas.withStyle(style) {
            drawRect(x, finalY, width, height)
            canvas.withStyle(titleStyle) {
                drawText(x, finalY, "Definition".take(width).padEnd(width, ' '))
            }
            var row = 1
            val innerWidth = (width - 2).coerceAtLeast(0)
            val navText = nav.take(innerWidth).padEnd(innerWidth, ' ')
            if (innerWidth > 0) drawText(x + 1, finalY + row, navText)
            if (innerWidth > 0 && popup.targets.size > 1 && hoveredDefinitionControl == DefinitionControl.NAVIGATION) {
                canvas.withStyle(linkStyle) {
                    if (hoveredDefinitionControl == DefinitionControl.NAVIGATION) {
                        // Underline only the arrow glyph under the pointer;
                        // the count and padding remain ordinary popup text.
                        val pointer = hoveredDefinitionArrow
                        if (pointer == Arrow.LEFT) drawText(x + 1, finalY + row, "<<".take(innerWidth))
                        if (pointer == Arrow.RIGHT) {
                            val rightOffset = nav.length - 2
                            if (rightOffset < innerWidth) {
                                drawText(x + 1 + rightOffset, finalY + row, ">>".take(innerWidth - rightOffset))
                            }
                        }
                    }
                }
            }
            row++
            val locationText = location.take(innerWidth)
            if (row < height - 1 && innerWidth > 0) {
                drawText(x + 1, finalY + row, locationText.padEnd(innerWidth, ' '))
            }
            if (hoveredDefinitionControl == DefinitionControl.LOCATION && row < height - 1 && innerWidth > 0) {
                canvas.withStyle(linkStyle) { drawText(x + 1, finalY + row, locationText) }
            }
            row++
            if (preview != null) {
                val previewSyntax = if (syntaxProvider != null && !preview.language.isNullOrBlank()) {
                    syntaxProvider.tokensForLines(preview.startLine, preview.lines, preview.language!!).groupBy { it.line }
                } else {
                    emptyMap()
                }
                val previewSemantic = if (codeIntel != null && preview.version >= 0L) {
                    codeIntel.tokens(
                        TokensRequest(
                            filePath = preview.filePath,
                            language = preview.language,
                            startLine = preview.startLine,
                            lines = preview.lines,
                            version = preview.version
                        )
                    ).groupBy { it.line }
                } else {
                    emptyMap()
                }
                preview.lines.forEachIndexed { index, lineText ->
                    if (row >= height) return@forEachIndexed
                    val visualLine = VisualLine(lineText)
                    val previewTokens = sliceVisualTokens(
                        previewSyntax[preview.startLine + index].orEmpty(),
                        visualLine,
                        0,
                        lineText.length
                    )
                    val previewSemanticTokens = sliceVisualTokens(
                        previewSemantic[preview.startLine + index].orEmpty(),
                        visualLine,
                        0,
                        lineText.length
                    )
                    renderLineWithTokens(
                        canvas = this,
                        text = visualLine.visualText,
                        y = finalY + row,
                        startX = x + 1,
                        maxCols = width - 2,
                        tokens = previewTokens,
                        overlayTokens = previewSemanticTokens,
                        baseStyle = style,
                        selection = null,
                        selectionStyle = style,
                        highlights = emptyList(),
                        highlightStyle = style,
                        activeHighlightStyle = style,
                        tabRanges = visualLine.tabRanges,
                        tabStyle = localStyleSheet.getStyle("code-tab").withDefaults(style.fg, style.bg)
                    )
                    row++
                }
            } else if (row < height - 1 && innerWidth > 0) {
                drawText(x + 1, finalY + row, "source unavailable".take(innerWidth).padEnd(innerWidth, ' '))
            }
            canvas.withStyle(delimiterStyle) {
                drawText(x, finalY + height - 1, " ".repeat(width))
            }
        }
        renderedInfo = RenderedPopup(x, finalY, width, height)
    }

    private fun buildUsageLabel(
        filePath: String,
        line: Int,
        column: Int,
        identifier: String,
        sourceCache: MutableMap<String, List<String>> = mutableMapOf()
    ): String {
        val relPath = compactPath(relativePath(filePath))
        val lineText = readLineText(filePath, line, sourceCache)
        val snippet = snippetAround(lineText, column, identifier.length)
        return "$relPath:${line + 1}:${column + 1} | $snippet"
    }

    private fun relativePath(filePath: String): String {
        val root = runCatching { projectRootProvider().toAbsolutePath().normalize() }
            .getOrDefault(Paths.get("").toAbsolutePath().normalize())
        val abs = Paths.get(filePath).toAbsolutePath().normalize()
        return if (abs.startsWith(root)) {
            root.relativize(abs).toString()
        } else {
            filePath
        }
    }

    private fun compactPath(path: String, maxLength: Int = MAX_USAGE_PATH_LENGTH): String {
        val normalized = path.replace(File.separatorChar, '/')
        if (normalized.length <= maxLength) return normalized
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return normalized.take((maxLength - 1).coerceAtLeast(1)) + "…"
        val folders = parts.dropLast(1)
        val fileName = parts.last()
        val compactParts = buildList {
            add(folders.first())
            folders.drop(1).dropLast(1).forEach { folder -> add(folder.first().toString()) }
            if (folders.size > 1) add(folders.last())
            add(fileName)
        }
        val compact = compactParts.joinToString("/")
        if (compact.length <= maxLength) return compact

        // Preserve the complete filename while collapsing the path middle.
        val anchor = if (folders.size == 1) {
            "${folders.first()}/"
        } else {
            "${folders.first()}/…/${folders.last()}/"
        }
        val availableName = maxLength - anchor.length
        if (availableName > 1) {
            return anchor + fileName.take(availableName - 1) + "…"
        }
        return normalized.take((maxLength - 1).coerceAtLeast(1)) + "…"
    }

    private fun readLineText(filePath: String, line: Int, sourceCache: MutableMap<String, List<String>>): String {
        if (line < 0) return ""
        val sameFile = runCatching {
            Paths.get(filePath).toAbsolutePath().normalize() == Paths.get(this.filePath).toAbsolutePath().normalize()
        }.getOrDefault(false)
        val cacheKey = if (sameFile) "@current-buffer" else filePath
        val lines = sourceCache.getOrPut(cacheKey) {
            if (sameFile) {
                buffer.text().split('\n')
            } else {
            runCatching { Files.readString(Paths.get(filePath)).split('\n') }.getOrDefault(emptyList())
            }
        }
        return lines.getOrElse(line) { "" }
    }

    private fun snippetAround(lineText: String, column: Int, length: Int): String {
        if (lineText.isBlank()) return ""
        val safeLength = length.coerceAtLeast(1)
        val start = (column - 30).coerceAtLeast(0)
        val end = (column + safeLength + 30).coerceAtMost(lineText.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < lineText.length) "..." else ""
        return prefix + lineText.substring(start, end).trim() + suffix
    }

    private data class VisualLine(val rawText: String) {
        val visualText: String
        val tabRanges: List<IntRange>
        private val rawToVisual: IntArray

        init {
            val out = StringBuilder(rawText.length)
            val tabs = mutableListOf<IntRange>()
            rawToVisual = IntArray(rawText.length + 1)
            var column = 0
            rawText.forEachIndexed { index, ch ->
                rawToVisual[index] = column
                if (ch == '\t') {
                    // Keep the marker atomic: one logical tab is always rendered
                    // as exactly four terminal cells, regardless of its column.
                    val spaces = TAB_WIDTH
                    val start = column
                    out.append("|-->")
                    tabs += start until (start + spaces)
                    column += spaces
                } else if (ch.isISOControl()) {
                    val escaped = if (ch.code <= 0xFF) {
                        "\\x%02X".format(ch.code)
                    } else {
                        "\\x%04X".format(ch.code)
                    }
                    out.append(escaped)
                    column += escaped.length
                } else {
                    out.append(ch)
                    column++
                }
            }
            rawToVisual[rawText.length] = column
            visualText = out.toString()
            tabRanges = tabs
        }

        fun visualColumn(rawColumn: Int): Int = rawToVisual[rawColumn.coerceIn(0, rawText.length)]

        fun rawColumn(visualColumn: Int): Int {
            val target = visualColumn.coerceIn(0, visualText.length)
            var low = 0
            var high = rawText.length
            while (low < high) {
                val mid = (low + high) ushr 1
                if (visualColumn(mid) < target) low = mid + 1 else high = mid
            }
            return low.coerceIn(0, rawText.length)
        }
    }

    private data class WrappedLine(val lineIndex: Int, val startColumn: Int, val endColumn: Int)
    private data class VisualLayout(
        val rawLines: List<String>,
        val visualLines: List<VisualLine>,
        val wrapped: List<WrappedLine>,
        val lineOffsets: IntArray,
        val wrapCounts: IntArray,
        val contentWidth: Int
    )

    private data class UsageEntry(val file: String, val line: Int, val column: Int, val label: String)
    private data class UsagePopup(val anchor: Position, val entries: List<UsageEntry>)
    private data class RenderedPopup(val x: Int, val y: Int, val width: Int, val height: Int)
    private enum class DefinitionControl { NAVIGATION, LOCATION }
    private enum class Arrow { LEFT, RIGHT }
    private data class SuggestionEntry(val name: String, val detail: String?)
    private data class SuggestionPopup(val anchor: Position, val prefix: String, val entries: List<SuggestionEntry>)
    private data class HoverInfoCandidate(val key: Triple<Int, Int, Int>, val name: String, val startedAt: Long, val anchor: Position)
    private data class DefinitionPreview(
        val filePath: String,
        val language: String?,
        val version: Long,
        val startLine: Int,
        val lines: List<String>
    )
    private data class DefinitionTarget(val target: NavigationTarget, val preview: DefinitionPreview?)
    private data class InfoPopup(
        val anchor: Position,
        val targets: List<DefinitionTarget>,
        var selectedIndex: Int = 0
    )
}
