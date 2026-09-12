package editor.ui

import editor.lib.Position
import editor.lib.TextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import react.StyleSheet
import react.UIEvent
import editor.grammars.Token
import react.StyleSet
import react.renderer.StringSnapshotRenderer
import editor.grammars.KeywordSyntaxProvider
import editor.codeintel.EditorIntelligenceService
import editor.codeintel.CompletionItem
import editor.codeintel.DefinitionRequest
import editor.codeintel.NavigationTarget
import editor.codeintel.ReferenceRequest
import editor.codeintel.TextPosition
import editor.codeintel.TokensRequest
import editor.codeintel.Diagnostic
import editor.codeintel.Symbol
import java.nio.file.Files

class CodeEditorViewTest {
    @Test
    fun pageKeysTraverseWrappedRowsWithinOneLogicalLine() {
        val buffer = TextBuffer().apply { loadText("\t".repeat(100)) }
        val view = CodeEditorView(StyleSheet(), buffer)
        val renderer = StringSnapshotRenderer(cols = 23, rows = 4)
        view.render(renderer)
        view.dispatch(UIEvent(kind = "key_down", key = "PageDown", shift = true))
        // Twenty content cells per row fit five tabs; three rows make one page.
        assertEquals(Position(0, 15), buffer.cursorPosition())
        assertEquals("\t".repeat(15), buffer.selectionText())
        view.dispatch(UIEvent(kind = "key_down", key = "PageUp"))
        assertEquals(Position(0, 0), buffer.cursorPosition())
    }


    @Test
    fun pageKeysMoveCursorAndViewportByVisibleRows() {
        val buffer = TextBuffer().apply { loadText((0..99).joinToString("\n") { "line $it" }) }
        val view = CodeEditorView(StyleSheet(), buffer)
        val renderer = StringSnapshotRenderer(cols = 35, rows = 11)
        view.render(renderer)
        assertTrue(view.dispatch(UIEvent(kind = "key_down", key = "PageDown")))
        assertEquals(Position(10, 0), buffer.cursorPosition())
        view.render(renderer)
        assertTrue(renderer.snapshot().lines()[1].contains("line 10"))
        view.dispatch(UIEvent(kind = "key_down", key = "PageUp"))
        assertEquals(Position(0, 0), buffer.cursorPosition())
        view.render(renderer)
        assertTrue(renderer.snapshot().lines()[1].contains("line 0"))
    }

    @Test
    fun wheelMovesThreeRowsAndAltWheelMovesNine() {
        val buffer = TextBuffer().apply { loadText((0..99).joinToString("\n") { "line $it" }) }
        val view = CodeEditorView(StyleSheet(), buffer)
        val renderer = StringSnapshotRenderer(cols = 35, rows = 11)
        view.render(renderer)
        view.dispatch(UIEvent(kind = "mouse_scroll", scrollDelta = -1))
        view.render(renderer)
        assertTrue(renderer.snapshot().lines()[1].contains("line 3"))
        view.dispatch(UIEvent(kind = "mouse_scroll", scrollDelta = -1, alt = true))
        view.render(renderer)
        assertTrue(renderer.snapshot().lines()[1].contains("line 12"))
        assertEquals(Position(0, 0), buffer.cursorPosition())
    }

    @Test
    fun ctrlFPrefillsSearchWithEscapedSelection() {
        val buffer = TextBuffer()
        buffer.loadText("A.*B line")
        buffer.startSelection(Position(0, 0))
        buffer.selectTo(Position(0, 4))
        val view = CodeEditorView(StyleSheet(), buffer)

        val handled = view.dispatch(UIEvent(kind = "key_down", key = "F", ctrl = true))

        assertTrue(handled)
        assertEquals(Regex.escape("A.*B"), buffer.searchState().query)
    }

    @Test
    fun completionDoesNotDeleteReceiverDotWhenPrefixIsEmpty() {
        val buffer = TextBuffer()
        buffer.loadText("customer.")
        buffer.moveCursorTo(Position(0, buffer.text().length), expand = false)
        val view = CodeEditorView(StyleSheet(), buffer)

        applySuggestion(view, "name", "")

        assertEquals("customer.name", buffer.text())
    }

    @Test
    fun completionReplacesOnlyTheTypedIdentifierPrefix() {
        val buffer = TextBuffer()
        buffer.loadText("customer.na")
        buffer.moveCursorTo(Position(0, buffer.text().length), expand = false)
        val view = CodeEditorView(StyleSheet(), buffer)

        applySuggestion(view, "name", "na")

        assertEquals("customer.name", buffer.text())
    }

