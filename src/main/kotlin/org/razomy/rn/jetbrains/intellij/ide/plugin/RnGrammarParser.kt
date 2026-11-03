package org.razomy.rn.jetbrains.intellij.ide.plugin

import kotlin.math.max

/**
 * Declarative PEG / Pest-equivalent AST & Schema Engine for Razomy Notation (`.rn`) v1.0.0.
 *
 * Why a custom indentation-aware PEG parser in Kotlin instead of Rust `pest`?
 * 1. IntelliJ Platform plugins run on the JVM and require incremental, zero-JNI-overhead AST/PSI
 *    construction on every keystroke.
 * 2. `.rn` Axiom 2 (Spatial Topology with adaptive `+Q` indentation, inline vs. block closure, and
 *    Column-0 raw string suspension `(Type)?(N)?"` ... `"(N)?`) is context-sensitive and cleaner
 *    to express in a dedicated stateful PEG + indentation stack than pure context-free `pest`.
 * 3. We also provide the canonical `grammar.pest` reference in `SPECIFICATION.md.rn` for Rust/WASM tools.
 */
object RnGrammarParser {

    sealed class RnValue {
        object NoneVal : RnValue()
        data class BoolVal(val value: Boolean) : RnValue()
        data class NumVal(val raw: String, val number: Double) : RnValue()
        data class SymVal(val symbol: String) : RnValue()
        data class StrVal(val text: String, val quoted: Boolean = false, val typeTag: String? = null) : RnValue()
        data class StreamVal(val typeTag: String?, val children: List<RnNode>) : RnValue()
        data class FormVal(val typeTag: String?, val entries: List<RnNode>) : RnValue()
        data class Tuple1DVal(val typeTag: String?, val items: List<RnValue>) : RnValue()
        data class Table2DVal(
            val typeTag: String?,
            val headers: List<String>,
            val rows: List<List<RnValue>>
        ) : RnValue()
    }

    data class RnNode(
        val key: String,
        val props: Map<String, RnValue> = emptyMap(),
        val slots: List<RnValue> = emptyList(),
        val algebra: List<Pair<Char, String>> = emptyList(),
        val value: RnValue = RnValue.NoneVal,
        val line: Int = 0,
        val indent: Int = 0,
        val endLine: Int = 0,
        val isPlainStreamLine: Boolean = false
    )

    data class RnDiagnostic(
        val line: Int,
        val startOffset: Int,
        val endOffset: Int,
        val message: String,
        val severity: Severity = Severity.ERROR
    ) {
        enum class Severity { ERROR, WARNING }
    }

    /**
     * Standard Built-in Type Constructors & Schema Archetypes in Razomy Notation v1.0.0.
     */
    val BUILTIN_SCALAR_AND_SCHEMA_TYPES: Set<String> = setOf(
        "Str", "String", "Text",
        "Int", "Integer", "Long",
        "Float", "Double", "Num", "Number", "Dec", "Decimal",
        "Bool", "Boolean",
        "Uuid", "Email", "Url", "Uri", "Regex",
        "Date", "DateTime", "Timestamp", "Time", "Duration",
        "Usd", "Eur", "Uah", "Gbp", "Jpy", "Cny", "Chf", "Cad", "Aud", "Pln",
        "Km", "M", "Cm", "Mm", "Kg", "G", "Ms", "Sec", "Min", "Hr", "Pct", "Px", "Rem",
        "Ver", "Semver",
        "Enum", "List", "Set", "Map", "Tuple", "Any", "Object", "Optional",
        "Md", "Sql", "Html", "Xml", "Scss", "Css", "Json", "Yaml", "Toml",
        "Ts", "Js", "Rs", "Py", "Kt", "Java", "Go", "Sh", "Bash", "Tf", "Proto", "Csv", "Mmd", "Pest",
        "Rn", "Rn1", "Rn2", "Rl"
    )

    val CURRENCY_AND_UNIT_PREFIXES: Set<String> = setOf(
        "Usd", "Eur", "Uah", "Gbp", "Jpy", "Cny", "Chf", "Cad", "Aud", "Pln",
        "Km", "Cm", "Mm", "Kg", "Ms", "Sec", "Pct", "Px", "Rem", "Ver", "Semver"
    )

    /**
     * Standardized Multilingual Markdown (`Md`) Keywords for Top 20 World Languages + Ukrainian (`uk`).
     */
    object I18nMdKeywords {
        val TITLE_H1 = setOf(
            "title", "h1",
            "заголовок", // uk, ru
            "titulo", "título", // es, pt
            "标题", // zh
            "शीर्षक", // hi
            "عنوان", // ar, fa
            "শিরোনাম", // bn
            "titre", // fr
            "titel", // de, nl
            "タイトル", // ja
            "제목", // ko
            "titolo", // it
            "baslik", "başlık", // tr
            "tieude", "tiêu_đề", // vi
            "tytul", "tytuł", // pl
            "judul", // id
            "หัวข้อ" // th
        )

        val PART_H2 = setOf(
            "part", "subtitle", "h2",
            "частина", "підзаголовок", // uk
            "часть", "подзаголовок", // ru
            "parte", // es, pt, it
            "部分", // zh
            "भाग", // hi
            "جزء", // ar
            "অংশ", // bn
            "partie", // fr
            "teil", // de
            "部", // ja
            "파트", // ko
            "bolum", "bölüm", // tr
            "phan", "phần", // vi
            "czesc", "część", // pl
            "deel", // nl
            "bagian", // id
            "ส่วน", // th
            "بخش" // fa
        )

        val SECTION_H3 = setOf(
            "section", "h3",
            "розділ", // uk
            "раздел", // ru
            "seccion", "sección", // es
            "secao", "seção", // pt
            "章节", // zh
            "अनुभाग", // hi
            "قسم", // ar
            "বিভাগ", // bn
            "abschnitt", // de
            "節", // ja
            "섹션", // ko
            "sezione", // it
            "kisim", "kısım", // tr
            "muc", "mục", // vi
            "sekcja", // pl
            "sectie", // nl
            "bab", // id
            "หมวด", // th
            "فصل" // fa
        )

        val TOPIC_H4 = setOf(
            "topic", "h4",
            "тема", // uk, ru, es
            "topico", "tópico", // pt
            "主题", // zh
            "विषय", // hi
            "موضوع", // ar, fa
            "বিষয়", // bn
            "sujet", // fr
            "thema", // de
            "トピック", // ja
            "주제", // ko
            "argomento", // it
            "konu", // tr
            "chude", "chủ_đề", // vi
            "temat", // pl
            "onderwerp", // nl
            "topik", // id
            "ประเด็น" // th
        )

        val UL_LIST = setOf(
            "ul", "список", "lista", "列表", "सूची", "قائمة", "তালিকা", "liste",
            "リスト", "목록", "danhsach", "lijst", "daftar", "รายการ", "فهرست"
        )

        val OL_LIST = setOf(
            "ol", "нум", "num", "编号", "क्रम", "ترقيم", "ক্রম",
            "番号", "번호", "stt", "urut", "ลำดับ", "شماره"
        )

        val QUOTE = setOf(
            "quote", "цитата", "cita", "citacao", "citação", "引用", "उद्धरण", "اقتباس",
            "উদ্ধৃতি", "citation", "zitat", "인용", "citazione", "alinti", "alıntı",
            "trichdan", "cytat", "citaat", "kutipan", "อ้างอิง", "نقل"
        )

        val NOTE = setOf(
            "note", "примітка", "заметка", "nota", "注意", "टिप्पणी", "ملاحظة",
            "নোট", "hinweis", "注記", "참고", "not", "ghichu", "notatka", "notitie",
            "catatan", "หมายเหตุ", "یادداشت"
        )

        val WARN = setOf(
            "warn", "увага", "внимание", "aviso", "警告", "चेतावनी", "تحذير",
            "সতর্কতা", "alerte", "warnung", "경고", "uyari", "uyarı", "canhbao",
            "uwaga", "waarschuwing", "peringatan", "คำเตือน", "هشدار"
        )

        val ALL_DOCUMENT_KEYWORDS: Set<String> =
            TITLE_H1 + PART_H2 + SECTION_H3 + TOPIC_H4 + UL_LIST + OL_LIST + QUOTE + NOTE + WARN +
                    setOf("h5", "h6", "meta", "example", "code", "код", "table", "таблиця", "таблица")

