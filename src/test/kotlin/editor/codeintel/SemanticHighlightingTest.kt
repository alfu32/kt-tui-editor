package editor.codeintel

import editor.codeintel.frontend.KotlinSemanticAdapter
import editor.codeintel.frontend.SourceFile
import editor.codeintel.index.SemanticIndex
import editor.codeintel.semantic.SemanticTokenKind
import editor.codeintel.semantic.SemanticTokenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticHighlightingTest {
    @Test
    fun refreshesCachedTokensWhenOnlyAReferencedFileChanges() {
        val service = CodeIntelService(debounceMs = 0L)
        try {
            val consumer = "fun test(customer: Customer) { customer.value }"
            service.indexDocumentNow("Model.kt", "kotlin", "class Customer { val value: Long = 0 }", 1L)
            service.indexDocumentNow("Use.kt", "kotlin", consumer, 1L)
            val request = TokensRequest("Use.kt", "kotlin", 0, consumer.lines(), 1L)
            assertTrue(service.tokens(request).any { it.text == "value" && "semantic.property" in it.scopes })
            val revision = service.semanticRevision("Use.kt")

            service.indexDocumentNow("Model.kt", "kotlin", "class Customer { fun value(): Long = 0 }", 2L)

            assertTrue(service.semanticRevision("Use.kt") != revision)
            assertEquals(1L, service.semanticVersion("Use.kt"))
            assertTrue(service.tokens(request).any { it.text == "value" && "semantic.method" in it.scopes })
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun combinesTreeSitterLexicalTokensWithResolvedSymbolKinds() {
        val text = """
            // customer model
            class Customer {
                val id: Long = 42
                val name: String = "Ada"
                val field = true
                val none = null
                fun save(input: Customer) {
                    missing(input)
                }
            }
        """.trimIndent()
        val source = SourceFile("Customer.kt", "kotlin", text, 1L)
        val index = SemanticIndex()
        val snapshot = index.apply(KotlinSemanticAdapter().extract(source))
        val tokens = SemanticTokenService().tokens(source.fileId(), snapshot).toList()

        fun kindsFor(value: String): Set<SemanticTokenKind> = tokens
            .filter { text.substring(it.range.startOffset, it.range.endOffset) == value }
            .mapTo(linkedSetOf()) { it.kind }

        assertTrue(SemanticTokenKind.COMMENT in kindsFor("// customer model"))
        assertTrue(SemanticTokenKind.KEYWORD in kindsFor("class"))
        assertTrue(SemanticTokenKind.NUMBER in kindsFor("42"))
        assertTrue(SemanticTokenKind.STRING in kindsFor("\"Ada\""))
        assertTrue(SemanticTokenKind.KEYWORD in kindsFor("true"))
        assertTrue(SemanticTokenKind.KEYWORD in kindsFor("null"))
        assertTrue(SemanticTokenKind.CLASS in kindsFor("Customer"))
        assertTrue(SemanticTokenKind.PROPERTY in kindsFor("id"))
        assertTrue(SemanticTokenKind.METHOD in kindsFor("save"))
        assertTrue(SemanticTokenKind.PARAMETER in kindsFor("input"))
        assertTrue(SemanticTokenKind.TYPE in kindsFor("Long"))
        assertEquals(setOf(SemanticTokenKind.UNKNOWN), kindsFor("missing"))
        assertEquals(setOf(SemanticTokenKind.PROPERTY), kindsFor("field"))
    }

    @Test
    fun compatibilityFacadeReturnsSemanticAndMultilineLexicalScopes() {
        val service = CodeIntelService(debounceMs = 0L)
        try {
            val text = """
                /* first
                   second */
                class Customer {
                    val id: Long = 42
                    fun save() = "saved"
                }
            """.trimIndent()
            service.indexDocumentNow("Customer.kt", "kotlin", text, version = 3L)

            val tokens = service.tokens(
                TokensRequest(
                    filePath = "Customer.kt",
                    language = "kotlin",
                    startLine = 0,
                    lines = text.lines(),
                    version = 3L
                )
            )

            assertTrue(tokens.any { it.line == 0 && "semantic.comment" in it.scopes })
            assertTrue(tokens.any { it.line == 1 && "semantic.comment" in it.scopes })
            assertTrue(tokens.any { it.text == "class" && "semantic.keyword" in it.scopes })
            assertTrue(tokens.any { it.text == "Customer" && "semantic.class" in it.scopes })
            assertTrue(tokens.any { it.text == "id" && "semantic.property" in it.scopes })
            assertTrue(tokens.any { it.text == "save" && "semantic.method" in it.scopes })
            assertTrue(tokens.any { it.text == "42" && "semantic.number" in it.scopes })
            assertTrue(tokens.any { it.text == "\"saved\"" && "semantic.string" in it.scopes })
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun normalizesTreeSitterUtf8RangesBeforeRenderingSemanticTokens() {
        val service = CodeIntelService(debounceMs = 0L)
        try {
            // Tree-sitter reports byte offsets. These Unicode characters must
            // not move semantic painting on the following source line.
            val text = "// naïve — 😀\nclass Customer { val name: String = \"Ada\" }"
            service.indexDocumentNow("Unicode.kt", "kotlin", text, version = 1L)
            val lines = text.lines()
            val tokens = service.tokens(TokensRequest("Unicode.kt", "kotlin", 0, lines, 1L))

            tokens.forEach { token ->
                assertEquals(token.text, lines[token.line].substring(token.start, token.end))
            }
            assertTrue(tokens.any { it.text == "class" && "semantic.keyword" in it.scopes })
            assertTrue(tokens.any { it.text == "Customer" && "semantic.class" in it.scopes })
            assertTrue(tokens.any { it.text == "name" && "semantic.property" in it.scopes })
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun reusesKotlinParserStateAcrossEditedDocumentVersions() {
        val adapter = KotlinSemanticAdapter()
        val first = adapter.extract(
            SourceFile(
                "Incremental.kt",
                "kotlin",
                "class Customer { val id: Long = 1 }",
                1L
            )
        )
        val secondText = "class Customer { val id: Long = 42 }"
        val second = adapter.extract(
            SourceFile(
                "Incremental.kt",
                "kotlin",
                secondText,
                2L
            )
        )

        assertTrue(first.symbols.any { it.name == "Customer" })
        assertTrue(second.symbols.any { it.name == "Customer" })
        assertTrue(second.lexicalTokens.any { secondText.substring(it.range.startOffset, it.range.endOffset) == "42" })
    }

    private fun SourceFile.fileId() = editor.codeintel.model.SemanticIds.file(path)
}
