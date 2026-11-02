package org.razomy.rn.jetbrains.intellij.ide.plugin

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.lang.ASTNode
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import java.util.EnumSet
import javax.swing.Icon

class RnTokenType(debugName: String) : IElementType(debugName, RnLanguage)
class RnElementType(debugName: String) : IElementType(debugName, RnLanguage)

object RnTypes {
    @JvmField val COMMENT = RnTokenType("COMMENT")
    @JvmField val KEY = RnTokenType("KEY")
    @JvmField val SCHEMA_PORTAL = RnTokenType("SCHEMA_PORTAL")
    @JvmField val SCHEMA_TYPE = RnTokenType("SCHEMA_TYPE")
    @JvmField val SYMBOL = RnTokenType("SYMBOL")
    @JvmField val TYPED_LITERAL = RnTokenType("TYPED_LITERAL")
    @JvmField val STRING = RnTokenType("STRING")
    @JvmField val COL0_RAW = RnTokenType("COL0_RAW")
    @JvmField val NUMBER = RnTokenType("NUMBER")
    @JvmField val KEYWORD = RnTokenType("KEYWORD")
    @JvmField val OPERATOR = RnTokenType("OPERATOR")
    @JvmField val BRACKET = RnTokenType("BRACKET")
    @JvmField val PUNCTUATION = RnTokenType("PUNCTUATION")
    @JvmField val TEXT = RnTokenType("TEXT")

    @JvmField val COL0_RAW_BLOCK = RnElementType("COL0_RAW_BLOCK")
    @JvmField val ROOT_ELEMENT = RnElementType("ROOT")
    @JvmField val FILE = IFileElementType(RnLanguage)
}

/**
 * Context-aware Lexer for Razomy Notation (.rn) v0.3.0:
 * - Symmetric Pentad (`[`, `{`, `(`, `"`, `#`)
 * - Inline (`# text`) and Block (`#\n ...`) Homoiconic Comments
 * - Space Law & Attached Type Constructors (`Sql(`, `Html[`, `Scss{`, `Date"..."`, `Ts"`)
 * - Column-0 Raw Strings (`(Type)?(N)?"` ... `"(N)?`)
 * - Symbols (`'pk`), Unified `None`, Algebraic Operators (`&`, `^`, `/`)
 */
class RnLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var pos: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var currentToken: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.pos = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = currentToken
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        if (pos >= endOffset) {
            currentToken = null
            return
        }

        tokenStart = pos
        val ch = buffer[pos]

        // Whitespace
        if (ch.isWhitespace()) {
            while (pos < endOffset && buffer[pos].isWhitespace()) {
                pos++
            }
            tokenEnd = pos
            currentToken = TokenType.WHITE_SPACE
            return
        }

        // Homoiconic Comment: `# ` (inline) or `#` at EOL (indented block comment, including attached `Type#`)
        if (ch == '#') {
            if (isEndOfLineHash(pos)) {
                consumeBlockComment(pos)
                return
            }
            if (isAtStartOfLineOrWhitespace(tokenStart)) {
                val nextCh = if (pos + 1 < endOffset) buffer[pos + 1] else '\n'
                if (nextCh == ' ' || nextCh == '\t') {
                    while (pos < endOffset && buffer[pos] != '\n') {
                        pos++
                    }
                    tokenEnd = pos
                    currentToken = RnTypes.COMMENT
                    return
                }
            }
        }

        // Symmetric Triad Brackets
        if (ch == '[' || ch == ']' || ch == '{' || ch == '}' || ch == '(' || ch == ')') {
            pos++
            tokenEnd = pos
            currentToken = RnTypes.BRACKET
            return
        }

        // Comma / Semicolon separators
        if (ch == ',' || ch == ';') {
            pos++
            tokenEnd = pos
            currentToken = RnTypes.PUNCTUATION
            return
        }

        // Algebraic Composition Operators (`&`, `^`, `/`) and Optional Schema Type Mark (`?`)
        if ((ch == '&' || ch == '^' || ch == '/') && isAlgebraOperatorContext(pos)) {
            pos++
            tokenEnd = pos
            currentToken = RnTypes.OPERATOR
            return
        }
        if (ch == '?' && isOptionalMarkAfterType(pos)) {
            pos++
            tokenEnd = pos
            currentToken = RnTypes.OPERATOR
            return
        }

        // Quoted String or Column-0 Raw String
        if (ch == '"') {
            if (isEndOfLineQuote(pos)) {
                consumeCol0RawString("")
                return
            }
            pos++
            while (pos < endOffset) {
                if (buffer[pos] == '\\' && pos + 1 < endOffset) {
                    pos += 2
                    continue
                }
                if (buffer[pos] == '"') {
                    pos++
                    break
                }
                if (buffer[pos] == '\n') break
                pos++
            }
            tokenEnd = pos
            currentToken = RnTypes.STRING
            return
        }

        // Backtick inline code `` `...` ``
        if (ch == '`') {
            pos++
            while (pos < endOffset && buffer[pos] != '`' && buffer[pos] != '\n') {
                pos++
            }
            if (pos < endOffset && buffer[pos] == '`') pos++
            tokenEnd = pos
            currentToken = RnTypes.STRING
            return
        }

        // Prefix-quoted Symbol `'pk`, `'not_null`, `'GET`
        if (ch == '\'' && pos + 1 < endOffset && !buffer[pos + 1].isWhitespace()) {
            pos++
            while (pos < endOffset && (buffer[pos].isLetterOrDigit() || buffer[pos] == '_' || buffer[pos] == '-' || buffer[pos] == '.')) {
                pos++
            }
            tokenEnd = pos
            currentToken = RnTypes.SYMBOL
            return
        }

        // Read word/token
        while (pos < endOffset) {
            val c = buffer[pos]
            if (c.isWhitespace() ||
                c == '[' || c == ']' || c == '{' || c == '}' || c == '(' || c == ')' ||
                c == ',' || c == ';'
            ) {
                break
            }
            if (c == '#' && isEndOfLineHash(pos)) {
                break
            }
            if ((c == '&' || c == '^' || c == '/') && isAlgebraOperatorContext(pos)) {
                break
            }
            if (c == '?' && isOptionalMarkAfterType(pos)) {
                break
            }
            if (c == '"') {
                // Check if this is a Column-0 raw string opener `(Type)?(N)?"` at end of line
                if (isEndOfLineQuote(pos)) {
                    val marker = buffer.subSequence(tokenStart, pos).toString()
                    val rankDigits = marker.takeLastWhile { it.isDigit() }
                    consumeCol0RawString(rankDigits)
                    return
                }
                // Or Typed Literal `Date"2026-03-30"`
                pos++
                while (pos < endOffset && buffer[pos] != '"' && buffer[pos] != '\n') {
                    if (buffer[pos] == '\\' && pos + 1 < endOffset) pos += 2 else pos++
                }
                if (pos < endOffset && buffer[pos] == '"') pos++
                tokenEnd = pos
                currentToken = RnTypes.TYPED_LITERAL
                return
            }
            pos++
        }

        // Safety guard: guarantee forward progress so the lexer never emits a zero-length token
        if (pos == tokenStart && pos < endOffset) {
            pos++
        }

        tokenEnd = pos
        val word = buffer.subSequence(tokenStart, tokenEnd).toString()

        currentToken = when {
            word == "true" || word == "false" || word == "None" ||
                    word in setOf("title", "subtitle", "part", "section", "topic", "quote") -> RnTypes.KEYWORD
            isNumberLiteral(word) -> RnTypes.NUMBER
            // v0.3.0 Type Constructor attached to Pentad (`Sql(`, `Html[`, `Scss{`, `Int(1, 10){label "x"}`)
            isPascalCase(word) && isDirectlyFollowedByPentad(tokenEnd) -> RnTypes.SCHEMA_PORTAL
            // v0.3.0 Schema Type Definition (`Uuid{'pk}`, `Str`, `Int(18, 120){default 18}`, `Uuid`)
            isPascalCase(word) -> RnTypes.SCHEMA_TYPE
            isKeyTokenPosition(tokenStart, tokenEnd) -> RnTypes.KEY
            else -> RnTypes.TEXT
        }
    }

    /**
     * Consumes a v0.3.0 Block Comment (`#` at end of line) until dedent back to `<= baseIndent`.
     */
    private fun consumeBlockComment(hashPos: Int) {
        val baseIndent = getLineIndentAt(hashPos)
        // Advance to end of the `#` header line
        while (pos < endOffset && buffer[pos] != '\n') {
            pos++
        }
        // Consume subsequent lines that are either blank or indented deeper than baseIndent
        while (pos < endOffset && buffer[pos] == '\n') {
            val nextLineStart = pos + 1
            if (nextLineStart >= endOffset) break
            var i = nextLineStart
            var indent = 0
            while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t')) {
                indent++
                i++
            }
            val isBlankLine = i >= endOffset || buffer[i] == '\n' || buffer[i] == '\r'
            if (isBlankLine || indent > baseIndent) {
                pos = i
                while (pos < endOffset && buffer[pos] != '\n') {
                    pos++
                }
            } else {
                break
            }
        }
        tokenEnd = pos
        currentToken = RnTypes.COMMENT
    }

    private fun consumeCol0RawString(rankDigits: String) {
        if (pos < endOffset && buffer[pos] == '"') pos++
        val closeMarker = "\"$rankDigits"
        while (pos < endOffset) {
            if (buffer[pos] == '\n') {
                val lineStart = pos + 1
                var lineEnd = lineStart
                while (lineEnd < endOffset && buffer[lineEnd] != '\n' && buffer[lineEnd] != '\r') {
                    lineEnd++
                }
                val lineStr = buffer.subSequence(lineStart, lineEnd).toString().trimEnd()
                if (lineStr == closeMarker) {
                    pos = lineEnd
                    break
                }
            }
            pos++
        }
        tokenEnd = pos
        currentToken = RnTypes.COL0_RAW
    }

    private fun getLineIndentAt(idx: Int): Int {
        var lineStart = idx
        while (lineStart > startOffset && buffer[lineStart - 1] != '\n') {
            lineStart--
        }
        var indent = 0
        var i = lineStart
        while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t')) {
            indent++
            i++
        }
        return indent
    }

    private fun isEndOfLineHash(hashPos: Int): Boolean {
        var i = hashPos + 1
        while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t' || buffer[i] == '\r')) {
            i++
        }
        return i >= endOffset || buffer[i] == '\n'
    }

    private fun isEndOfLineQuote(quotePos: Int): Boolean {
        var i = quotePos + 1
        while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t' || buffer[i] == '\r')) {
            i++
        }
        if (i < endOffset && buffer[i] == '#') {
            val nextCh = if (i + 1 < endOffset) buffer[i + 1] else '\n'
            if (nextCh == ' ' || nextCh == '\t' || nextCh == '\r' || nextCh == '\n') {
                return true
            }
        }
        return i >= endOffset || buffer[i] == '\n'
    }

    private fun isAtStartOfLineOrWhitespace(idx: Int): Boolean {
        return idx == 0 || buffer[idx - 1].isWhitespace()
    }

    private fun isAlgebraOperatorContext(idx: Int): Boolean {
        val prevSpace = idx > startOffset && (buffer[idx - 1] == ' ' || buffer[idx - 1] == '\t')
        val nextSpace = idx + 1 < endOffset && (buffer[idx + 1] == ' ' || buffer[idx + 1] == '\t')
        return prevSpace && nextSpace
    }

    private fun isOptionalMarkAfterType(idx: Int): Boolean {
        if (idx <= startOffset) return false
        var start = idx - 1
        while (start >= startOffset && (buffer[start].isLetterOrDigit() || buffer[start] == '_')) {
            start--
        }
        val wordStart = start + 1
        if (wordStart >= idx) return false
        val word = buffer.subSequence(wordStart, idx).toString()
        return isPascalCase(word)
    }

    /**
     * Matches `key_token` or `inline_tag_key` in the v0.3.0 grammar:
     * - First token on a line (`unified_node`)
     * - Field key inside `inline_form` / `attached_props` (`{key val, key2 val2}`)
     * - Token after algebra operator (`& Mixin`, `/ debug`)
     * - Inline stream child tag (`b[bold]`, `span{class x}[text]`)
     */
    private fun isKeyTokenPosition(startIdx: Int, endIdx: Int): Boolean {
        // Inline child tag or attached props/slots: `b[bold]`, `div{class card}(slot)`
        if (endIdx < endOffset && (buffer[endIdx] == '[' || buffer[endIdx] == '{' || buffer[endIdx] == '(')) {
            return true
        }
        var i = startIdx - 1
        while (i >= startOffset && (buffer[i] == ' ' || buffer[i] == '\t')) {
            i--
        }
        if (i < startOffset || buffer[i] == '\n' || buffer[i] == '{') return true
        if (buffer[i] == '&' || buffer[i] == '^' || buffer[i] == '/') return true
        return false
    }

    private fun isDirectlyFollowedByPentad(afterIdx: Int): Boolean {
        return afterIdx < endOffset && (
            buffer[afterIdx] == '{' ||
            buffer[afterIdx] == '(' ||
            buffer[afterIdx] == '[' ||
            buffer[afterIdx] == '#'
        )
    }

    private fun isPascalCase(s: String): Boolean {
        return s.isNotEmpty() && s[0] in 'A'..'Z' && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
    }

    private fun isNumberLiteral(s: String): Boolean {
        if (s.startsWith("0x") || s.startsWith("0X") || s.startsWith("-0x")) return true
        return s.toDoubleOrNull() != null && s.count { it == '.' } <= 1
    }
}

class RnFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, RnLanguage) {
    override fun getFileType() = RnFileType
    override fun toString(): String = "Razomy Notation File"
}

/**
 * PSI Host Element for Column-0 Multiline Raw Strings (`Json"`, `Ts"`, `Sql"`, `Html"`, `Rn2"`, etc.).
 */
class RnCol0RawBlockPsiElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiLanguageInjectionHost {
    override fun isValidHost(): Boolean = true

    override fun updateText(text: String): PsiLanguageInjectionHost {
        val firstChildNode = node.firstChildNode
        if (firstChildNode is LeafPsiElement) {
            firstChildNode.replaceWithText(text)
        }
        return this
    }

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> =
        LiteralTextEscaper.createSimple(this)

    fun getLanguageTag(): String {
        val raw = text
        val firstNewline = raw.indexOf('\n')
        val header = if (firstNewline >= 0) raw.substring(0, firstNewline).trim() else raw.trim()
        if (!header.endsWith('"')) return ""
        val withoutQuote = header.dropLast(1)
        return withoutQuote.dropLastWhile { it.isDigit() }.trim()
    }

    fun getContentRangeInHost(): TextRange? {
        val raw = text
        val firstNewline = raw.indexOf('\n')
        if (firstNewline < 0) return null
        val contentStart = firstNewline + 1

        val header = raw.substring(0, firstNewline).trim()
        val rankDigits = header.removeSuffix("\"").takeLastWhile { it.isDigit() }
        val closeMarker = "\"$rankDigits"

        val lastNewline = raw.lastIndexOf('\n')
        val contentEnd = if (lastNewline >= contentStart) {
            val lastLine = raw.substring(lastNewline + 1).trimEnd()
            if (lastLine == closeMarker) lastNewline else raw.length
        } else {
            raw.length
        }

        return if (contentEnd > contentStart) TextRange(contentStart, contentEnd) else null
    }
}

class RnLanguageInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(RnCol0RawBlockPsiElement::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? RnCol0RawBlockPsiElement ?: return
        val tag = host.getLanguageTag()
        if (tag.isEmpty()) return

        val contentRange = host.getContentRangeInHost() ?: return
        val guestLanguage = resolveGuestLanguage(tag) ?: return

        registrar
            .startInjecting(guestLanguage)
            .addPlace(null, null, host, contentRange)
            .doneInjecting()
    }

    private fun resolveGuestLanguage(rawTag: String): Language? {
        val tag = rawTag.lowercase()
        if (tag == "rn" || tag == "rl" || tag == "razomy") {
            return RnLanguage
        }

        val idCandidates = when (tag) {
            "json", "json5" -> listOf("JSON", "JSON5")
            "ts", "typescript" -> listOf("TypeScript", "JavaScript", "ECMAScript 6")
            "js", "javascript" -> listOf("JavaScript", "ECMAScript 6")
            "html", "htm" -> listOf("HTML", "XHTML")
            "xml", "svg" -> listOf("XML")
            "css" -> listOf("CSS")
            "scss", "sass" -> listOf("SCSS", "SASS", "CSS")
            "sql", "postgres", "postgresql", "mysql", "sqlite" -> listOf("SQL", "PostgreSQL", "MySQL", "SQLite")
            "md", "markdown" -> listOf("Markdown")
            "yaml", "yml" -> listOf("yaml", "YAML")
            "sh", "bash", "zsh", "shell" -> listOf("Shell Script", "Bash", "sh")
            "tf", "hcl", "terraform" -> listOf("HCL", "Terraform")
            "dockerfile", "docker" -> listOf("Dockerfile")
            "proto", "protobuf" -> listOf("protobuf", "Proto")
            "rs", "rust" -> listOf("Rust")
            "py", "python" -> listOf("Python")
            "kt", "kotlin" -> listOf("kotlin", "Kotlin")
            "java" -> listOf("JAVA", "Java")
            "go", "golang" -> listOf("go", "Go")
            "toml" -> listOf("TOML")
            "graphql", "gql" -> listOf("GraphQL")
            else -> listOf(rawTag, tag.uppercase())
        }

        val registered = Language.getRegisteredLanguages()
        for (candidate in idCandidates) {
            Language.findLanguageByID(candidate)?.let { return it }
            registered.firstOrNull { it.id.equals(candidate, ignoreCase = true) }?.let { return it }
        }

        val extCandidates = when (tag) {
            "typescript" -> listOf("ts", "js")
            "javascript" -> listOf("js")
            "markdown" -> listOf("md")
            "shell", "bash" -> listOf("sh")
            "terraform" -> listOf("tf")
            "python" -> listOf("py")
            "rust" -> listOf("rs")
            "kotlin" -> listOf("kt")
            else -> listOf(tag)
        }
        for (ext in extCandidates) {
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)
            if (fileType is LanguageFileType) {
                return fileType.language
            }
        }

        return null
    }
}

class RnParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = RnLexer()
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            if (builder.tokenType == RnTypes.COL0_RAW) {
                val rawBlockMarker = builder.mark()
                builder.advanceLexer()
                rawBlockMarker.done(RnTypes.COL0_RAW_BLOCK)
            } else {
                builder.advanceLexer()
            }
        }
        rootMarker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = RnTypes.FILE
    override fun getCommentTokens(): TokenSet = TokenSet.create(RnTypes.COMMENT)
    override fun getStringLiteralElements(): TokenSet =
        TokenSet.create(RnTypes.STRING, RnTypes.COL0_RAW, RnTypes.TYPED_LITERAL)
    override fun createElement(node: ASTNode): PsiElement =
        when (node.elementType) {
            RnTypes.COL0_RAW_BLOCK -> RnCol0RawBlockPsiElement(node)
            else -> ASTWrapperPsiElement(node)
        }
    override fun createFile(viewProvider: FileViewProvider): PsiFile = RnFile(viewProvider)
}

class RnSyntaxHighlighter : SyntaxHighlighterBase() {
    companion object {
        val COMMENT = createTextAttributesKey("RN_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val KEY = createTextAttributesKey("RN_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        val SCHEMA_PORTAL = createTextAttributesKey("RN_SCHEMA_PORTAL", DefaultLanguageHighlighterColors.CLASS_NAME)
        val SCHEMA_TYPE = createTextAttributesKey("RN_SCHEMA_TYPE", DefaultLanguageHighlighterColors.CLASS_REFERENCE)
        val SYMBOL = createTextAttributesKey("RN_SYMBOL", DefaultLanguageHighlighterColors.CONSTANT)
        val TYPED_LITERAL = createTextAttributesKey("RN_TYPED_LITERAL", DefaultLanguageHighlighterColors.METADATA)
        val STRING = createTextAttributesKey("RN_STRING", DefaultLanguageHighlighterColors.STRING)
        val COL0_RAW = createTextAttributesKey("RN_COL0_RAW", DefaultLanguageHighlighterColors.DOC_COMMENT)
        val NUMBER = createTextAttributesKey("RN_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val KEYWORD = createTextAttributesKey("RN_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val OPERATOR = createTextAttributesKey("RN_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val BRACKET = createTextAttributesKey("RN_BRACKET", DefaultLanguageHighlighterColors.BRACES)
        val PUNCTUATION = createTextAttributesKey("RN_PUNCTUATION", DefaultLanguageHighlighterColors.COMMA)
        val TEXT = createTextAttributesKey("RN_TEXT", HighlighterColors.TEXT)
    }

    override fun getHighlightingLexer() = RnLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        when (tokenType) {
            RnTypes.COMMENT -> pack(COMMENT)
            RnTypes.KEY -> pack(KEY)
            RnTypes.SCHEMA_PORTAL -> pack(SCHEMA_PORTAL)
            RnTypes.SCHEMA_TYPE -> pack(SCHEMA_TYPE)
            RnTypes.SYMBOL -> pack(SYMBOL)
            RnTypes.TYPED_LITERAL -> pack(TYPED_LITERAL)
            RnTypes.STRING -> pack(STRING)
            RnTypes.COL0_RAW -> pack(COL0_RAW)
            RnTypes.NUMBER -> pack(NUMBER)
            RnTypes.KEYWORD -> pack(KEYWORD)
            RnTypes.OPERATOR -> pack(OPERATOR)
            RnTypes.BRACKET -> pack(BRACKET)
            RnTypes.PUNCTUATION -> pack(PUNCTUATION)
            RnTypes.TEXT -> pack(TEXT)
            else -> TextAttributesKey.EMPTY_ARRAY
        }
}

class RnSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        RnSyntaxHighlighter()
}

class RnCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "# "
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/**
 * Topological Folding Builder for `.rn` v0.3.0 blocks (`{`, `(`, `[`, `#` at end of line).
 */
class RnFoldingBuilder : FoldingBuilderEx() {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        val lineCount = document.lineCount
        for (line in 0 until lineCount) {
            val startOffset = document.getLineStartOffset(line)
            val endOffset = document.getLineEndOffset(line)
            val lineText = document.getText(TextRange(startOffset, endOffset))
            val trimmed = lineText.trim()
            if (trimmed.isEmpty()) continue
            // Inline comment `# ...` is single-line, whereas bare `#` at EOL is a foldable block comment
            if (trimmed.startsWith("#") && trimmed != "#") continue

            val indent = lineText.takeWhile { it == ' ' || it == '\t' }.length
            if (trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[") || trimmed == "#") {
                var lastChildLine = line
                for (sub in (line + 1) until lineCount) {
                    val subStart = document.getLineStartOffset(sub)
                    val subEnd = document.getLineEndOffset(sub)
                    val subText = document.getText(TextRange(subStart, subEnd))
                    if (subText.isBlank()) continue
                    val subIndent = subText.takeWhile { it == ' ' || it == '\t' }.length
                    if (subIndent > indent) {
                        lastChildLine = sub
                    } else {
                        break
                    }
                }
                if (lastChildLine > line) {
                    val foldEnd = document.getLineEndOffset(lastChildLine)
                    if (foldEnd > endOffset) {
                        descriptors.add(FoldingDescriptor(root.node, TextRange(endOffset - 1, foldEnd)))
                    }
                }
            }
        }
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

/**
 * Visual Column Formatter for Razomy Notation (`.rn`) v0.3.0:
 * Aligns 2D Relational Tables (`(` blocks where the first child line has top-level commas)
 * into clean visual columns (just like Markdown table formatting in JetBrains IDEs),
 * while preserving Column-0 multiline raw strings untouched!
 */
object RnTableFormatter {

    fun formatDocument(text: String): String {
        val lines = text.split("\n")
        val result = lines.toMutableList()
        var i = 0

        while (i < result.size) {
            val line = result[i]
            val trimmedRight = line.trimEnd('\r', ' ', '\t')

            // 1. Skip Column-0 Raw Multiline Strings `(Type)?(N)?"` so their literal content is never touched
            val col0Rank = detectCol0RawOpenerRank(trimmedRight)
            if (col0Rank != null) {
                val closeMarker = "\"$col0Rank"
                i++
                while (i < result.size) {
                    if (result[i].trimEnd('\r', ' ', '\t') == closeMarker) {
                        break
                    }
                    i++
                }
                i++
                continue
            }

            // 2. Skip Block Comments (`#` at EOL)
            if (trimmedRight.trim() == "#") {
                val baseIndent = getIndentLength(line)
                i++
                while (i < result.size) {
                    val sub = result[i]
                    if (sub.isNotBlank() && getIndentLength(sub) <= baseIndent) break
                    i++
                }
                continue
            }

            // 3. Detect Tuple block opener `(` or `Type(` at EOL
            val codePart = stripInlineComment(trimmedRight).trimEnd()
            if (codePart.endsWith("(")) {
                val parentIndent = getIndentLength(line)
                val blockEnd = findBlockEnd(result, i, parentIndent)
                if (blockEnd > i) {
                    formatTupleIf2DTable(result, i + 1, blockEnd)
                }
            }

            i++
        }

        return result.joinToString("\n")
    }

    /**
     * Checks if the lines in `startLine..endLine` form a 2D Relational Table (Axiom III.3:
     * first indented non-blank line contains at least one top-level comma) and aligns all rows by column.
     */
    private fun formatTupleIf2DTable(lines: MutableList<String>, startLine: Int, endLine: Int) {
        // Find the first non-blank child line
        var firstChildIdx = -1
        for (idx in startLine..endLine) {
            if (lines[idx].isNotBlank()) {
                firstChildIdx = idx
                break
            }
        }
        if (firstChildIdx == -1) return

        val rowIndentLen = getIndentLength(lines[firstChildIdx])
        val firstRowCells = splitTopLevelRow(lines[firstChildIdx]) ?: return
        // A 2D table in .rn must have at least one top-level comma on the header line
        if (firstRowCells.cells.size < 2) return

        // Collect all direct child rows at `rowIndentLen` that contain top-level commas
        data class ParsedRow(
            val lineIndex: Int,
            val indentStr: String,
            val cells: List<String>,
            val trailingComment: String?
        )

        val parsedRows = mutableListOf<ParsedRow>()
        for (idx in firstChildIdx..endLine) {
            val rawLine = lines[idx]
            if (rawLine.isBlank()) continue
            val indent = getIndentLength(rawLine)
            // Only align rows at the table's row indentation level
            if (indent != rowIndentLen) continue
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#")) continue

            val split = splitTopLevelRow(rawLine) ?: continue
            if (split.cells.size < 2) continue

            parsedRows.add(
                ParsedRow(
                    lineIndex = idx,
                    indentStr = rawLine.substring(0, indent),
                    cells = split.cells,
                    trailingComment = split.trailingComment
                )
            )
        }

        if (parsedRows.size < 2) return

        // Compute maximum visual width for each column
        val maxCols = parsedRows.maxOf { it.cells.size }
        val colWidths = IntArray(maxCols)
        for (row in parsedRows) {
            for (c in row.cells.indices) {
                val cellLen = row.cells[c].length
                if (cellLen > colWidths[c]) {
                    colWidths[c] = cellLen
                }
            }
        }

        // Rebuild each row with aligned columns (just like Markdown tables, using `, `)
        for (row in parsedRows) {
            val sb = StringBuilder(row.indentStr)
            val lastColIdx = row.cells.lastIndex
            for (c in 0..lastColIdx) {
                val cellText = row.cells[c]
                sb.append(cellText)
                if (c < lastColIdx) {
                    sb.append(",")
                    val padCount = (colWidths[c] - cellText.length) + 1
                    sb.append(" ".repeat(padCount.coerceAtLeast(1)))
                }
            }
            if (!row.trailingComment.isNullOrEmpty()) {
                sb.append(" ").append(row.trailingComment)
            }
            lines[row.lineIndex] = sb.toString()
        }
    }

    private data class RowSplitResult(
        val cells: List<String>,
        val trailingComment: String?
    )

    /**
     * Splits a single table row by top-level `,` while respecting:
     * - Quoted strings `"Hello, world!"`
     * - Typed literals `Date"12,12"`
     * - Backtick spans `` `a, b` ``
     * - Nested brackets `(...)`, `{...}`, `[...]`
     * - Trailing inline comments `# comment`
     */
    private fun splitTopLevelRow(rawLine: String): RowSplitResult? {
        val cleanLine = rawLine.trimEnd('\r')
        val contentWithoutComment = stripInlineComment(cleanLine)
        val trailingComment = if (contentWithoutComment.length < cleanLine.length) {
            cleanLine.substring(contentWithoutComment.length).trim()
        } else {
            null
        }

        val trimmed = contentWithoutComment.trim()
        if (trimmed.isEmpty()) return null

        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var inBackticks = false
        var bracketDepth = 0
        var i = 0

        while (i < trimmed.length) {
            val ch = trimmed[i]

            if (inQuotes) {
                current.append(ch)
                if (ch == '\\' && i + 1 < trimmed.length) {
                    current.append(trimmed[i + 1])
                    i += 2
                    continue
                }
                if (ch == '"') {
                    inQuotes = false
                }
                i++
                continue
            }

            if (inBackticks) {
                current.append(ch)
                if (ch == '`') {
                    inBackticks = false
                }
                i++
                continue
            }

            when (ch) {
                '"' -> {
                    inQuotes = true
                    current.append(ch)
                }
                '`' -> {
                    inBackticks = true
                    current.append(ch)
                }
                '(', '{', '[' -> {
                    bracketDepth++
                    current.append(ch)
                }
                ')', '}', ']' -> {
                    if (bracketDepth > 0) bracketDepth--
                    current.append(ch)
                }
                ',' -> {
                    if (bracketDepth == 0) {
                        cells.add(current.toString().trim())
                        current.setLength(0)
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
            i++
        }

        cells.add(current.toString().trim())
        return RowSplitResult(cells, trailingComment)
    }

    /**
     * Strips inline `# comment` outside quotes/backticks.
     */
    private fun stripInlineComment(line: String): String {
        var inQuotes = false
        var inBackticks = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '\\' && i + 1 < line.length) {
                    i += 2
                    continue
                }
                if (ch == '"') inQuotes = false
                i++
                continue
            }
            if (inBackticks) {
                if (ch == '`') inBackticks = false
                i++
                continue
            }
            if (ch == '"') {
                inQuotes = true
            } else if (ch == '`') {
                inBackticks = true
            } else if (ch == '#' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t')) {
                val nextCh = if (i + 1 < line.length) line[i + 1] else '\n'
                if (nextCh == ' ' || nextCh == '\t' || nextCh == '\n') {
                    return line.substring(0, i).trimEnd()
                }
            }
            i++
        }
        return line
    }

    /**
     * Detects if a line ends with a Column-0 raw string opener (`"`, `1"`, `Ts"`, `Rn2"`, etc.)
     * rather than an inline closed string `"..."`. Returns the rank digits (`""`, `"1"`, `"2"`) or `null`.
     */
    private fun detectCol0RawOpenerRank(trimmedRight: String): String? {
        if (!trimmedRight.endsWith('"')) return null
        // Count unescaped quotes on the line: if odd, the final `"` opens a multiline raw string!
        var quoteCount = 0
        var i = 0
        while (i < trimmedRight.length) {
            if (trimmedRight[i] == '\\' && i + 1 < trimmedRight.length) {
                i += 2
                continue
            }
            if (trimmedRight[i] == '"') quoteCount++
            i++
        }
        if (quoteCount % 2 == 0) return null

        val beforeQuote = trimmedRight.dropLast(1)
        return beforeQuote.takeLastWhile { it.isDigit() }
    }

    private fun findBlockEnd(lines: List<String>, openLine: Int, parentIndent: Int): Int {
        var lastChild = openLine
        for (sub in (openLine + 1) until lines.size) {
            val subText = lines[sub]
            if (subText.isBlank()) continue
            val subIndent = getIndentLength(subText)
            if (subIndent > parentIndent) {
                lastChild = sub
            } else {
                break
            }
        }
        return lastChild
    }

    private fun getIndentLength(line: String): Int =
        line.takeWhile { it == ' ' || it == '\t' }.length
}

/**
 * Registers `Ctrl + Alt + L` (`Cmd + Option + L`) Reformat Code support for `.rn` files in IntelliJ IDEA.
 */
class RnFormattingService : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature> =
        EnumSet.noneOf(FormattingService.Feature::class.java)

    override fun canFormat(file: PsiFile): Boolean = file is RnFile

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val documentText = request.documentText
        return object : FormattingTask {
            override fun run() {
                val formatted = RnTableFormatter.formatDocument(documentText)
                request.onTextReady(formatted)
            }

            override fun cancel(): Boolean = true
            override fun isRunUnderProgress(): Boolean = false
        }
    }

    override fun getNotificationGroupId(): String = "Razomy Notation"
    override fun getName(): String = "Razomy Notation Table Formatter"
}

class RnColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        AttributesDescriptor("Comment (# )", RnSyntaxHighlighter.COMMENT),
        AttributesDescriptor("Key / Tag Name", RnSyntaxHighlighter.KEY),
        AttributesDescriptor("Attached Type Constructor (Sql(, Html[, Scss{)", RnSyntaxHighlighter.SCHEMA_PORTAL),
        AttributesDescriptor("Schema Type Definition (Uuid, Int, Str)", RnSyntaxHighlighter.SCHEMA_TYPE),
        AttributesDescriptor("Symbol ('pk, 'GET)", RnSyntaxHighlighter.SYMBOL),
        AttributesDescriptor("Typed Literal (Date\"...\")", RnSyntaxHighlighter.TYPED_LITERAL),
        AttributesDescriptor("String", RnSyntaxHighlighter.STRING),
        AttributesDescriptor("Column-0 Raw String", RnSyntaxHighlighter.COL0_RAW),
        AttributesDescriptor("Number", RnSyntaxHighlighter.NUMBER),
        AttributesDescriptor("Keyword / Boolean / None", RnSyntaxHighlighter.KEYWORD),
        AttributesDescriptor("Algebra & Optional Operator (&, ^, /, ?)", RnSyntaxHighlighter.OPERATOR),
        AttributesDescriptor("Symmetric Pentad Bracket ([], {}, ())", RnSyntaxHighlighter.BRACKET)
    )

