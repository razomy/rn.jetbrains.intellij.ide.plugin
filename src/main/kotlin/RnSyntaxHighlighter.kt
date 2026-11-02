package org.razomy.rn.intellij.plugin

import com.intellij.lang.ASTNode
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lexer.LexerBase
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTempTextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
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
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import javax.swing.Icon

class RnTokenType(debugName: String) : IElementType(debugName, RnLanguage)
class RnElementType(debugName: String) : IElementType(debugName, RnLanguage)

object RnTypes {
    @JvmField val COMMENT = RnTokenType("COMMENT")
    @JvmField val KEY = RnTokenType("KEY")
    @JvmField val SCHEMA_PORTAL = RnTokenType("SCHEMA_PORTAL")
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
 * Context-aware Lexer for Razomy Notation (.rn) v0.1.0:
 * Recognizes Homoiconic Comments (`# `), Column-0 Raw Strings (`(tag)?(N)?"` ... `"(N)?`),
 * Symbols (`'pk`), Typed Literals (`Date"..."`), Schema Portals (`Sql`, `Html`, `Scss`, `Md`),
 * Algebraic Operators (`&`, `^`, `/`), Symmetric Triad Brackets (`[]`, `{}`, `()`), and Auto-Typed Scalars.
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

        // Homoiconic Comment: `# ` or `#` at end of line
        if (ch == '#' && isAtStartOfLineOrWhitespace(tokenStart)) {
            val nextCh = if (pos + 1 < endOffset) buffer[pos + 1] else '\n'
            if (nextCh == ' ' || nextCh == '\t' || nextCh == '\n' || nextCh == '\r') {
                while (pos < endOffset && buffer[pos] != '\n') {
                    pos++
                }
                tokenEnd = pos
                currentToken = RnTypes.COMMENT
                return
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

        // Algebraic Composition Operators (` & `, ` ^ `, ` / `)
        if ((ch == '&' || ch == '^' || ch == '/') && isSurroundedBySpace(pos)) {
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
            if (c.isWhitespace() || c == '[' || c == ']' || c == '{' || c == '}' || c == '(' || c == ')' || c == ',' || c == ';') {
                break
            }
            if (c == '"') {
                // Check if this is a Column-0 raw string opener `(tag)?(N)?"` at end of line
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

        tokenEnd = pos
        val word = buffer.subSequence(tokenStart, tokenEnd).toString()

        currentToken = when {
            word == "true" || word == "false" || word == "null" || word == "None" ||
                word in setOf("title", "subtitle", "part", "section", "topic", "quote") -> RnTypes.KEYWORD
            isNumberLiteral(word) -> RnTypes.NUMBER
            isPascalCase(word) && isFollowedByBracketOnLine(tokenEnd) -> RnTypes.SCHEMA_PORTAL
            isFirstTokenOnLine(tokenStart) -> RnTypes.KEY
            else -> RnTypes.TEXT
        }
    }

    private fun consumeCol0RawString(rankDigits: String) {
        // Consume the opening `"` and everything until `\n"<rankDigits>` at Column 0
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

    private fun isEndOfLineQuote(quotePos: Int): Boolean {
        var i = quotePos + 1
        while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t' || buffer[i] == '\r')) {
            i++
        }
        return i >= endOffset || buffer[i] == '\n'
    }

    private fun isAtStartOfLineOrWhitespace(idx: Int): Boolean {
        return idx == 0 || buffer[idx - 1].isWhitespace()
    }

    private fun isSurroundedBySpace(idx: Int): Boolean {
        val prevSpace = idx > startOffset && (buffer[idx - 1] == ' ' || buffer[idx - 1] == '\t')
        val nextSpace = idx + 1 < endOffset && (buffer[idx + 1] == ' ' || buffer[idx + 1] == '\t')
        return prevSpace && nextSpace
    }

    private fun isFirstTokenOnLine(idx: Int): Boolean {
        var i = idx - 1
        while (i >= startOffset && (buffer[i] == ' ' || buffer[i] == '\t')) {
            i--
        }
        return i < startOffset || buffer[i] == '\n' || buffer[i] == '{'
    }

    private fun isFollowedByBracketOnLine(afterIdx: Int): Boolean {
        var i = afterIdx
        while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t')) {
            i++
        }
        return i < endOffset && (buffer[i] == '{' || buffer[i] == '(' || buffer[i] == '[')
    }

    private fun isPascalCase(s: String): Boolean {
        return s.isNotEmpty() && s[0].isUpperCase() && s.all { it.isLetterOrDigit() || it == '_' }
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
 * PSI Host Element for Column-0 Multiline Raw Strings (`json"`, `ts"`, `sql"`, `html"`, `rn2"`, etc.).
 * Enables IntelliJ Language Injection (syntax highlighting, inspections, completion inside embedded code blocks).
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

    /**
     * Extracts the language tag prefix from `(tag)?(N)?"` (e.g. `json"` -> `json`, `rn2"` -> `rn`, `sh1"` -> `sh`).
     */
    fun getLanguageTag(): String {
        val raw = text
        val firstNewline = raw.indexOf('\n')
        val header = if (firstNewline >= 0) raw.substring(0, firstNewline).trim() else raw.trim()
        if (!header.endsWith('"')) return ""
        val withoutQuote = header.dropLast(1)
        return withoutQuote.dropLastWhile { it.isDigit() }.trim()
    }

    /**
     * Computes the inner text range (relative to this PSI element) inside the Column-0 multiline string,
     * excluding the opening `(tag)?(N)?"\n` line and the closing `\n"(N)?` line.
     */
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

/**
 * Injects guest languages (JSON, TypeScript/JavaScript, SQL, HTML, SCSS/CSS, Markdown, Shell, Razomy Notation, etc.)
 * into tagged Column-0 multiline strings (`json"`, `ts"`, `sql"`, `html"`, `scss"`, `md"`, `sh1"`, `rn2"`, etc.).
 */
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

        // Map common shorthand tags to IntelliJ Language IDs and file extensions
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

        // 1. Try direct Language ID lookup (case-insensitive)
        val registered = Language.getRegisteredLanguages()
        for (candidate in idCandidates) {
            Language.findLanguageByID(candidate)?.let { return it }
            registered.firstOrNull { it.id.equals(candidate, ignoreCase = true) }?.let { return it }
        }

        // 2. Fallback: lookup by file extension in IntelliJ FileTypeManager
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
    override fun getSyntaxHighlighter(project: Project?, virtualFile: com.intellij.openapi.vfs.VirtualFile?): SyntaxHighlighter =
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
 * 1-Space Topological Folding Builder for `.rn` blocks (`{`, `(`, `[` at end of line) and Column-0 Raw Strings.
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
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val indent = lineText.takeWhile { it == ' ' || it == '\t' }.length
            if (trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[")) {
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

class RnColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        AttributesDescriptor("Comment (# )", RnSyntaxHighlighter.COMMENT),
        AttributesDescriptor("Key / Tag Name", RnSyntaxHighlighter.KEY),
        AttributesDescriptor("Schema Portal (Sql, Html, Scss, Md)", RnSyntaxHighlighter.SCHEMA_PORTAL),
        AttributesDescriptor("Symbol ('pk, 'GET)", RnSyntaxHighlighter.SYMBOL),
        AttributesDescriptor("Typed Literal (Date\"...\")", RnSyntaxHighlighter.TYPED_LITERAL),
        AttributesDescriptor("String", RnSyntaxHighlighter.STRING),
        AttributesDescriptor("Column-0 Raw String", RnSyntaxHighlighter.COL0_RAW),
        AttributesDescriptor("Number", RnSyntaxHighlighter.NUMBER),
        AttributesDescriptor("Keyword / Boolean / Null", RnSyntaxHighlighter.KEYWORD),
        AttributesDescriptor("Algebra Operator (&, ^, /)", RnSyntaxHighlighter.OPERATOR),
        AttributesDescriptor("Symmetric Triad Bracket ([], {}, ())", RnSyntaxHighlighter.BRACKET)
    )

    override fun getIcon(): Icon = RnIcons.FILE
    override fun getHighlighter(): SyntaxHighlighter = RnSyntaxHighlighter()
    override fun getDemoText(): String = """
        # Razomy Notation (.rn) v0.1.0 Sample
        cluster.primary.database {
         engine postgres
         port 5432
         tls true
         created_at Date"12.12.23"
         role 'pk
         welcome_msg 1"
        Line 1 starts at Column 0!
        "1
        
        UserCard & BaseCard / debug {
         query Sql (
          select, from, where
          "*", users, "id = :id"
         view Html [
          div{class card} [
           h2 [ User Profile ]
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "Razomy Notation (.rn)"
}