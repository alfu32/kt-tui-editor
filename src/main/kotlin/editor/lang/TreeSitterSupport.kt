package editor.lang

import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSPoint
import org.treesitter.TSInputEdit
import org.treesitter.TSTree
import java.nio.charset.StandardCharsets

internal class TreeSitterNode(
    private val node: TSNode,
    private val source: String,
    private val offsets: Utf8SourceOffsets,
    private val parentNode: TreeSitterNode? = null,
    private val field: String? = null
) : TsNode {
    override val type: String
        get() = node.type

    override val text: String
        get() = safeSubstring(startByte, endByte)

    /**
     * Tree-sitter itself uses UTF-8 byte positions. The editor and semantic
     * model use Kotlin String (UTF-16) offsets, so expose normalized offsets
     * through the language-neutral node contract.
     */
    override val startByte: Int
        get() = offsets.characterOffset(node.startByte)

    override val endByte: Int
        get() = offsets.characterOffset(node.endByte)

    override val startPoint: Point
        get() = pointAt(startByte, node.startPoint.row)

    override val endPoint: Point
        get() = pointAt(endByte, node.endPoint.row)

    override fun parent(): TsNode? = parentNode

    override fun children(): List<TsNode> {
        val count = node.namedChildCount
        if (count <= 0) return emptyList()
        val list = ArrayList<TsNode>(count)
        for (i in 0 until count) {
            val child = node.getNamedChild(i)
            if (child == null || child.isNull) continue
            val fieldName = node.getFieldNameForNamedChild(i)
            list.add(TreeSitterNode(child, source, offsets, this, fieldName))
        }
        return list
    }

    override fun allChildren(): List<TsNode> {
        val count = node.childCount
        if (count <= 0) return emptyList()
        val list = ArrayList<TsNode>(count)
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child == null || child.isNull) continue
            val fieldName = node.getFieldNameForChild(i)
            list.add(TreeSitterNode(child, source, offsets, this, fieldName))
        }
        return list
    }

    override fun fieldName(): String? = field

    private fun safeSubstring(start: Int, end: Int): String {
        if (start < 0 || end <= start || end > source.length) return ""
        return source.substring(start, end)
    }

    private fun pointAt(offset: Int, row: Int): Point {
        val lineStart = source.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        return Point(row, offset - lineStart)
    }
}

class TreeSitterParser(private val language: TSLanguage) {
    fun parse(text: String): TsNode? {
        return parseTree(text)?.rootNode()
    }

    fun parseTree(
        text: String,
        previous: TreeSitterParsedTree? = null,
        edit: TSInputEdit? = null
    ): TreeSitterParsedTree? {
        val parser = TSParser()
        val setOk = runCatching { parser.setLanguage(language) }.getOrDefault(false)
        if (!setOk) return null
        val oldTree = previous?.tree?.copy()
        if (oldTree != null && edit != null) oldTree.edit(edit)
        val tree = runCatching { parser.parseString(oldTree, text) }.getOrNull() ?: return null
        val root = tree.rootNode
        if (root.isNull) return null
        return TreeSitterParsedTree(tree, text)
    }
}

class TreeSitterParsedTree internal constructor(
    internal val tree: TSTree,
    private val source: String
) {
    private val offsets = Utf8SourceOffsets(source)

    internal fun rootNode(): TsNode = TreeSitterNode(tree.rootNode, source, offsets)
}

/** Maps Tree-sitter UTF-8 byte boundaries to Kotlin String offsets. */
internal class Utf8SourceOffsets(source: String) {
    private val byteToCharacter = IntArray(source.toByteArray(StandardCharsets.UTF_8).size + 1)

    init {
        var character = 0
        var byte = 0
        byteToCharacter[0] = 0
        while (character < source.length) {
            val codePoint = source.codePointAt(character)
            val characterCount = Character.charCount(codePoint)
            val byteCount = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
            repeat(byteCount) { index -> byteToCharacter[byte + index] = character }
            byte += byteCount
            character += characterCount
            byteToCharacter[byte] = character
        }
    }

    fun characterOffset(byteOffset: Int): Int =
        byteToCharacter[byteOffset.coerceIn(0, byteToCharacter.lastIndex)]
}

object TreeSitterLinearizer {
    fun linearize(root: TsNode): List<LinearNode> {
        val result = mutableListOf<LinearNode>()
        fun walk(node: TsNode, path: List<String>, depth: Int) {
            result.add(LinearNode(node, path, depth))
            node.children().forEach { child ->
                walk(child, path + child.type, depth + 1)
            }
        }
        walk(root, listOf(root.type), 0)
        return result
    }
}

class TreeSitterIdentifierPipeline(
    private val parser: TreeSitterParser,
    private val adapter: LanguageAdapter
) {
    fun extract(text: String, fileName: String, language: String?): List<IdentifierOccurrence> {
        val root = parser.parse(text) ?: return emptyList()
        val linear = TreeSitterLinearizer.linearize(root)
        return IdentifierCollector().collect(linear, adapter, fileName, language)
    }
}