    override fun getIcon(): Icon = RnIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = RnSyntaxHighlighter()
    override fun getDemoText(): String = """
        # Razomy Notation (.rn) v0.3.0 — Universal Node & Schema Sample
        #
         Multiline block comment (5th Pentad member)!
         Auto-closed on dedent with zero '#' on inner lines.
        User{table "users"}(1, 2) & Auditable / debug {
         id Uuid{'pk}
         age Int(18, 120){default 18}
         bio Str
         created_at Date"2026-03-30"
         fallback None
         welcome_msg Ts1"
        const greeting: string = "Column-0 Raw String!";
        "1
        
        users_schema (
         column, type, constraint, default
         id,     Uuid, 'pk,        gen_uuid
         org_id, Uuid, Orgs.id,    None
         role,   Text, 'not_null,  member
        
        UserCard & BaseCard {
         query Sql(
          select, from,  where
          "*",    users, "id = :id"
         view Html[
          div{class card} [
           p [ Hello b[bold] and `code`!
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "Razomy Notation (.rn)"
}

/**
 * Visual Auto-Numbering Inlay Hints for Razomy Notation (.rn) v0.3.0:
 * 1. Hierarchical Markdown Heading Numbering (`part` -> `1.`, `section` -> `1.1.`, `topic` -> `1.1.1.`)
 * 2. 2D Table Badge (`Table 1`) & Row Auto-Numbering (`#1`, `#2`, `#3` skipping the header row)
 * 3. Ordered List (`ol [`) hierarchical item numbering (`1.`, `2.`, `2.1.`)
 */
@Suppress("UnstableApiUsage")
class RnVisualNumberingHintsProvider : InlayHintsProvider<NoSettings> {
    override val key: SettingsKey<NoSettings> = SettingsKey("rn.visual.numbering.hints")
    override val name: String = "Razomy Visual Auto-Numbering (Md Headings & 2D Tables)"
    override val previewText: String = """
        part AXIOMATIC SPECIFICATION
        section AXIOM II: SPATIAL TOPOLOGY
        topic Inline Mode
        topic Block Mode
        