        fun getHeadingLevel(word: String): Int = when (word.lowercase()) {
            in TITLE_H1 -> 1
            in PART_H2 -> 2
            in SECTION_H3 -> 3
            in TOPIC_H4 -> 4
            "h5" -> 5
            "h6" -> 6
            else -> 0
        }
    }

    /**
     * Splits a 2D table row by top-level `,` into exactly the expected number of columns (`expectedCols`).
     *
     * Fixes the bug where unquoted descriptions in the last column (e.g. in `vocabulary (` where
     * the 3rd column is `The variable, field, or tag identifier...`) were split into extra columns!
     * If `expectedCols > 0`, once `expectedCols - 1` top-level commas have been split, the remainder
     * of the row is preserved intact as the final cell!
     */
    fun splitTableRow(
        rawLine: String,
        expectedCols: Int = -1
    ): Pair<List<String>, String?> {
        val cleanLine = rawLine.trimEnd('\r')
        val withoutComment = stripInlineComment(cleanLine)
        val trailingComment = if (withoutComment.length < cleanLine.length) {
            cleanLine.substring(withoutComment.length).trim()
        } else {
            null
        }

        val trimmed = withoutComment.trim()
        if (trimmed.isEmpty()) return Pair(emptyList(), trailingComment)

        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var inBackticks = false
        var bracketDepth = 0
        var i = 0

        while (i < trimmed.length) {
            // If we already collected (expectedCols - 1) cells, the rest of the line is the last cell!
            if (expectedCols > 1 && cells.size == expectedCols - 1) {
                current.append(trimmed.substring(i))
                break
            }

            val ch = trimmed[i]
            if (inQuotes) {
                current.append(ch)
                if (ch == '\\' && i + 1 < trimmed.length) {
                    current.append(trimmed[i + 1])
                    i += 2
                    continue
                }
                if (ch == '"') inQuotes = false
                i++
                continue
            }

            if (inBackticks) {
                current.append(ch)
                if (ch == '`') inBackticks = false
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
        // Normalize multiple internal alignment spaces inside unquoted prose cells
        val normalizedCells = cells.mapIndexed { idx, cell ->
            if (expectedCols > 1 && idx == expectedCols - 1 && !cell.startsWith("\"")) {
                collapseAlignmentSpaces(cell)
            } else {
                cell
            }
        }
        return Pair(normalizedCells, trailingComment)
    }

    /**
     * Collapses runs of 2+ spaces that were previously inserted after commas inside a prose cell.
     */
    private fun collapseAlignmentSpaces(cell: String): String {
        val sb = StringBuilder()
        var inQuotes = false
        var inBackticks = false
        var spaceRun = 0
        for (ch in cell) {
            if (ch == '"') inQuotes = !inQuotes
            if (ch == '`') inBackticks = !inBackticks
            if (!inQuotes && !inBackticks && ch == ' ') {
                spaceRun++
                if (spaceRun == 1) sb.append(ch)
            } else {
                spaceRun = 0
                sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    fun stripInlineComment(line: String): String {
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
            when (ch) {
                '"' -> inQuotes = true
                '`' -> inBackticks = true
                '#' -> if (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t') {
                    val nextCh = if (i + 1 < line.length) line[i + 1] else '\n'
                    if (nextCh == ' ' || nextCh == '\t' || nextCh == '\n') {
                        return line.substring(0, i).trimEnd()
                    }
                }
            }
            i++
        }
        return line
    }

    /**
     * Validates a `.rn` document and returns syntax, indentation, Pentad bracket, and Schema validation diagnostics.
     */
    fun validateDocument(text: String, isMdDocument: Boolean = false): List<RnDiagnostic> {
        val diagnostics = mutableListOf<RnDiagnostic>()
        val lines = text.split("\n")
        val lineOffsets = IntArray(lines.size)
        var runningOffset = 0
        for (idx in lines.indices) {
            lineOffsets[idx] = runningOffset
            runningOffset += lines[idx].length + 1
        }

        // Schema registry: TypeName -> Map<FieldName, FieldSchema>
        data class FieldConstraint(
            val fieldName: String,
            val baseType: String,
            val optional: Boolean,
            val min: Double? = null,
            val max: Double? = null,
            val enumValues: Set<String> = emptySet(),
            val hasDefault: Boolean = false
        )

        val schemas = mutableMapOf<String, Map<String, FieldConstraint>>()

        var i = 0
        while (i < lines.size) {
            val rawLine = lines[i]
            val trimmedRight = rawLine.trimEnd('\r', ' ', '\t')
            val trimmed = trimmedRight.trim()
            val lineStart = lineOffsets[i]
            val lineEnd = lineStart + rawLine.length

            // 1. Check Column-0 Raw Multiline String closure
            val col0Rank = detectCol0RawOpenerRank(trimmedRight)
            if (col0Rank != null) {
                val closeMarker = "\"$col0Rank"
                val openLine = i
                i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trimEnd('\r', ' ', '\t') == closeMarker) {
                        closed = true
                        break
                    }
                    i++
                }
                if (!closed) {
                    diagnostics.add(
                        RnDiagnostic(
                            openLine,
                            lineOffsets[openLine],
                            lineOffsets[openLine] + lines[openLine].length,
                            "Unclosed Column-0 raw string block: expected '$closeMarker' at Column 0"
                        )
                    )
                    break
                }
                i++
                continue
            }

            // 2. Skip Block Comments (`#` at EOL)
            if (trimmed == "#") {
                val baseIndent = getIndent(rawLine)
                i++
                while (i < lines.size) {
                    val sub = lines[i]
                    if (sub.isNotBlank() && getIndent(sub) <= baseIndent) break
                    i++
                }
                continue
            }

            if (trimmed.isEmpty() || trimmed.startsWith("# ")) {
                i++
                continue
            }

            // 3. Check for standalone closing brackets on a line:
            // Allowed in Draft / Bracket Mode and automatically stripped + normalized to 1-space indent on Reformat Code (Ctrl+Alt+L)!
            if (trimmed == "}" || trimmed == "]" || trimmed == ")" ||
                trimmed == "}," || trimmed == "]," || trimmed == "),"
            ) {
                diagnostics.add(
                    RnDiagnostic(
                        i,
                        lineStart + getIndent(rawLine),
                        lineEnd,
                        "Optional closing bracket '$trimmed': press Ctrl+Alt+L (Cmd+Option+L) to auto-format into canonical bracketless .rn",
                        RnDiagnostic.Severity.WARNING
                    )
                )
                i++
                continue
            }

            // 4. Check for unclosed inline quotes on a single line (when not a Col-0 opener)
            val codePart = stripInlineComment(trimmedRight)
            if (hasUnclosedInlineQuote(codePart)) {
                diagnostics.add(
                    RnDiagnostic(
                        i,
                        lineStart + getIndent(rawLine),
                        lineEnd,
                        "Unclosed quoted string literal on line"
                    )
                )
            }

            // 5. Check balanced inline brackets on the line (when not opening a block at EOL)
            val cleanEnd = codePart.trimEnd()
            if (!cleanEnd.endsWith("{") && !cleanEnd.endsWith("(") && !cleanEnd.endsWith("[")) {
                val unbalancedMsg = checkInlineBracketBalance(cleanEnd)
                if (unbalancedMsg != null) {
                    diagnostics.add(
                        RnDiagnostic(
                            i,
                            lineStart + getIndent(rawLine),
                            lineEnd,
                            unbalancedMsg,
                            RnDiagnostic.Severity.WARNING
                        )
                    )
                }
            }

            // 6. Collect Schema Definitions (`SchemaName {` where key is PascalCase) & Validate Typed Instances (`inst SchemaName{`)
            val indent = getIndent(rawLine)
            if (cleanEnd.endsWith("{")) {
                val header = cleanEnd.dropLast(1).trim()
                val parts = header.split(Regex("\\s+"))
                if (parts.size == 1 && isPascalCase(parts[0])) {
                    val schemaName = parts[0]
                    val blockEnd = findBlockEnd(lines, i, indent)
                    val fields = mutableMapOf<String, FieldConstraint>()
                    for (sub in (i + 1)..blockEnd) {
                        val subLine = stripInlineComment(lines[sub]).trim()
                        if (subLine.isEmpty() || subLine.startsWith("#")) continue
                        if (getIndent(lines[sub]) != indent + 1 && getIndent(lines[sub]) != indent + 2) continue
                        val fieldKey = subLine.takeWhile { !it.isWhitespace() && it != '{' && it != '(' }
                        val rest = subLine.substring(fieldKey.length).trim()
                        if (fieldKey.isNotEmpty() && rest.isNotEmpty()) {
                            val constraint = parseFieldConstraint(fieldKey, rest)
                            if (constraint != null) {
                                fields[fieldKey] = constraint as FieldConstraint
                            }
                        }
                    }
                    if (fields.isNotEmpty()) {
                        schemas[schemaName] = fields
                    }
                } else if (parts.size >= 2) {
                    val lastPart = parts.last()
                    if (isPascalCase(lastPart) && schemas.containsKey(lastPart)) {
                        val schemaName = lastPart
                        val schemaFields = schemas[schemaName]!!
                        val blockEnd = findBlockEnd(lines, i, indent)
                        val providedKeys = mutableSetOf<String>()
                        for (sub in (i + 1)..blockEnd) {
                            val subRaw = lines[sub]
                            val subTrimmed = stripInlineComment(subRaw).trim()
                            if (subTrimmed.isEmpty() || subTrimmed.startsWith("#")) continue
                            if (getIndent(subRaw) <= indent) break
                            if (getIndent(subRaw) > indent + 2) continue
                            val k = subTrimmed.takeWhile { !it.isWhitespace() && it != '{' && it != '(' }
                            val v = subTrimmed.substring(k.length).trim()
                            providedKeys.add(k)
                            val fieldSpec = schemaFields[k]
                            if (fieldSpec == null) {
                                diagnostics.add(
                                    RnDiagnostic(
                                        sub,
                                        lineOffsets[sub] + getIndent(subRaw),
                                        lineOffsets[sub] + subRaw.length,
                                        "Schema '$schemaName': Unknown property '$k'",
                                        RnDiagnostic.Severity.WARNING
                                    )
                                )
                            } else {
                                val err = validateValueAgainstConstraint(v, fieldSpec)
                                if (err != null) {
                                    diagnostics.add(
                                        RnDiagnostic(
                                            sub,
                                            lineOffsets[sub] + getIndent(subRaw),
                                            lineOffsets[sub] + subRaw.length,
                                            "Schema '$schemaName.$k': $err",
                                            RnDiagnostic.Severity.ERROR
                                        )
                                    )
                                }
                            }
                        }
                        // Check required fields
                        for ((reqKey, spec) in schemaFields) {
                            if (!spec.optional && !spec.hasDefault && reqKey !in providedKeys) {
                                diagnostics.add(
                                    RnDiagnostic(
                                        i,
                                        lineStart + indent,
                                        lineEnd,
                                        "Schema '$schemaName': Missing required property '$reqKey' (${spec.baseType})",
                                        RnDiagnostic.Severity.ERROR
                                    )
                                )
                            }
                        }
                    }
                }
            }

            i++
        }

        return diagnostics
    }

    private fun parseFieldConstraint(
        fieldName: String,
        specText: String
    ): Any? {
        // Used internally by validateDocument via local class reflection-free helper
        return null
    }

    data class SchemaFieldSpec(
        val fieldName: String,
        val baseType: String,
        val optional: Boolean,
        val min: Double? = null,
        val max: Double? = null,
        val pattern: String? = null,
        val enumValues: Set<String> = emptySet(),
        val hasDefault: Boolean = false
    )

    /**
     * Extracts all PascalCase Schema Definitions (`UserForm { ... }`) from a `.rn` text.
     */
    fun extractSchemasFromText(text: String): Map<String, Map<String, SchemaFieldSpec>> {
        val lines = text.split("\n")
        val schemas = mutableMapOf<String, Map<String, SchemaFieldSpec>>()
        var i = 0
        while (i < lines.size) {
            val rawLine = lines[i]
            val trimmedRight = rawLine.trimEnd('\r', ' ', '\t')
            val col0Rank = detectCol0RawOpenerRank(trimmedRight)
            if (col0Rank != null) {
                val closeMarker = "\"$col0Rank"
                i++
                while (i < lines.size && lines[i].trimEnd('\r', ' ', '\t') != closeMarker) {
                    i++
                }
                i++
                continue
            }

            val cleanEnd = stripInlineComment(trimmedRight).trimEnd()
            if (cleanEnd.endsWith("{")) {
                val indent = getIndent(rawLine)
                val header = cleanEnd.dropLast(1).trim()
                val parts = header.split(Regex("\\s+"))
                if (parts.size == 1 && isPascalCase(parts[0])) {
                    val schemaName = parts[0]
                    val blockEnd = findBlockEnd(lines, i, indent)
                    val fields = mutableMapOf<String, SchemaFieldSpec>()
                    for (sub in (i + 1)..blockEnd) {
                        val subRaw = lines[sub]
                        val subLine = stripInlineComment(subRaw).trim()
                        if (subLine.isEmpty() || subLine.startsWith("#")) continue
                        if (getIndent(subRaw) <= indent || getIndent(subRaw) > indent + 2) continue
                        val fieldKey = subLine.takeWhile { !it.isWhitespace() && it != '{' && it != '(' }
                        val rest = subLine.substring(fieldKey.length).trim()
                        if (fieldKey.isNotEmpty() && rest.isNotEmpty()) {
                            parseSchemaFieldSpec(fieldKey, rest)?.let { fields[fieldKey] = it }
                        }
                    }
                    if (fields.isNotEmpty()) {
                        schemas[schemaName] = fields
                    }
                }
            }
            i++
        }
        return schemas
    }

    /**
     * Validates schemas, typed instances, unknown types, forbidden `?` operator, and `meta { schema ... }` rules.
     * Accepts optional `externalSchemas` resolved from sibling/imported project `.rn` files.
     */
    fun validateSchemasInDocument(
        text: String,
        externalSchemas: Map<String, Map<String, SchemaFieldSpec>> = emptyMap()
    ): List<RnDiagnostic> {
        val diagnostics = mutableListOf<RnDiagnostic>()
        val lines = text.split("\n")
        val lineOffsets = IntArray(lines.size)
        var runningOffset = 0
        for (idx in lines.indices) {
            lineOffsets[idx] = runningOffset
            runningOffset += lines[idx].length + 1
        }

        val schemas = mutableMapOf<String, Map<String, SchemaFieldSpec>>()
        schemas.putAll(externalSchemas)
        schemas.putAll(extractSchemasFromText(text))

        // Check if `meta { schema SomeSchema }` is declared at top of file (supports `./models.rl/UserForm` and `./models.rl#UserForm`)
        var fileDefaultSchema: String? = null
        if (lines.isNotEmpty() && stripInlineComment(lines[0]).trim() == "meta {") {
            val metaEnd = findBlockEnd(lines, 0, getIndent(lines[0]))
            for (m in 1..metaEnd) {
                val mLine = stripInlineComment(lines[m]).trim()
                if (mLine.startsWith("schema ")) {
                    val rawRef = mLine.removePrefix("schema").trim().removeSurrounding("\"")
                    val targetSchema = extractSchemaNameFromPathOrRef(rawRef)
                    if (isPascalCase(targetSchema)) {
                        fileDefaultSchema = targetSchema
                        if (targetSchema !in schemas && targetSchema !in BUILTIN_SCALAR_AND_SCHEMA_TYPES) {
                            diagnostics.add(
                                RnDiagnostic(
                                    m,
                                    lineOffsets[m] + getIndent(lines[m]),
                                    lineOffsets[m] + lines[m].length,
                                    "Unknown schema '$targetSchema' referenced in meta block",
                                    RnDiagnostic.Severity.ERROR
                                )
                            )
                        }
                    }
                }
            }
        }

        var i = 0
        while (i < lines.size) {
            val rawLine = lines[i]
            val trimmedRight = rawLine.trimEnd('\r', ' ', '\t')

            // Skip Column-0 Raw Multiline Strings so embedded examples/code are not falsely flagged
            val col0Rank = detectCol0RawOpenerRank(trimmedRight)
            if (col0Rank != null) {
                val closeMarker = "\"$col0Rank"
                i++
                while (i < lines.size && lines[i].trimEnd('\r', ' ', '\t') != closeMarker) {
                    i++
                }
                i++
                continue
            }

            // Skip Block Comments
            if (trimmedRight.trim() == "#") {
                val baseIndent = getIndent(rawLine)
                i++
                while (i < lines.size) {
                    if (lines[i].isNotBlank() && getIndent(lines[i]) <= baseIndent) break
                    i++
                }
                continue
            }

            val cleanEnd = stripInlineComment(trimmedRight).trimEnd()
            if (cleanEnd.isEmpty() || cleanEnd.startsWith("#")) {
                i++
                continue
            }

            val indent = getIndent(rawLine)

            // Forbid `Type?` syntax everywhere and suggest `& Optional`
//            val forbiddenQMatch = Regex("""\b([A-Z][A-Za-z0-9_]*)\?""").find(cleanEnd)
//            if (forbiddenQMatch != null) {
//                val typeName = forbiddenQMatch.groupValues[1]
//                diagnostics.add(
//                    RnDiagnostic(
//                        i,
//                        lineOffsets[i] + indent,
//                        lineOffsets[i] + rawLine.length,
//                        "Operator '?' ('$typeName?') is not used in Razomy Notation. Use '$typeName & Optional' instead.",
//                        RnDiagnostic.Severity.ERROR
//                    )
//                )
//            }

            if (cleanEnd.endsWith("{")) {
                val header = cleanEnd.dropLast(1).trim()
                val parts = header.split(Regex("\\s+"))
                val blockEnd = findBlockEnd(lines, i, indent)

                if (parts.size >= 2 && parts[1] != "&" && parts[1] != "^" && parts[1] != "/") {
                    val rawTypeToken = parts.last()
                    val resolvedType = extractSchemaNameFromPathOrRef(rawTypeToken)
                    if (isPascalCase(resolvedType)) {
                        val schemaFields = schemas[resolvedType]
                        if (schemaFields != null) {
                            validateInstanceAgainstSchema(
                                lines, i, blockEnd, indent, resolvedType, schemaFields, lineOffsets, diagnostics
                            )
                        } else if (resolvedType !in BUILTIN_SCALAR_AND_SCHEMA_TYPES) {
                            diagnostics.add(
                                RnDiagnostic(
                                    i,
                                    lineOffsets[i] + indent,
                                    lineOffsets[i] + rawLine.length,
                                    "Unknown type or schema '$resolvedType'. Define '$resolvedType {' or use a standard type.",
                                    RnDiagnostic.Severity.ERROR
                                )
                            )
                        }
                    } else if (rawTypeToken.all { it.isLetterOrDigit() || it == '_' }) {
                        diagnostics.add(
                            RnDiagnostic(
                                i,
                                lineOffsets[i] + indent,
                                lineOffsets[i] + rawLine.length,
                                "Invalid type constructor '$rawTypeToken': Type names must be PascalCase (e.g. UserForm{)",
                                RnDiagnostic.Severity.ERROR
                            )
                        )
                    }
                } else if (parts.size == 1 && !isPascalCase(parts[0]) && parts[0] !in setOf("meta", "example")) {
                    // Untyped block e.g. `valid_user {`: check if `fileDefaultSchema` is active OR if its keys match a defined schema in this file!
                    val nodeName = parts[0]
                    val targetSchemaName = fileDefaultSchema ?: inferMatchingSchemaForUntypedBlock(
                        lines, i, blockEnd, indent, schemas
                    )
                    if (targetSchemaName != null && schemas.containsKey(targetSchemaName)) {
                        diagnostics.add(
                            RnDiagnostic(
                                i,
                                lineOffsets[i] + indent,
                                lineOffsets[i] + rawLine.length,
                                "Missing schema type '$targetSchemaName' on node '$nodeName'. Write '$nodeName $targetSchemaName{' to validate.",
                                RnDiagnostic.Severity.WARNING
                            )
                        )
                        validateInstanceAgainstSchema(
                            lines, i, blockEnd, indent, targetSchemaName, schemas[targetSchemaName]!!, lineOffsets, diagnostics
                        )
                    }
                }
            }
            i++
        }
        return diagnostics
    }

    private fun inferMatchingSchemaForUntypedBlock(
        lines: List<String>,
        openLine: Int,
        blockEnd: Int,
        indent: Int,
        schemas: Map<String, Map<String, SchemaFieldSpec>>
    ): String? {
        if (schemas.isEmpty() || indent > 0) return null
        val childKeys = mutableSetOf<String>()
        for (sub in (openLine + 1)..blockEnd) {
            val subRaw = lines[sub]
            val subTrimmed = stripInlineComment(subRaw).trim()
            if (subTrimmed.isEmpty() || subTrimmed.startsWith("#")) continue
            val subIndent = getIndent(subRaw)
            if (subIndent <= indent) break
            if (subIndent > indent + 2) continue
            val k = subTrimmed.takeWhile { !it.isWhitespace() && it != '{' && it != '(' }
            if (k.isNotEmpty()) childKeys.add(k)
        }
        if (childKeys.size < 2) return null
        for ((schemaName, fields) in schemas) {
            val matchCount = childKeys.count { it in fields }
            if (matchCount >= 2 && matchCount >= childKeys.size / 2) {
                return schemaName
            }
        }
        return null
    }

    private fun validateInstanceAgainstSchema(
        lines: List<String>,
        openLine: Int,
        blockEnd: Int,
        indent: Int,
        typeName: String,
        schemaFields: Map<String, SchemaFieldSpec>,
        lineOffsets: IntArray,
        diagnostics: MutableList<RnDiagnostic>
    ) {
        val providedKeys = mutableSetOf<String>()
        for (sub in (openLine + 1)..blockEnd) {
            val subRaw = lines[sub]
            val subTrimmed = stripInlineComment(subRaw).trim()
            if (subTrimmed.isEmpty() || subTrimmed.startsWith("#")) continue
            val subIndent = getIndent(subRaw)
            if (subIndent <= indent) break
            if (subIndent > indent + 2) continue

            val k = subTrimmed.takeWhile { !it.isWhitespace() && it != '{' && it != '(' }
            val v = subTrimmed.substring(k.length).trim()
            providedKeys.add(k)

            val spec = schemaFields[k]
            if (spec == null) {
                diagnostics.add(
                    RnDiagnostic(
                        sub,
                        lineOffsets[sub] + subIndent,
                        lineOffsets[sub] + subRaw.length,
                        "Schema '$typeName': Unknown field '$k'",
                        RnDiagnostic.Severity.WARNING
                    )
                )
            } else {
                val err = checkFieldValue(v, spec)
                if (err != null) {
                    diagnostics.add(
                        RnDiagnostic(
                            sub,
                            lineOffsets[sub] + subIndent,
                            lineOffsets[sub] + subRaw.length,
                            "Schema '$typeName.$k': $err",
                            RnDiagnostic.Severity.ERROR
                        )
                    )
                }
            }
        }

        for ((reqKey, spec) in schemaFields) {
            if (!spec.optional && !spec.hasDefault && reqKey !in providedKeys) {
                diagnostics.add(
                    RnDiagnostic(
                        openLine,
                        lineOffsets[openLine] + indent,
                        lineOffsets[openLine] + lines[openLine].length,
                        "Schema '$typeName': Missing required field '$reqKey' (${spec.baseType})",
                        RnDiagnostic.Severity.ERROR
                    )
                )
            }
        }
    }

    private fun parseSchemaFieldSpec(fieldName: String, rest: String): SchemaFieldSpec? {
        if (rest == "{") {
            return SchemaFieldSpec(fieldName, "Object", optional = false)
        }
        val baseTypeRaw = rest.takeWhile { it.isLetterOrDigit() || it == '_' }
        if (baseTypeRaw.isEmpty() || !isPascalCase(baseTypeRaw)) return null
        val optional = rest.contains("& Optional") || rest.contains("default None")
        val hasDefault = rest.contains("default ")

        var min: Double? = null
        var max: Double? = null
        var pattern: String? = null
        val enumVals = mutableSetOf<String>()

        // 1. Parse explicit `{min 3, max 20, min_items 1, max_items 5, pattern Regex"^[a-z0-9_]+$"}` props
        val braceStart = rest.indexOf('{')
        val braceEnd = rest.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) {
            val propsBody = rest.substring(braceStart + 1, braceEnd)
            Regex("""\b(?:min|min_items|min_length)\s+(-?\d+(?:\.\d+)?)""").find(propsBody)?.let {
                min = it.groupValues[1].toDoubleOrNull()
            }
            Regex("""\b(?:max|max_items|max_length)\s+(-?\d+(?:\.\d+)?)""").find(propsBody)?.let {
                max = it.groupValues[1].toDoubleOrNull()
            }
            Regex("""\bpattern\s+(?:Regex)?"([^"]+)"""").find(propsBody)?.let {
                pattern = it.groupValues[1]
            }
        }

        // 2. Parse `(Slots)` for `Enum('admin, 'editor)` or `List(Str)` (and legacy numeric slots if present)
        val parenStart = rest.indexOf('(')
        val parenEnd = rest.indexOf(')', parenStart + 1)
        if (parenStart >= 0 && parenEnd > parenStart && (braceStart < 0 || parenStart < braceStart)) {
            val inside = rest.substring(parenStart + 1, parenEnd)
            val args = inside.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (baseTypeRaw == "Enum") {
                enumVals.addAll(args)
            } else if (min == null && args.size >= 2 && args[0].toDoubleOrNull() != null) {
                min = args[0].toDoubleOrNull()
                max = args[1].toDoubleOrNull()
            }
        }

        return SchemaFieldSpec(
            fieldName = fieldName,
            baseType = baseTypeRaw,
            optional = optional,
            min = min,
            max = max,
            pattern = pattern,
            enumValues = enumVals,
            hasDefault = hasDefault
        )
    }

    private fun checkFieldValue(rawVal: String, spec: SchemaFieldSpec): String? {
        if (rawVal.isEmpty() || rawVal == "None") {
            return if (spec.optional || spec.hasDefault) null else "Field cannot be None (add '& Optional' to schema if optional)"
        }
        val cleanStr = rawVal.removeSurrounding("\"")
        return when (spec.baseType) {
            "Int", "Integer", "Long" -> {
                val n = rawVal.toLongOrNull() ?: return "Expected Int, got '$rawVal'"
                if (spec.min != null && n < spec.min) return "Value $n is less than minimum ${spec.min.toInt()}"
                if (spec.max != null && n > spec.max) return "Value $n exceeds maximum ${spec.max.toInt()}"
                null
            }
            "Float", "Double", "Num", "Number", "Dec", "Decimal" -> {
                val n = rawVal.toDoubleOrNull() ?: return "Expected Number, got '$rawVal'"
                if (spec.min != null && n < spec.min) return "Value $n is less than minimum ${spec.min}"
                if (spec.max != null && n > spec.max) return "Value $n exceeds maximum ${spec.max}"
                null
            }
            in CURRENCY_AND_UNIT_PREFIXES -> {
                val stripped = if (rawVal.startsWith(spec.baseType)) {
                    rawVal.removePrefix(spec.baseType).removeSurrounding("\"")
                } else {
                    rawVal
                }
                if (spec.baseType == "Ver" || spec.baseType == "Semver") {
                    if (!stripped.matches(Regex("""\d+(\.\d+)+.*"""))) {
                        return "Expected ${spec.baseType} version (e.g. ${spec.baseType}1.0.0), got '$rawVal'"
                    }
                    return null
                }
                val n = stripped.toDoubleOrNull()
                    ?: return "Expected ${spec.baseType} number (e.g. ${spec.baseType}123.46), got '$rawVal'"
                if (spec.min != null && n < spec.min) return "Value $n is less than minimum ${spec.min}"
                if (spec.max != null && n > spec.max) return "Value $n exceeds maximum ${spec.max}"
                null
            }
            "Bool", "Boolean" -> {
                if (rawVal != "true" && rawVal != "false") "Expected Bool (true or false), got '$rawVal'" else null
            }
            "Email" -> {
                val addr = cleanStr.removePrefix("Email\"").removeSuffix("\"")
                if (!addr.contains("@") || !addr.substringAfter("@").contains(".")) {
                    "Invalid Email format '$addr'"
                } else null
            }
            "Url", "Uri" -> {
                val uri = cleanStr.removePrefix("Url\"").removeSuffix("\"")
                if (!uri.startsWith("http://") && !uri.startsWith("https://") && !uri.startsWith("/")) {
                    "Expected URL starting with https://, http://, or /, got '$uri'"
                } else null
            }
            "Str", "String", "Text" -> {
                val len = cleanStr.length
                if (spec.min != null && len < spec.min) return "String length $len is shorter than min ${spec.min.toInt()}"
                if (spec.max != null && len > spec.max) return "String length $len exceeds max ${spec.max.toInt()}"
                if (spec.pattern != null) {
                    val regex = runCatching { Regex(spec.pattern) }.getOrNull()
                    if (regex != null && !regex.matches(cleanStr)) {
                        return "Value '$cleanStr' does not match pattern Regex\"${spec.pattern}\""
                    }
                }
                null
            }
            "Enum" -> {
                if (spec.enumValues.isNotEmpty() && rawVal !in spec.enumValues && "'$cleanStr" !in spec.enumValues) {
                    "Expected one of ${spec.enumValues.joinToString(", ")}, got '$rawVal'"
                } else null
            }
            else -> null
        }
    }

    private fun validateValueAgainstConstraint(v: String, c: Any): String? = null

    private fun hasUnclosedInlineQuote(codePart: String): Boolean {
        var inQuotes = false
        var inBackticks = false
        var i = 0
        while (i < codePart.length) {
            val ch = codePart[i]
            if (inQuotes) {
                if (ch == '\\' && i + 1 < codePart.length) {
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
            if (ch == '"') inQuotes = true
            else if (ch == '`') inBackticks = true
            i++
        }
        return inQuotes
    }

    private fun checkInlineBracketBalance(line: String): String? {
        var inQuotes = false
        var inBackticks = false
        val stack = ArrayDeque<Char>()
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
                '[', '{', '(' -> stack.addLast(ch)
                ']', '}', ')' -> {
                    if (stack.isEmpty()) return "Unexpected closing bracket '$ch'"
                    val open = stack.removeLast()
                    val expected = when (open) {
                        '[' -> ']'
                        '{' -> '}'
                        '(' -> ')'
                        else -> ' '
                    }
                    if (ch != expected) {
                        return "Mismatched bracket: opened '$open' but closed with '$ch'"
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Converts `.rn` text to full GitHub-Flavored Markdown (`.md`), including:
     * - Frontmatter / `meta {` block skipping or YAML frontmatter rendering
     * - Headings (`title` -> `#`, `part` -> `##`, `section` -> `###`, `topic` -> `####`)
     * - 2D Relational Tables (`name (` -> GFM `| col1 | col2 |` tables with aligned columns!)
     * - Lists (`ul [` -> `- `, `ol [` -> `1. `)
     * - Quotes & Callouts (`quote [`, `note [`, `warn [`)
     * - Column-0 Fenced Code Blocks (`code Ts"`, `input Rn2"`, `output Md"`, etc.)
     * - Inline formatting (`b[bold]` -> `**bold**`, `i[italic]` -> `*italic*`, `s[strike]` -> `~~strike~~`, `a(/url)[text]` -> `[text](/url)`, `img(/url)[alt]` -> `![alt](/url)`, `span{class x}[text]` -> `<span class="x">text</span>`)
     */
    fun convertToMarkdown(source: String): String {
        val lines = source.split("\n")
        val sb = StringBuilder()
        var i = 0

        while (i < lines.size) {
            val rawLine = lines[i]
            val trimmedRight = rawLine.trimEnd('\r', ' ', '\t')
            val trimmed = trimmedRight.trim()
            val indent = getIndent(rawLine)

            // 1. Blank line
            if (trimmed.isEmpty()) {
                sb.append("\n")
                i++
                continue
            }

            // 2. Skip inline or block comments
            if (trimmed == "#") {
                i++
                while (i < lines.size) {
                    if (lines[i].isNotBlank() && getIndent(lines[i]) <= indent) break
                    i++
                }
                continue
            }
            if (trimmed.startsWith("# ")) {
                i++
                continue
            }

            // 3. Column-0 Raw Multiline String (`code Ts"`, `input Rn2"`, etc.)
            val col0Rank = detectCol0RawOpenerRank(trimmedRight)
            if (col0Rank != null) {
                val closeMarker = "\"$col0Rank"
                val beforeQuote = trimmedRight.trim().dropLast(1).dropLastWhile { it.isDigit() }
                val parts = beforeQuote.split(Regex("\\s+"), limit = 2)
                val keyName = parts[0]
                val langTag = if (parts.size == 2) parts[1].lowercase() else keyName.lowercase()
                val fenceLang = if (langTag in setOf("code", "input", "output", "raw")) "" else langTag

                val bodyLines = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val subRight = lines[i].trimEnd('\r', ' ', '\t')
                    if (subRight == closeMarker) break
                    bodyLines.add(lines[i].trimEnd('\r'))
                    i++
                }
                val maxBackticks = bodyLines.maxOfOrNull { line ->
                    Regex("`+").findAll(line).maxOfOrNull { it.value.length } ?: 0
                } ?: 0
                val fence = "`".repeat(max(3, maxBackticks + 1))
                sb.append("$fence$fenceLang\n")
                for (bLine in bodyLines) {
                    sb.append(bLine).append("\n")
                }
                sb.append("$fence\n")
                i++
                continue
            }

            // 4. Skip top-level `meta {` or render as YAML frontmatter if at line 0
            val codePart = stripInlineComment(trimmedRight).trimEnd()
            if (codePart == "meta {" && i == 0) {
                val blockEnd = findBlockEnd(lines, i, indent)
                i = blockEnd + 1
                continue
            }

            // 5. Document Headings (`title`, `subtitle`, `part`, `section`, `topic`, `h1`..`h6` + Top 20 languages + Ukrainian)
            val firstWord = codePart.takeWhile { !it.isWhitespace() && it != '[' && it != '{' && it != '(' }
            val firstWordLower = firstWord.lowercase()
            val afterWord = codePart.substring(firstWord.length).trim().removePrefix("[").trim()
            val headingLevel = I18nMdKeywords.getHeadingLevel(firstWordLower)
            if (headingLevel in 1..6 && afterWord.isNotEmpty() && !codePart.endsWith("{") && !codePart.endsWith("(")) {
                sb.append("#".repeat(headingLevel)).append(" ").append(renderInlineMarkdown(afterWord)).append("\n")
                i++
                continue
            }

            // 6. Unordered & Ordered Lists (`ul [`, `ol [`, `список [`, `нум [`, etc.)
            if ((firstWordLower in I18nMdKeywords.UL_LIST || firstWordLower in I18nMdKeywords.OL_LIST) && codePart.endsWith("[")) {
                val blockEnd = findBlockEnd(lines, i, indent)
                renderListBlockToMarkdown(
                    lines,
                    i + 1,
                    blockEnd,
                    firstWordLower in I18nMdKeywords.OL_LIST,
                    indent,
                    0,
                    sb
                )
                i = blockEnd + 1
                continue
            }

            // 7. Blockquotes & Callouts (`quote [`, `note [`, `warn [`, `цитата [`, `заметка [`, `внимание [`, `примітка [`, `увага [`)
            if ((firstWordLower in I18nMdKeywords.QUOTE || firstWordLower in I18nMdKeywords.NOTE || firstWordLower in I18nMdKeywords.WARN) && codePart.endsWith("[")) {
                val blockEnd = findBlockEnd(lines, i, indent)
                if (firstWordLower in I18nMdKeywords.NOTE) sb.append("> [!NOTE]\n")
                if (firstWordLower in I18nMdKeywords.WARN) sb.append("> [!WARNING]\n")
                for (sub in (i + 1)..blockEnd) {
                    val subTrim = lines[sub].trim()
                    if (subTrim.isEmpty()) {
                        sb.append(">\n")
                    } else if (!subTrim.startsWith("#")) {
                        sb.append("> ${renderInlineMarkdown(subTrim)}\n")
                    }
                }
                i = blockEnd + 1
                continue
            }

            // 8. Tuple Block `(` -> 2D Relational Table or 1D Bullet List!
            if (codePart.endsWith("(")) {
                val blockEnd = findBlockEnd(lines, i, indent)
                if (blockEnd > i) {
                    val renderedTable = tryRender2DTableToMarkdown(lines, i + 1, blockEnd)
                    if (renderedTable != null) {
                        sb.append(renderedTable)
                        i = blockEnd + 1
                        continue
                    } else {
                        // 1D Tuple list
                        for (sub in (i + 1)..blockEnd) {
                            val subTrim = stripInlineComment(lines[sub]).trim()
                            if (subTrim.isNotEmpty() && !subTrim.startsWith("#")) {
                                sb.append("- ${renderInlineMarkdown(subTrim.removeSurrounding("\""))}\n")
                            }
                        }
                        i = blockEnd + 1
                        continue
                    }
                }
            }

            // 9. Form Block `example {` or generic `key {` inside Markdown
            if (codePart.endsWith("{")) {
                val blockEnd = findBlockEnd(lines, i, indent)
                if (firstWord == "example") {
                    // Render inner input/output blocks directly into Markdown
                    val innerText = lines.subList(i + 1, blockEnd + 1).joinToString("\n")
                    sb.append(convertToMarkdown(innerText))
                    i = blockEnd + 1
                    continue
                }
            }

            // 10. Regular paragraph / outliner stream line
            sb.append(renderInlineMarkdown(codePart)).append("\n")
            i++
        }

        return sb.toString().trimEnd() + "\n"
    }

    private fun renderListBlockToMarkdown(
        lines: List<String>,
        startLine: Int,
        endLine: Int,
        ordered: Boolean,
        parentIndent: Int,
        depth: Int,
        sb: StringBuilder
    ) {
        var itemIndent = -1
        for (l in startLine..endLine) {
            if (lines[l].isNotBlank() && !lines[l].trim().startsWith("#")) {
                itemIndent = getIndent(lines[l])
                break
            }
        }
        if (itemIndent <= parentIndent) return

        var counter = 0
        var l = startLine
        while (l <= endLine) {
            val raw = lines[l]
            val trimmed = stripInlineComment(raw).trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                l++
                continue
            }
            val ind = getIndent(raw)
            if (ind == itemIndent) {
                counter++
                val prefix = if (ordered) "$counter. " else "- "
                val pad = "  ".repeat(depth)
                sb.append("$pad$prefix${renderInlineMarkdown(trimmed)}\n")
                val childEnd = findBlockEnd(lines, l, itemIndent).coerceAtMost(endLine)
                if (childEnd > l) {
                    renderListBlockToMarkdown(lines, l + 1, childEnd, ordered, itemIndent, depth + 1, sb)
                    l = childEnd + 1
                    continue
                }
            }
            l++
        }
    }

    /**
     * Renders a 2D Tuple table block (`vocabulary (`, `benchmarks (`, etc.) to a GFM Markdown table
     * with properly escaped pipes and aligned columns.
     */
    private fun tryRender2DTableToMarkdown(
        lines: List<String>,
        startLine: Int,
        endLine: Int
    ): String? {
        var firstChildIdx = -1
        for (idx in startLine..endLine) {
            val t = lines[idx].trim()
            if (t.isNotEmpty() && !t.startsWith("#")) {
                firstChildIdx = idx
                break
            }
        }
        if (firstChildIdx == -1) return null

        val rowIndent = getIndent(lines[firstChildIdx])
        val headerSplit = splitTableRow(lines[firstChildIdx], expectedCols = -1)
        if (headerSplit.first.size < 2) return null

        val numCols = headerSplit.first.size
        val rows = mutableListOf<List<String>>()
        rows.add(headerSplit.first.map { formatTableCellForMarkdown(it) })

        for (idx in (firstChildIdx + 1)..endLine) {
            val raw = lines[idx]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (getIndent(raw) != rowIndent) continue

            val rowSplit = splitTableRow(raw, expectedCols = numCols)
            if (rowSplit.first.size < 2) continue
            val padded = MutableList(numCols) { colIdx ->
                val rawCell = rowSplit.first.getOrNull(colIdx) ?: ""
                formatTableCellForMarkdown(rawCell)
            }
            rows.add(padded)
        }

        if (rows.isEmpty()) return null

        val colWidths = IntArray(numCols) { c ->
            max(3, rows.maxOf { r -> r[c].length })
        }

        val sb = StringBuilder()
        // Header row
        sb.append("|")
        for (c in 0 until numCols) {
            sb.append(" ").append(rows[0][c].padEnd(colWidths[c], ' ')).append(" |")
        }
        sb.append("\n|")
        // Separator row
        for (c in 0 until numCols) {
            sb.append(" ").append("-".repeat(colWidths[c])).append(" |")
        }
        sb.append("\n")
        // Data rows
        for (r in 1 until rows.size) {
            sb.append("|")
            for (c in 0 until numCols) {
                sb.append(" ").append(rows[r][c].padEnd(colWidths[c], ' ')).append(" |")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun formatTableCellForMarkdown(cell: String): String {
        val unquoted = if (cell.length >= 2 && cell.startsWith("\"") && cell.endsWith("\"")) {
            cell.substring(1, cell.length - 1).replace("\\\"", "\"")
        } else {
            cell
        }
        val rendered = renderInlineMarkdown(unquoted)
        // Escape top-level pipes inside Markdown table cells
        return rendered.replace("|", "\\|")
    }

    /**
     * Converts inline `.rn` Stream tags (`b[bold]`, `i[italic]`, `s[strike]`, `a(/url)[text]`,
     * `img(/url)[alt]`, `span{class x}[text]`) into Markdown / HTML while leaving backtick code spans untouched.
     */
    fun renderInlineMarkdown(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            // Preserve backtick code spans `` `...` ``
            if (text[i] == '`') {
                val endTick = text.indexOf('`', i + 1)
                if (endTick > i) {
                    sb.append(text.substring(i, endTick + 1))
                    i = endTick + 1
                    continue
                }
            }

            // Detect inline tag: `tag{props}(slots)[content]`
            if (text[i].isLetter() && (i == 0 || !text[i - 1].isLetterOrDigit())) {
                val tagStart = i
                var p = i
                while (p < text.length && (text[p].isLetterOrDigit() || text[p] == '_' || text[p] == '-')) {
                    p++
                }
                val tagName = text.substring(tagStart, p)
                var propsStr: String? = null
                var slotsStr: String? = null

                if (p < text.length && text[p] == '{') {
                    val closeBrace = findMatchingBracket(text, p, '{', '}')
                    if (closeBrace > p) {
                        propsStr = text.substring(p + 1, closeBrace)
                        p = closeBrace + 1
                    }
                }
                if (p < text.length && text[p] == '(') {
                    val closeParen = findMatchingBracket(text, p, '(', ')')
                    if (closeParen > p) {
                        slotsStr = text.substring(p + 1, closeParen)
                        p = closeParen + 1
                    }
                }
                if (p < text.length && text[p] == '[') {
                    val closeBracket = findMatchingBracket(text, p, '[', ']')
                    if (closeBracket >= p) {
                        val innerContent = renderInlineMarkdown(text.substring(p + 1, closeBracket))
                        when (tagName) {
                            "b", "strong", "ж", "жирний", "жирный" -> sb.append("**$innerContent**")
                            "i", "em", "к", "курсив" -> sb.append("*$innerContent*")
                            "s", "del", "з", "зачерк" -> sb.append("~~$innerContent~~")
                            "code", "код" -> sb.append("`$innerContent`")
                            "a", "сс", "посилання", "ссылка" -> {
                                val url = slotsStr?.trim()
                                    ?: extractPropValue(propsStr, "href")
                                    ?: "#"
                                sb.append("[$innerContent]($url)")
                            }
                            "img", "рис", " фото", "картинка" -> {
                                val url = slotsStr?.trim()
                                    ?: extractPropValue(propsStr, "src")
                                    ?: ""
                                sb.append("![$innerContent]($url)")
                            }
                            else -> {
                                val htmlAttrs = formatHtmlProps(propsStr)
                                sb.append("<$tagName$htmlAttrs>$innerContent</$tagName>")
                            }
                        }
                        i = closeBracket + 1
                        continue
                    }
                }
            }

            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    private fun extractPropValue(propsStr: String?, key: String): String? {
        if (propsStr.isNullOrBlank()) return null
        for (pair in propsStr.split(',')) {
            val parts = pair.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[0] == key) {
                return parts[1].removeSurrounding("\"")
            }
        }
        return null
    }

    private fun formatHtmlProps(propsStr: String?): String {
        if (propsStr.isNullOrBlank()) return ""
        val attrs = mutableListOf<String>()
        for (pair in propsStr.split(',')) {
            val parts = pair.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                val k = parts[0]
                val v = parts[1].removeSurrounding("\"")
                attrs.add("$k=\"$v\"")
            }
        }
        return if (attrs.isEmpty()) "" else " " + attrs.joinToString(" ")
    }

    private fun findMatchingBracket(text: String, openIdx: Int, openCh: Char, closeCh: Char): Int {
        var depth = 0
        var inQuotes = false
        var i = openIdx
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (ch == '"') inQuotes = false
                i++
                continue
            }
            if (ch == '"') inQuotes = true
            else if (ch == openCh) depth++
            else if (ch == closeCh) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    fun detectCol0RawOpenerRank(trimmedRight: String): String? {
        if (!trimmedRight.endsWith('"')) return null
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
        return trimmedRight.dropLast(1).takeLastWhile { it.isDigit() }
    }

    fun findBlockEnd(lines: List<String>, openLine: Int, parentIndent: Int): Int {
        var lastChild = openLine
        var sub = openLine + 1
        while (sub < lines.size) {
            val subText = lines[sub]
            val trimmedRight = subText.trimEnd('\r', ' ', '\t')

            if (subText.isBlank()) {
                sub++
                continue
            }
            val subIndent = getIndent(subText)
            if (subIndent > parentIndent) {
                lastChild = sub
                // If this child line opens a Column-0 raw string, jump past its Column-0 closing marker!
                val col0Rank = detectCol0RawOpenerRank(trimmedRight)
                if (col0Rank != null) {
                    val closeMarker = "\"$col0Rank"
                    sub++
                    while (sub < lines.size) {
                        lastChild = sub
                        if (lines[sub].trimEnd('\r', ' ', '\t') == closeMarker) break
                        sub++
                    }
                }
            } else {
                break
            }
            sub++
        }
        return lastChild
    }

    fun getIndent(line: String): Int =
        line.takeWhile { it == ' ' || it == '\t' }.length

    fun extractSchemaNameFromPathOrRef(rawRef: String): String {
        val clean = rawRef.trim().removeSurrounding("\"")
        if (clean.contains('#')) {
            return clean.substringAfterLast('#')
        }
        val afterSlash = clean.substringAfterLast('/')
        return afterSlash.removeSuffix(".rn").removeSuffix(".rl")
    }

    fun isPascalCase(s: String): Boolean =
        s.isNotEmpty() && s[0] in 'A'..'Z' && s.any { it in 'a'..'z' } &&
                s.all { it.isLetterOrDigit() || it == '_' }

    fun isTypedNumericLiteral(s: String): Boolean {
        if (s.length < 2 || s[0] !in 'A'..'Z') return false
        val prefix = s.takeWhile { it.isLetter() }
        if (!isPascalCase(prefix)) return false
        val rest = s.substring(prefix.length)
        if (rest.isEmpty()) return false
        if (prefix == "Ver" || prefix == "Semver") {
            return rest[0].isDigit() && rest.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        }
        return rest.toDoubleOrNull() != null
    }

    fun isEmailLiteral(s: String): Boolean {
        val atIdx = s.indexOf('@')
        if (atIdx <= 0 || atIdx >= s.length - 3) return false
        val domain = s.substring(atIdx + 1)
        return domain.contains('.') && !s.any { it.isWhitespace() || it == '"' || it == '\'' }
    }

    fun isUrlLiteral(s: String): Boolean {
        return (s.startsWith("https://") || s.startsWith("http://")) && s.length > 8
    }

    /**
     * Lightweight, zero-dependency converters from simple formats (JSON, YAML, CSV, .env/.properties, Markdown) into `.rn`.
     */
    fun convertSimpleFormatToRn(source: String, extension: String): String {
        return when (extension.lowercase()) {
            "json", "json5" -> convertJsonToRn(source)
            "yaml", "yml" -> convertYamlToRn(source)
            "csv", "tsv" -> convertCsvToRn(source, if (extension.lowercase() == "tsv") '\t' else ',')
            "env", "properties", "ini" -> convertEnvOrPropertiesToRn(source)
            "md", "markdown" -> convertMarkdownToRn(source)
            else -> convertJsonToRn(source)
        }
    }

    fun convertJsonToRn(jsonText: String): String {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder()
        var indent = 0
        val lines = trimmed.lines()

        // Simple recursive-descent tokenizing converter for JSON objects & arrays -> 1-space `.rn`
        var i = 0
        fun skipWs() {
            while (i < trimmed.length && trimmed[i].isWhitespace()) i++
        }
        fun readJsonString(): String {
            if (i >= trimmed.length || trimmed[i] != '"') return ""
            i++
            val out = StringBuilder()
            while (i < trimmed.length) {
                val c = trimmed[i]
                if (c == '\\' && i + 1 < trimmed.length) {
                    out.append(c).append(trimmed[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') {
                    i++
                    break
                }
                out.append(c)
                i++
            }
            return out.toString()
        }
        fun formatRnKey(k: String): String {
            return if (k.isNotEmpty() && k.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) k else "\"$k\""
        }
        fun formatRnScalar(raw: String): String {
            val s = raw.trim()
            if (s == "null") return "None"
            if (s == "true" || s == "false") return s
            if (s.toDoubleOrNull() != null) return s
            val unq = s.removeSurrounding("\"")
            if (unq.isEmpty()) return "\"\""
            if (isEmailLiteral(unq) || isUrlLiteral(unq)) return unq
            if (unq.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '/' || it == '.' } &&
                unq !in setOf("true", "false", "None")
            ) {
                return unq
            }
            return "\"$unq\""
        }

        fun parseValue(currentIndent: Int, keyPrefix: String?) {
            skipWs()
            if (i >= trimmed.length) return
            val pad = " ".repeat(currentIndent)
            when (trimmed[i]) {
                '{' -> {
                    i++
                    skipWs()
                    if (i < trimmed.length && trimmed[i] == '}') {
                        i++
                        if (keyPrefix != null) sb.append("$pad$keyPrefix {}\n")
                        return
                    }
                    val childIndent = when (keyPrefix) {
                        "{" -> {
                            sb.append("$pad{\n")
                            currentIndent + 1
                        }
                        null -> currentIndent
                        else -> {
                            sb.append("$pad$keyPrefix {\n")
                            currentIndent + 1
                        }
                    }
                    while (i < trimmed.length) {
                        skipWs()
                        if (i < trimmed.length && trimmed[i] == '}') {
                            i++
                            break
                        }
                        val k = if (trimmed[i] == '"') readJsonString() else {
                            val start = i
                            while (i < trimmed.length && trimmed[i] != ':' && !trimmed[i].isWhitespace()) i++
                            trimmed.substring(start, i)
                        }
                        skipWs()
                        if (i < trimmed.length && trimmed[i] == ':') i++
                        parseValue(childIndent, formatRnKey(k))
                        skipWs()
                        if (i < trimmed.length && trimmed[i] == ',') i++
                    }
                }
                '[' -> {
                    i++
                    skipWs()
                    if (i < trimmed.length && trimmed[i] == ']') {
                        i++
                        if (keyPrefix != null) sb.append("$pad$keyPrefix ()\n")
                        return
                    }
                    // Check if array is a simple 1D scalar list that fits on one line
                    val savedPos = i
                    val scalarItems = mutableListOf<String>()
                    var isSimpleScalarList = true
                    while (i < trimmed.length) {
                        skipWs()
                        if (i < trimmed.length && trimmed[i] == ']') {
                            i++
                            break
                        }
                        if (trimmed[i] == '{' || trimmed[i] == '[') {
                            isSimpleScalarList = false
                            break
                        }
                        if (trimmed[i] == '"') {
                            val str = readJsonString()
                            scalarItems.add(formatRnScalar("\"$str\""))
                        } else {
                            val start = i
                            while (i < trimmed.length && trimmed[i] != ',' && trimmed[i] != ']' && !trimmed[i].isWhitespace()) i++
                            scalarItems.add(formatRnScalar(trimmed.substring(start, i)))
                        }
                        skipWs()
                        if (i < trimmed.length && trimmed[i] == ',') i++
                    }
                    if (isSimpleScalarList) {
                        val prefix = if (keyPrefix != null) "$pad$keyPrefix " else pad
                        if (scalarItems.joinToString(", ").length <= 60) {
                            sb.append("$prefix(${scalarItems.joinToString(", ")}\n")
                        } else {
                            sb.append("$prefix(\n")
                            for (item in scalarItems) {
                                sb.append("$pad $item\n")
                            }
                        }
                    } else {
                        i = savedPos
                        val prefix = if (keyPrefix != null) "$pad$keyPrefix (\n" else "$pad(\n"
                        sb.append(prefix)
                        while (i < trimmed.length) {
                            skipWs()
                            if (i < trimmed.length && trimmed[i] == ']') {
                                i++
                                break
                            }
                            if (trimmed[i] == '{') {
                                parseValue(currentIndent + 1, "{")
                            } else if (trimmed[i] == '[') {
                                parseValue(currentIndent + 1, "(")
                            } else {
                                parseValue(currentIndent + 1, null)
                            }
                            skipWs()
                            if (i < trimmed.length && trimmed[i] == ',') i++
                        }
                    }
                }
                '"' -> {
                    val s = readJsonString()
                    val formatted = formatRnScalar("\"$s\"")
                    if (keyPrefix != null) sb.append("$pad$keyPrefix $formatted\n") else sb.append("$pad$formatted\n")
                }
                else -> {
                    val start = i
                    while (i < trimmed.length && trimmed[i] != ',' && trimmed[i] != '}' && trimmed[i] != ']' && !trimmed[i].isWhitespace()) {
                        i++
                    }
                    val formatted = formatRnScalar(trimmed.substring(start, i))
                    if (keyPrefix != null) sb.append("$pad$keyPrefix $formatted\n") else sb.append("$pad$formatted\n")
                }
            }
        }

        parseValue(0, null)
        return sb.toString().trimEnd() + "\n"
    }

    fun convertYamlToRn(yamlText: String): String {
        val sb = StringBuilder()
        for (rawLine in yamlText.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                sb.append(rawLine).append("\n")
                continue
            }
            val indent = (rawLine.takeWhile { it == ' ' }.length / 2).coerceAtLeast(0)
            val pad = " ".repeat(indent)
            if (trimmed.startsWith("- ")) {
                sb.append("$pad${trimmed.removePrefix("- ").trim()}\n")
            } else if (trimmed.endsWith(":")) {
                val key = trimmed.dropLast(1).trim()
                sb.append("$pad$key {\n")
            } else if (trimmed.contains(": ")) {
                val key = trimmed.substringBefore(": ").trim()
                val rawVal = trimmed.substringAfter(": ").trim()
                val rnVal = if (rawVal == "null" || rawVal == "~") "None" else rawVal
                sb.append("$pad$key $rnVal\n")
            } else {
                sb.append("$pad$trimmed\n")
            }
        }
        return RnTableFormatter.formatDocument(sb.toString()).trimEnd() + "\n"
    }

    fun convertCsvToRn(csvText: String, separator: Char = ','): String {
        val sb = StringBuilder("data (\n")
        for (rawLine in csvText.lines()) {
            val t = rawLine.trim()
            if (t.isEmpty()) continue
            val normalized = if (separator == ',') t else t.replace(separator, ',')
            sb.append(" ").append(normalized).append("\n")
        }
        return RnTableFormatter.formatDocument(sb.toString()).trimEnd() + "\n"
    }

    fun convertEnvOrPropertiesToRn(envText: String): String {
        val sb = StringBuilder("env {\n")
        for (rawLine in envText.lines()) {
            val t = rawLine.trim()
            if (t.isEmpty() || t.startsWith("#")) {
                if (t.startsWith("#")) sb.append(" ").append(if (t.startsWith("# ")) t else "# ${t.removePrefix("#")}").append("\n")
                continue
            }
            val eqIdx = t.indexOf('=')
            if (eqIdx > 0) {
                val k = t.substring(0, eqIdx).trim()
                val v = t.substring(eqIdx + 1).trim()
                sb.append(" $k ${if (v.isEmpty()) "None" else v}\n")
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    fun convertMarkdownToRn(mdText: String): String {
        val sb = StringBuilder("meta {\n type Md\n\n")
        val lines = mdText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("# ") -> sb.append("title ${t.removePrefix("# ").trim()}\n")
                t.startsWith("## ") -> sb.append("part ${t.removePrefix("## ").trim()}\n")
                t.startsWith("### ") -> sb.append("section ${t.removePrefix("### ").trim()}\n")
                t.startsWith("#### ") -> sb.append("topic ${t.removePrefix("#### ").trim()}\n")
                t.startsWith("```") -> {
                    val lang = t.removePrefix("```").trim().replaceFirstChar { it.uppercase() }
                    val opener = if (lang.isEmpty()) "code \"" else "code $lang\""
                    sb.append("$opener\n")
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        sb.append(lines[i]).append("\n")
                        i++
                    }
                    sb.append("\"\n")
                }
                else -> sb.append(line).append("\n")
            }
            i++
        }
        return sb.toString().trimEnd() + "\n"
    }
}