    @Test
    fun overlappingSyntaxTokensNeverCreateOverlappingDrawSegments() {
        val view = CodeEditorView(StyleSheet())
        val method = CodeEditorView::class.java.getDeclaredMethod(
            "buildSegments",
            String::class.java,
            List::class.java,
            StyleSet::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val segments = method.invoke(
            view,
            "abcdef",
            listOf(
                Token(1, 4, listOf("keyword"), 0),
                Token(3, 6, listOf("type"), 0)
            ),
            StyleSet()
        ) as List<Any>
        val ranges = segments.map { segment ->
            val type = segment.javaClass
            type.getDeclaredField("start").apply { isAccessible = true }.getInt(segment) to
                type.getDeclaredField("end").apply { isAccessible = true }.getInt(segment)
        }
        assertTrue(ranges.zipWithNext().all { (left, right) -> left.second <= right.first })
        assertEquals(6, ranges.sumOf { it.second - it.first })
    }

    @Test
    fun cKeywordHighlightingPreservesPunctuationAndSourceText() {
        val source = listOf(
            "for (i = 0; i < len; i++) {",
            "    printf(\"%d\\n\", v[i]);",
            "}"
        ).joinToString("\n")
        val view = CodeEditorView(StyleSheet(), syntaxProvider = KeywordSyntaxProvider)
        view.loadVirtualContent("test.c", source, "c")
        val renderer = StringSnapshotRenderer(cols = 100, rows = 8)

        view.render(renderer)

        val rendered = renderer.snapshot().lines().drop(1).take(3).map { it.drop(3) }
        source.split("\n").forEachIndexed { index, line ->
            assertTrue(rendered[index].startsWith(line))
        }
    }

    @Test
    fun tabsRenderAtFourColumnStopsWithoutShiftingTokens() {
        val source = "\tfor (i = 0; i < len; i++) {\n\t\treturn i;"
        val view = CodeEditorView(StyleSheet(), syntaxProvider = KeywordSyntaxProvider)
        view.loadVirtualContent("test.c", source, "c")
        val renderer = StringSnapshotRenderer(cols = 100, rows = 8)

        view.render(renderer)

        val rendered = renderer.snapshot().lines().drop(1).take(2).map { it.drop(3) }
        assertTrue(rendered[0].startsWith("|-->for (i = 0; i < len; i++) {"))
        assertTrue(rendered[1].startsWith("|-->|-->return i;"))
    }

    @Test
    fun controlCharactersUseSingleCursorStepEscapes() {
        val ctor = Class.forName("editor.ui.CodeEditorView\$VisualLine").getDeclaredConstructor(String::class.java)
        ctor.isAccessible = true
        val line = ctor.newInstance("a\u0001b")
        val visualText = line.javaClass.getDeclaredField("visualText").apply { isAccessible = true }.get(line) as String
        assertEquals("a\\x01b", visualText)
        val visualColumn = line.javaClass.getDeclaredMethod("visualColumn", Int::class.javaPrimitiveType!!)
        visualColumn.isAccessible = true
        assertEquals(1, visualColumn.invoke(line, 1))
        assertEquals(5, visualColumn.invoke(line, 2))
    }

    @Test
    fun logicalCursorStepsTreatTabAndControlAsOneCharacterEach() {
        val buffer = TextBuffer()
        buffer.loadText("\t\u0001x")

        buffer.moveRight()
        assertEquals(Position(0, 1), buffer.cursorPosition())
        buffer.moveRight()
        assertEquals(Position(0, 2), buffer.cursorPosition())
        buffer.moveLeft()
        assertEquals(Position(0, 1), buffer.cursorPosition())
    }

    @Test
    fun cSemanticTokensStayWithinIdentifierSpans() {
        val source = "for (i = 0; i < len; i++) {\n    printf(\"%d\\n\", v[i]);\n}"
        val service = editor.codeintel.CodeIntelService(debounceMs = 0L)
        try {
            service.indexDocumentNow("test.c", "c", source, 1L)
            val tokens = service.tokens(editor.codeintel.TokensRequest("test.c", "c", 0, source.split("\n"), 1L))
            tokens.forEach { token ->
                assertEquals(token.text, source.lines()[token.line].substring(token.start, token.end))
                if (token.scopes.any { it == "codeintel.declaration" || it == "codeintel.usage" }) {
                    assertTrue(token.text.all { it.isLetterOrDigit() || it == '_' })
                }
            }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun definitionPopupPagesTargetsAndNavigatesSelectedProjectRelativeFile() {
        val root = Files.createTempDirectory("kode-definition-popup")
        val first = root.resolve("first.c").also { Files.writeString(it, "int foo(void) { return 1; }\n") }
        val second = root.resolve("second.c").also { Files.writeString(it, "int foo(void) { return 2; }\n") }
        val opened = mutableListOf<String>()
        val service = PopupIntelligence(
            definitions = listOf(
                NavigationTarget(first.toString(), range(0)),
                NavigationTarget(second.toString(), range(0))
            )
        )
        val view = CodeEditorView(
            StyleSheet(),
            syntaxProvider = KeywordSyntaxProvider,
            codeIntel = service,
            navigationHandler = { path, _ -> opened += path },
            projectRootProvider = { root }
        )
        view.loadVirtualContent(root.resolve("current.c").toString(), "foo\n", "c")
        val renderer = StringSnapshotRenderer(cols = 80, rows = 20)
        view.render(renderer)
        view.dispatch(UIEvent(kind = "mouse_move", x = 3, y = 1, timeMs = 0, cols = 80, rows = 20))
        view.dispatch(UIEvent(kind = "mouse_move", x = 3, y = 1, timeMs = 600, cols = 80, rows = 20))
        view.render(renderer)
        assertTrue(renderer.snapshot().lines().any { it.contains("Definition") })
        assertTrue(renderer.snapshot().lines().any { it.contains("first.c:1") })
        assertTrue(renderer.snapshot().lines().any { it.contains("<< 1/2 >>") })

        val info = privateField(view, "renderedInfo")
        val rightArrowX = privateInt(info, "x") + 1 + "<< 1/2 >>".length - 2
        val navY = privateInt(info, "y") + 1
        view.dispatch(UIEvent(kind = "mouse_down", x = rightArrowX, y = navY, cols = 80, rows = 20))
        view.render(renderer)
        assertTrue(renderer.snapshot().lines().any { it.contains("<< 2/2 >>") })
        val selectedInfo = privateField(view, "renderedInfo")
        view.dispatch(UIEvent(
            kind = "mouse_down",
            x = privateInt(selectedInfo, "x") + 2,
            y = privateInt(selectedInfo, "y") + 2,
            cols = 80,
            rows = 20
        ))
        assertEquals(listOf(second.toString()), opened)
    }

    @Test
    fun usagePopupCapturesWheelAndKeyboardAndShowsAllReferencesThroughOffset() {
        val root = Files.createTempDirectory("kode-usage-popup")
        val refs = (0 until 12).map { index ->
            NavigationTarget(root.resolve("use$index.c").toString(), range(index), name = "foo")
        }
        val service = PopupIntelligence(
            tokens = listOf(Token(0, 3, listOf("codeintel.declaration"), 0, text = "foo")),
            references = refs
        )
        val buffer = TextBuffer()
        val opened = mutableListOf<String>()
        val view = CodeEditorView(StyleSheet(), buffer, codeIntel = service,
            navigationHandler = { path, _ -> opened += path })
        view.loadVirtualContent("current.c", "foo\n" + (0 until 30).joinToString("\n") { "line $it" }, "c")
        val renderer = StringSnapshotRenderer(cols = 80, rows = 12)
        view.render(renderer)
        val cursorBefore = buffer.cursorPosition()
        view.dispatch(UIEvent(kind = "mouse_down", x = 3, y = 1, ctrl = true, cols = 80, rows = 12))
        view.render(renderer)
        val before = renderer.snapshot().toString()
        assertTrue(before.contains("usage sites"))
        assertTrue(before.contains("│") || before.contains("█"))
        val usageBox = privateField(view, "renderedPopup")
        view.dispatch(UIEvent(
            kind = "mouse_move",
            x = privateInt(usageBox, "x"),
            y = privateInt(usageBox, "y") + 1,
            cols = 80,
            rows = 12
        ))
        assertEquals(-1, privateIntValue(view, "hoveredUsageIndex"), "row padding is not a link")
        view.dispatch(UIEvent(kind = "mouse_move", x = 0, y = 0, cols = 80, rows = 12))
        assertEquals(-1, privateIntValue(view, "hoveredUsageIndex"), "leaving the popup clears the link hover")
        assertEquals(0, privateIntValue(view, "selectedUsageIndex"), "hovering does not change keyboard selection")
        val tinyRenderer = StringSnapshotRenderer(cols = 10, rows = 4)
        view.render(tinyRenderer)
        assertTrue(tinyRenderer.snapshot().lines().all { it.length == 10 })
        view.render(renderer)

        assertTrue(view.dispatch(UIEvent(kind = "mouse_scroll", scrollDelta = -1)))
        assertEquals(cursorBefore, buffer.cursorPosition())
        assertEquals(1, privateIntValue(view, "usageScrollOffset"))
        view.dispatch(UIEvent(kind = "key_down", key = "PageDown"))
        view.dispatch(UIEvent(kind = "key_down", key = "End"))
        assertEquals(cursorBefore, buffer.cursorPosition())
        assertEquals(refs.lastIndex, privateIntValue(view, "selectedUsageIndex"))
        view.render(renderer)
        assertTrue(renderer.snapshot().lines().any { it.contains("use11.c:12") })
        view.dispatch(UIEvent(kind = "key_down", key = "Enter"))
        assertEquals(listOf(refs.last().filePath), opened)
    }

    @Test
    fun outsideClickDismissesDefinitionBeforeNextAnimationFrame() {
        val root = Files.createTempDirectory("kode-definition-dismiss")
        val target = root.resolve("target.c").also { Files.writeString(it, "int foo(void) { return 1; }\n") }
        val service = PopupIntelligence(definitions = listOf(NavigationTarget(target.toString(), range(0))))
        val view = CodeEditorView(StyleSheet(), syntaxProvider = KeywordSyntaxProvider, codeIntel = service)
        view.loadVirtualContent(root.resolve("current.c").toString(), "foo\n", "c")
        val renderer = StringSnapshotRenderer(cols = 60, rows = 12)
        view.render(renderer)
        view.dispatch(UIEvent(kind = "mouse_move", x = 3, y = 1, timeMs = 0, cols = 60, rows = 12))
        view.dispatch(UIEvent(kind = "mouse_move", x = 3, y = 1, timeMs = 600, cols = 60, rows = 12))
        view.render(renderer)
        assertTrue(renderer.snapshot().lines().any { it.contains("Definition") })
        view.dispatch(UIEvent(kind = "mouse_down", x = 0, y = 1, cols = 60, rows = 12))
        view.dispatch(UIEvent(kind = "animation_frame", timeMs = 2000, cols = 60, rows = 12))
        view.render(renderer)
        assertTrue(renderer.snapshot().lines().none { it.contains("Definition") })
    }

    @Test
    fun usagePathsCollapseDirectoriesAndPreserveFilename() {
        val view = CodeEditorView(StyleSheet())
        val method = CodeEditorView::class.java.getDeclaredMethod(
            "compactPath",
            String::class.java,
            Int::class.javaPrimitiveType!!
        )
        method.isAccessible = true

        val compact = method.invoke(
            view,
            "src/test/kotlin/editor/grammars/RegexSyntaxProviderTest.kt",
            48
        ) as String

        assertTrue(compact.length <= 48)
        assertTrue(compact != "src/test/kotlin/editor/grammars/RegexSyntaxProviderTest.kt")
        assertTrue(compact.endsWith("RegexSyntaxProviderTest.kt"))
    }

    private fun applySuggestion(view: CodeEditorView, suggestion: String, prefix: String) {
        val method = CodeEditorView::class.java.getDeclaredMethod(
            "applySuggestion",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(view, suggestion, prefix)
    }

    private fun range(line: Int): editor.codeintel.TextRange =
        editor.codeintel.TextRange(TextPosition(line, 0), TextPosition(line, 3))

    private fun privateField(view: CodeEditorView, name: String): Any {
        return CodeEditorView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)!!
    }

    private fun privateInt(value: Any, name: String): Int =
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(value)

    private fun privateIntValue(view: CodeEditorView, name: String): Int =
        CodeEditorView::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(view)

    private class PopupIntelligence(
        private val tokens: List<Token> = listOf(Token(0, 3, listOf("codeintel.usage"), 0, text = "foo")),
        private val definitions: List<NavigationTarget> = emptyList(),
        private val references: List<NavigationTarget> = emptyList()
    ) : EditorIntelligenceService {
        override fun tokens(request: TokensRequest): List<Token> = tokens
        override fun definitions(request: DefinitionRequest): List<NavigationTarget> = definitions
        override fun references(request: ReferenceRequest): List<NavigationTarget> = references
        override fun completions(request: editor.codeintel.CompletionRequest): List<CompletionItem> = emptyList()
        override fun diagnostics(path: String): List<Diagnostic> = emptyList()
        override fun documentSymbols(path: String): List<Symbol> = emptyList()
    }
}