        users_schema (
         column, type, constraint, default
         id,     Uuid, 'pk,        gen_uuid
         org_id, Uuid, Orgs.id,    None
         role,   Text, 'not_null,  member
    """.trimIndent()

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener): JComponent = JPanel()
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector {
        return object : FactoryInlayHintsCollector(editor) {
            private var rendered = false

            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                // Run once at the root PsiFile level so we can compute top-to-bottom hierarchical counters
                if (rendered || element !is PsiFile) return true
                rendered = true

                val document = editor.document
                val text = document.charsSequence
                val lineCount = document.lineCount

                var partCounter = 0
                var sectionCounter = 0
                var topicCounter = 0
                var tableCounter = 0

                var line = 0
                while (line < lineCount) {
                    val lineStart = document.getLineStartOffset(line)
                    val lineEnd = document.getLineEndOffset(line)
                    val rawLine = text.subSequence(lineStart, lineEnd).toString()
                    val trimmedRight = rawLine.trimEnd('\r', ' ', '\t')
                    val trimmed = trimmedRight.trim()

                    // 1. Skip Column-0 Raw Multiline Strings `(Type)?(N)?"` so embedded code isn't numbered
                    val col0Rank = detectCol0Opener(trimmedRight)
                    if (col0Rank != null) {
                        val closeMarker = "\"$col0Rank"
                        line++
                        while (line < lineCount) {
                            val subStart = document.getLineStartOffset(line)
                            val subEnd = document.getLineEndOffset(line)
                            val subLine = text.subSequence(subStart, subEnd).toString().trimEnd('\r', ' ', '\t')
                            if (subLine == closeMarker) break
                            line++
                        }
                        line++
                        continue
                    }

                    // 2. Skip Block Comments (`#` at EOL)
                    if (trimmed == "#") {
                        val baseIndent = getIndent(rawLine)
                        line++
                        while (line < lineCount) {
                            val subStart = document.getLineStartOffset(line)
                            val subEnd = document.getLineEndOffset(line)
                            val subLine = text.subSequence(subStart, subEnd).toString()
                            if (subLine.isNotBlank() && getIndent(subLine) <= baseIndent) break
                            line++
                        }
                        continue
                    }

                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        line++
                        continue
                    }

                    val indent = getIndent(rawLine)
                    val firstWord = trimmed.takeWhile { !it.isWhitespace() && it != '[' && it != '{' && it != '(' }

                    // 3. Markdown Headings: `part`, `section`, `topic` -> `1.`, `1.1.`, `1.1.1.`
                    when (firstWord) {
                        "part" -> {
                            partCounter++
                            sectionCounter = 0
                            topicCounter = 0
                            val kwEndOffset = lineStart + indent + "part".length
                            val cleanAfter = stripManualLeadingNumber(trimmed.removePrefix("part").trimStart())
                            if (!cleanAfter.alreadyNumbered) {
                                addBadge(sink, factory, kwEndOffset, " $partCounter.")
                            }
                        }
                        "section" -> {
                            sectionCounter++
                            topicCounter = 0
                            val label = if (partCounter > 0) "$partCounter.$sectionCounter." else "$sectionCounter."
                            val kwEndOffset = lineStart + indent + "section".length
                            val cleanAfter = stripManualLeadingNumber(trimmed.removePrefix("section").trimStart())
                            if (!cleanAfter.alreadyNumbered) {
                                addBadge(sink, factory, kwEndOffset, " $label")
                            }
                        }
                        "topic" -> {
                            topicCounter++
                            val label = when {
                                partCounter > 0 && sectionCounter > 0 -> "$partCounter.$sectionCounter.$topicCounter."
                                sectionCounter > 0 -> "$sectionCounter.$topicCounter."
                                partCounter > 0 -> "$partCounter.$topicCounter."
                                else -> "$topicCounter."
                            }
                            val kwEndOffset = lineStart + indent + "topic".length
                            val cleanAfter = stripManualLeadingNumber(trimmed.removePrefix("topic").trimStart())
                            if (!cleanAfter.alreadyNumbered) {
                                addBadge(sink, factory, kwEndOffset, " $label")
                            }
                        }
                    }

                    // 4. Ordered Lists `ol [` -> `1.`, `2.`, `2.1.`
                    val codePart = trimmedRight.substringBefore(" #").trimEnd()
                    if (firstWord == "ol" && codePart.endsWith("[")) {
                        val blockEnd = findBlockEnd(document, line, indent)
                        if (blockEnd > line) {
                            numberOrderedListBlock(document, line + 1, blockEnd, indent, "", sink, factory)
                        }
                    }

                    // 5. 2D Relational Tables `(` -> `[Table N]` + Row numbers `#1`, `#2`, ...
                    if (codePart.endsWith("(")) {
                        val blockEnd = findBlockEnd(document, line, indent)
                        if (blockEnd > line && is2DTableBlock(document, line + 1, blockEnd)) {
                            tableCounter++
                            val tableBadge = if (partCounter > 0 && sectionCounter > 0) {
                                "Table $partCounter.$sectionCounter.$tableCounter"
                            } else {
                                "Table $tableCounter"
                            }
                            // Show `[Table N]` right after the opening `(`
                            addBadge(sink, factory, lineEnd, " $tableBadge")

                            // Number each data row `#1`, `#2`, `#3` (skipping the first header row)
                            number2DTableRows(document, line + 1, blockEnd, sink, factory)
                        }
                    }

                    line++
                }

                return false
            }
        }
    }

    private fun number2DTableRows(
        document: Document,
        startLine: Int,
        endLine: Int,
        sink: InlayHintsSink,
        factory: PresentationFactory
    ) {
        val text = document.charsSequence
        var headerLineIdx = -1
        var rowIndent = -1

        for (l in startLine..endLine) {
            val s = document.getLineStartOffset(l)
            val e = document.getLineEndOffset(l)
            val lineStr = text.subSequence(s, e).toString()
            if (lineStr.isNotBlank() && !lineStr.trim().startsWith("#")) {
                headerLineIdx = l
                rowIndent = getIndent(lineStr)
                break
            }
        }
        if (headerLineIdx == -1) return

        // Count actual data rows to compute exact digit padding width
        var totalDataRows = 0
        for (l in (headerLineIdx + 1)..endLine) {
            val s = document.getLineStartOffset(l)
            val e = document.getLineEndOffset(l)
            val lineStr = text.subSequence(s, e).toString()
            val trimmed = lineStr.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (getIndent(lineStr) == rowIndent && hasTopLevelComma(trimmed)) {
                totalDataRows++
            }
        }
        if (totalDataRows == 0) return

        val padWidth = totalDataRows.toString().length.coerceAtLeast(1)

        // Add matching-width `#` badge on the header row so header and data columns align vertically
        val headerStartOffset = document.getLineStartOffset(headerLineIdx)
        val headerLabel = "${"#".padStart(padWidth, ' ')}. "
        addBadge(sink, factory, headerStartOffset + rowIndent, headerLabel)

        var rowNumber = 0
        for (l in (headerLineIdx + 1)..endLine) {
            val s = document.getLineStartOffset(l)
            val e = document.getLineEndOffset(l)
            val lineStr = text.subSequence(s, e).toString()
            val trimmed = lineStr.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = getIndent(lineStr)
            if (indent != rowIndent) continue
            if (!hasTopLevelComma(trimmed)) continue

            rowNumber++
            val rowLabel = "${rowNumber.toString().padStart(padWidth, ' ')}. "
            addBadge(sink, factory, s + indent, rowLabel)
        }
    }

    private fun numberOrderedListBlock(
        document: Document,
        startLine: Int,
        endLine: Int,
        parentIndent: Int,
        prefix: String,
        sink: InlayHintsSink,
        factory: PresentationFactory
    ) {
        val text = document.charsSequence
        var itemIndent = -1
        for (l in startLine..endLine) {
            val lineStr = text.subSequence(document.getLineStartOffset(l), document.getLineEndOffset(l)).toString()
            if (lineStr.isNotBlank() && !lineStr.trim().startsWith("#")) {
                itemIndent = getIndent(lineStr)
                break
            }
        }
        if (itemIndent <= parentIndent) return

        var itemIdx = 0
        var l = startLine
        while (l <= endLine) {
            val s = document.getLineStartOffset(l)
            val e = document.getLineEndOffset(l)
            val lineStr = text.subSequence(s, e).toString()
            val trimmed = lineStr.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                l++
                continue
            }
            val indent = getIndent(lineStr)
            if (indent == itemIndent) {
                itemIdx++
                val currentNumber = if (prefix.isEmpty()) "$itemIdx" else "$prefix.$itemIdx"
                addBadge(sink, factory, s + indent, "$currentNumber. ")

                // Check if this item has nested outline children (`+Q` indent)
                val childEnd = findBlockEnd(document, l, itemIndent).coerceAtMost(endLine)
                if (childEnd > l) {
                    numberOrderedListBlock(document, l + 1, childEnd, itemIndent, currentNumber, sink, factory)
                    l = childEnd + 1
                    continue
                }
            }
            l++
        }
    }

    private fun addBadge(
        sink: InlayHintsSink,
        factory: PresentationFactory,
        offset: Int,
        label: String
    ) {
        val presentation: InlayPresentation = factory.roundWithBackground(factory.smallText(label))
        sink.addInlineElement(offset, relatesToPrecedingText = true, presentation = presentation, placeAtTheEndOfLine = false)
    }

    private data class ManualNumberCheck(val alreadyNumbered: Boolean)

    /**
     * Avoids duplicating numbers if the author already manually wrote `topic 1. Inline Mode`.
     */
    private fun stripManualLeadingNumber(restOfHeading: String): ManualNumberCheck {
        val firstToken = restOfHeading.takeWhile { !it.isWhitespace() }
        val isManualNum = firstToken.isNotEmpty() &&
                firstToken.endsWith(".") &&
                firstToken.dropLast(1).all { it.isDigit() || it == '.' }
        return ManualNumberCheck(alreadyNumbered = isManualNum)
    }

    private fun is2DTableBlock(document: Document, startLine: Int, endLine: Int): Boolean {
        val text = document.charsSequence
        for (l in startLine..endLine) {
            val s = document.getLineStartOffset(l)
            val e = document.getLineEndOffset(l)
            val lineStr = text.subSequence(s, e).toString().trim()
            if (lineStr.isEmpty() || lineStr.startsWith("#")) continue
            return hasTopLevelComma(lineStr)
        }
        return false
    }

    private fun hasTopLevelComma(line: String): Boolean {
        var inQuotes = false
        var inBackticks = false
        var depth = 0
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '\\' && i + 1 < line.length) {
                    i += 2
                    continue
                }
                if (ch == '"') inQuotes = false
                i++
                continue
            }
            if (inBackticks) {
                if (ch == '`') inBackticks = false
                i++
                continue
            }
            when (ch) {
                '"' -> inQuotes = true
                '`' -> inBackticks = true
                '(', '{', '[' -> depth++
                ')', '}', ']' -> if (depth > 0) depth--
                '#' -> if (i == 0 || line[i - 1].isWhitespace()) break
                ',' -> if (depth == 0) return true
            }
            i++
        }
        return false
    }

    private fun detectCol0Opener(trimmedRight: String): String? {
        if (!trimmedRight.endsWith('"')) return null
        var quotes = 0
        var i = 0
        while (i < trimmedRight.length) {
            if (trimmedRight[i] == '\\' && i + 1 < trimmedRight.length) {
                i += 2
                continue
            }
            if (trimmedRight[i] == '"') quotes++
            i++
        }
        if (quotes % 2 == 0) return null
        return trimmedRight.dropLast(1).takeLastWhile { it.isDigit() }
    }

    private fun findBlockEnd(document: Document, openLine: Int, parentIndent: Int): Int {
        val text = document.charsSequence
        var lastChild = openLine
        for (sub in (openLine + 1) until document.lineCount) {
            val s = document.getLineStartOffset(sub)
            val e = document.getLineEndOffset(sub)
            val subText = text.subSequence(s, e).toString()
            if (subText.isBlank()) continue
            if (getIndent(subText) > parentIndent) {
                lastChild = sub
            } else {
                break
            }
        }
        return lastChild
    }

    private fun getIndent(line: String): Int =
        line.takeWhile { it == ' ' || it == '\t' }.length
}