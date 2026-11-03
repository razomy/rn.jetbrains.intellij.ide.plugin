package org.razomy.rn.jetbrains.intellij.ide.plugin

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ProcessingContext
import java.awt.BorderLayout
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Persistent IDE Settings for Razomy Notation (`.rn`):
 * - Automatic export on file save (`*.md.rn` -> `.md`, `*.json.rn` -> `.json`, or custom target format)
 * - Schema & Syntax validation toggle
 */
@Service(Service.Level.APP)
@State(
    name = "org.razomy.rn.settings.RnSettingsState",
    storages = [Storage("RazomyNotationSettings.xml")]
)
class RnSettingsState : PersistentStateComponent<RnSettingsState.StateData> {
    data class StateData(
        var autoExportOnSave: Boolean = false,
        var autoExportCompoundExtensionsOnly: Boolean = true,
        var defaultExportFormatId: String = "md",
        var openExportedFileInEditor: Boolean = false,
        var enableSchemaValidation: Boolean = true
    )

    private var stateData = StateData()

    override fun getState(): StateData = stateData

    override fun loadState(state: StateData) {
        this.stateData = state
    }

    companion object {
        fun getInstance(): RnSettingsState =
            ApplicationManager.getApplication().getService(RnSettingsState::class.java)
    }
}

class RnSettingsConfigurable : Configurable {
    private var autoExportCheckBox: JBCheckBox? = null
    private var compoundOnlyCheckBox: JBCheckBox? = null
    private var openInEditorCheckBox: JBCheckBox? = null
    private var schemaValidationCheckBox: JBCheckBox? = null
    private var defaultFormatCombo: JComboBox<ExportTargetFormat>? = null

    override fun getDisplayName(): String = "Razomy Notation (.rn)"

    override fun createComponent(): JComponent {
        val settings = RnSettingsState.getInstance().state
        autoExportCheckBox = JBCheckBox(
            "Automatically export .rn file on save (Ctrl+S / File Save)",
            settings.autoExportOnSave
        )
        compoundOnlyCheckBox = JBCheckBox(
            "Auto-detect target format from compound extension (*.md.rn -> .md, *.json.rn -> .json, *.sql.rn -> .sql, *.scss.rn -> .scss)",
            settings.autoExportCompoundExtensionsOnly
        )
        openInEditorCheckBox = JBCheckBox(
            "Open exported file in editor after manual export",
            settings.openExportedFileInEditor
        )
        schemaValidationCheckBox = JBCheckBox(
            "Enable real-time syntax & JSON Schema archetype validation",
            settings.enableSchemaValidation
        )
        defaultFormatCombo = JComboBox(ExportTargetFormat.entries.toTypedArray()).apply {
            selectedItem = ExportTargetFormat.entries.find { it.id == settings.defaultExportFormatId }
                ?: ExportTargetFormat.MARKDOWN
        }

        val formGrid = JBPanel<JBPanel<*>>(GridLayout(0, 1, 6, 6)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(autoExportCheckBox!!)
            add(compoundOnlyCheckBox!!)
            val comboRow = JBPanel<JBPanel<*>>(BorderLayout(8, 0)).apply {
                add(JBLabel("Fallback auto-export format:"), BorderLayout.WEST)
                add(defaultFormatCombo!!, BorderLayout.CENTER)
            }
            add(comboRow)
            add(openInEditorCheckBox!!)
            add(schemaValidationCheckBox!!)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(formGrid, BorderLayout.NORTH)
        }
    }

    override fun isModified(): Boolean {
        val s = RnSettingsState.getInstance().state
        val selectedFmt = (defaultFormatCombo?.selectedItem as? ExportTargetFormat)?.id ?: "md"
        return autoExportCheckBox?.isSelected != s.autoExportOnSave ||
                compoundOnlyCheckBox?.isSelected != s.autoExportCompoundExtensionsOnly ||
                openInEditorCheckBox?.isSelected != s.openExportedFileInEditor ||
                schemaValidationCheckBox?.isSelected != s.enableSchemaValidation ||
                selectedFmt != s.defaultExportFormatId
    }

    override fun apply() {
        val s = RnSettingsState.getInstance().state
        s.autoExportOnSave = autoExportCheckBox?.isSelected ?: false
        s.autoExportCompoundExtensionsOnly = compoundOnlyCheckBox?.isSelected ?: true
        s.openExportedFileInEditor = openInEditorCheckBox?.isSelected ?: false
        s.enableSchemaValidation = schemaValidationCheckBox?.isSelected ?: true
        s.defaultExportFormatId = (defaultFormatCombo?.selectedItem as? ExportTargetFormat)?.id ?: "md"
    }

    override fun reset() {
        val s = RnSettingsState.getInstance().state
        autoExportCheckBox?.isSelected = s.autoExportOnSave
        compoundOnlyCheckBox?.isSelected = s.autoExportCompoundExtensionsOnly
        openInEditorCheckBox?.isSelected = s.openExportedFileInEditor
        schemaValidationCheckBox?.isSelected = s.enableSchemaValidation
        defaultFormatCombo?.selectedItem =
            ExportTargetFormat.entries.find { it.id == s.defaultExportFormatId } ?: ExportTargetFormat.MARKDOWN
    }
}

/**
 * Listener that automatically exports `.rn` files when saved if enabled in Settings or ToolWindow.
 */
class RnAutoExportSaveListener(private val project: Project) : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val settings = RnSettingsState.getInstance().state
        if (!settings.autoExportOnSave) return

        val vf = FileDocumentManager.getInstance().getFile(document) ?: return
        if (vf.extension != "rn" && vf.extension != "rl") return
        if (vf.name.endsWith(".formatted.rn")) return

        val targetFormat = inferTargetFormatForFile(vf, settings) ?: return
        RnExporterService.exportFileToDisk(
            project = project,
            sourceFile = vf,
            sourceText = document.text,
            targetFormat = targetFormat,
            openInEditor = false,
            silentNotification = true
        )
    }

    companion object {
        fun inferTargetFormatForFile(vf: VirtualFile, settings: RnSettingsState.StateData): ExportTargetFormat? {
            val name = vf.name.lowercase()
            return when {
                name.endsWith(".md.rn") -> ExportTargetFormat.MARKDOWN
                name.endsWith(".json.rn") -> ExportTargetFormat.JSON
                name.endsWith(".yaml.rn") || name.endsWith(".yml.rn") -> ExportTargetFormat.YAML
                name.endsWith(".html.rn") -> ExportTargetFormat.HTML
                name.endsWith(".sql.rn") -> ExportTargetFormat.SQL
                name.endsWith(".scss.rn") || name.endsWith(".css.rn") -> ExportTargetFormat.SCSS
                name.endsWith(".tf.rn") -> ExportTargetFormat.TERRAFORM
                name.endsWith(".proto.rn") -> ExportTargetFormat.PROTO
                name.endsWith(".mmd.rn") -> ExportTargetFormat.MERMAID
                name.endsWith(".csv.rn") -> ExportTargetFormat.CSV
                !settings.autoExportCompoundExtensionsOnly ->
                    ExportTargetFormat.entries.find { it.id == settings.defaultExportFormatId }
                else -> null
            }
        }
    }
}

/**
 * Real-time Annotator for Razomy Notation (`.rn`):
 * Highlights syntax violations (unclosed Col-0 raw strings, forbidden standalone closing brackets,
 * unbalanced inline brackets) and JSON Schema archetype validation errors (`UserForm{ ... }`).
 */
class RnAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is RnFile) return
        val settings = RnSettingsState.getInstance().state
        if (!settings.enableSchemaValidation) return

        val text = element.text
        val isMd = element.name.endsWith(".md.rn", ignoreCase = true)

        // Collect schemas from sibling .rn/.rl files and any relative path referenced in `meta { schema ... }`
        val externalSchemas = mutableMapOf<String, Map<String, RnGrammarParser.SchemaFieldSpec>>()
        val baseDir = element.virtualFile?.parent
        baseDir?.children?.forEach { sibling ->
            if (!sibling.isDirectory && sibling != element.virtualFile &&
                (sibling.extension == "rn" || sibling.extension == "rl")
            ) {
                runCatching {
                    val siblingText = String(sibling.contentsToByteArray(), Charsets.UTF_8)
                    externalSchemas.putAll(RnGrammarParser.extractSchemasFromText(siblingText))
                }
            }
        }
        // Also resolve explicit relative paths in `schema ./path/to/models.rl/UserForm`
        val schemaPathRegex = Regex("""(?m)^\s*schema\s+([^\s#]+(?:#[A-Za-z0-9_]+)?)""")
        for (match in schemaPathRegex.findAll(text)) {
            val rawRef = match.groupValues[1].trim().removeSurrounding("\"")
            val splitTarget = splitFileAndSymbolFromSchemaRef(rawRef)
            val targetVf = resolveVirtualFileByRelPath(element.project, element.virtualFile, splitTarget.first)
            if (targetVf != null && !targetVf.isDirectory) {
                runCatching {
                    val targetText = String(targetVf.contentsToByteArray(), Charsets.UTF_8)
                    externalSchemas.putAll(RnGrammarParser.extractSchemasFromText(targetText))
                }
            }
        }

        val syntaxDiagnostics = RnGrammarParser.validateDocument(text, isMd)
        val schemaDiagnostics = RnGrammarParser.validateSchemasInDocument(text, externalSchemas)

        for (diag in (syntaxDiagnostics + schemaDiagnostics)) {
            val start = diag.startOffset.coerceIn(0, text.length)
            val end = diag.endOffset.coerceIn(start, text.length)
            if (end <= start) continue
            val severity = if (diag.severity == RnGrammarParser.RnDiagnostic.Severity.ERROR) {
                HighlightSeverity.ERROR
            } else {
                HighlightSeverity.WARNING
            }
            holder.newAnnotation(severity, diag.message)
                .range(TextRange(start, end))
                .create()
        }
    }
}

/**
 * Context-aware IntelliSense Completion Contributor for `.rn` and `.md.rn`:
 * 1. Standard `Md` Document & Inline Tags (`title`, `part`, `section`, `topic`, `ul [`, `ol [`, `quote [`, `note [`, `warn [`, `code Ts"`, `b[bold]`, `i[italic]`, `a(/url)[text]`, `img(/url)[alt]`, `span{class x}[text]`)
 * 2. Standard Type Constructors & Schema Archetypes (`Str`, `Int`, `Float`, `Bool`, `Uuid`, `Email`, `Date`, `Enum`, `List`, `Sql(`, `Html[`, `Scss{`, `Json"`, `Ts"`, `Md"`)
 * 3. Relative & Project File Path Completion (`./`, `../`, `/`, or inside `a(...)` / `img(...)` / `include` / `ref`)
 */
class RnCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(RnLanguage),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    val file = parameters.originalFile
                    val vFile = file.virtualFile
                    val project = file.project
                    val offset = parameters.offset
                    val docText = file.text
                    val lineStart = docText.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
                    val linePrefix = docText.substring(lineStart, offset)

                    // 1. Md Document, Multilingual (RU/UK) & HTML Tag Completion
                    addMdAndHtmlCompletions(result, linePrefix)

                    // 2. Smart Context-Aware Schema Types, Constraints & User-Defined Schemas
                    addSmartSchemaAndTypeCompletions(docText, offset, linePrefix, vFile, result)

                    // 3. Relative & Project File Path Completion
                    if (vFile != null) {
                        addProjectFileCompletions(project, vFile, linePrefix, result)
                    }
                }
            }
        )
    }

    /**
     * Helper that inserts `templateWithCaret` where `|` marks the exact cursor position after insertion,
     * so IntelliSense creates only the clean key or wrapper (`b[|]`, `Str{|}`, `min |`) without annoying dummy values!
     */
    private fun createCaretLookup(
        lookup: String,
        presentable: String,
        templateWithCaret: String,
        typeDesc: String,
        icon: javax.swing.Icon,
        tailText: String? = null,
        bold: Boolean = false
    ): LookupElementBuilder {
        val caretIdx = templateWithCaret.indexOf('|')
        val cleanInsert = if (caretIdx >= 0) templateWithCaret.replace("|", "") else templateWithCaret
        var builder = LookupElementBuilder.create(cleanInsert)
            .withLookupString(lookup)
            .withPresentableText(presentable)
            .withTypeText(typeDesc, true)
            .withIcon(icon)
        if (tailText != null) {
            builder = builder.withTailText(tailText, true)
        }
        if (bold) {
            builder = builder.bold()
        }
        if (caretIdx >= 0) {
            builder = builder.withInsertHandler { ctx, _ ->
                val targetOffset = (ctx.startOffset + caretIdx).coerceIn(0, ctx.document.textLength)
                ctx.editor.caretModel.moveToOffset(targetOffset)
            }
        }
        return builder
    }

    private fun addMdAndHtmlCompletions(result: CompletionResultSet, linePrefix: String) {
        val trimmedPrefix = linePrefix.trimStart()
        val isLineStart = !trimmedPrefix.contains(' ')

        val blockElements = listOf(
            Triple("title", "title |", "Md H1 Document Title (#)"),
            Triple("part", "part |", "Md H2 Document Part (##)"),
            Triple("section", "section |", "Md H3 Section (###)"),
            Triple("topic", "topic |", "Md H4 Topic (####)"),
            Triple("ul [", "ul [\n |", "Md Unordered Bullet List"),
            Triple("ol [", "ol [\n |", "Md Ordered Numbered List"),
            Triple("quote [", "quote [\n |", "Md Blockquote (>)"),
            Triple("note [", "note [\n |", "Md Callout Note (> [!NOTE])"),
            Triple("warn [", "warn [\n |", "Md Callout Warning (> [!WARNING])"),
            Triple("code Ts\"", "code Ts\"\n|\n\"", "Md Fenced TypeScript Code Block"),
            Triple("code Rn\"", "code Rn\"\n|\n\"", "Md Fenced Razomy Notation Block"),
            Triple("table (", "table (\n |", "Md 2D Relational Table"),
            Triple("meta {", "meta {\n schema |", "Document Metadata & Schema Header"),
            Triple("example {", "example {\n input Rn\"\n|\n\"\n output Md\"\n\"", "Specification Example Block"),
            // Russian & Ukrainian Keyboard Lifehacks (auto-inserts brackets `[` `{` `(` `"` without layout switch!)
            Triple("заголовок", "заголовок |", "RU/UK H1 Заголовок документа (#)"),
            Triple("часть", "часть |", "RU H2 Часть документа (##)"),
            Triple("частина", "частина |", "UK H2 Частина документа (##)"),
            Triple("раздел", "раздел |", "RU H3 Раздел (###)"),
            Triple("розділ", "розділ |", "UK H3 Розділ (###)"),
            Triple("тема", "тема |", "RU/UK H4 Тема (####)"),
            Triple("список [", "список [\n |", "RU/UK Список без переключения раскладки"),
            Triple("нум [", "нум [\n |", "RU/UK Нумерованный список"),
            Triple("цитата [", "цитата [\n |", "RU/UK Цитата (>)"),
            Triple("заметка [", "заметка [\n |", "RU Заметка (> [!NOTE])"),
            Triple("примітка [", "примітка [\n |", "UK Примітка (> [!NOTE])"),
            Triple("внимание [", "внимание [\n |", "RU Внимание (> [!WARNING])"),
            Triple("увага [", "увага [\n |", "UK Увага (> [!WARNING])"),
            Triple("таблица (", "таблица (\n |", "RU/UK 2D Таблица"),
            Triple("код Rn\"", "код Rn\"\n|\n\"", "RU/UK Блок кода без переключения раскладки")
        )

        if (isLineStart) {
            for ((lookup, template, typeDesc) in blockElements) {
                result.addElement(
                    createCaretLookup(
                        lookup = lookup,
                        presentable = lookup,
                        templateWithCaret = template,
                        typeDesc = typeDesc,
                        icon = AllIcons.Nodes.Tag,
                        bold = true
                    )
                )
            }
        }

        val inlineElements = listOf(
            Triple("b[...]", "b[|]", "Bold inline text (**bold**)"),
            Triple("i[...]", "i[|]", "Italic inline text (*italic*)"),
            Triple("s[...]", "s[|]", "Strikethrough inline text (~~strike~~)"),
            Triple("a(/url)[text]", "a(|)[]", "Hyperlink or relative file link"),
            Triple("img(/url)[alt]", "img(|)[]", "Embedded image"),
            Triple("span{class x}[text]", "span{class |}[]", "Inline HTML span with Props"),
            Triple("ж[...]", "ж[|]", "RU/UK Жирный текст (**жирный**) без смены раскладки"),
            Triple("к[...]", "к[|]", "RU/UK Курсив (*курсив*) без смены раскладки"),
            Triple("сс(/url)[текст]", "сс(|)[]", "RU/UK Ссылка без смены раскладки"),
            Triple("div{class} [", "div{class |} [\n ", "HTML div container block"),
            Triple("article{id} [", "article{id |} [\n ", "HTML article block"),
            Triple("p [", "p [ |", "HTML paragraph stream")
        )

        for ((presentable, template, desc) in inlineElements) {
            result.addElement(
                createCaretLookup(
                    lookup = presentable.substringBefore("[").substringBefore("{").substringBefore("("),
                    presentable = presentable,
                    templateWithCaret = template,
                    typeDesc = desc,
                    icon = AllIcons.Nodes.Tag
                )
            )
        }
    }

    private fun addSmartSchemaAndTypeCompletions(
        docText: String,
        offset: Int,
        linePrefix: String,
        currentFile: VirtualFile?,
        result: CompletionResultSet
    ) {
        // 1. Collect all user-defined schemas from current file + sibling .rn/.rl files
        val allSchemas = mutableMapOf<String, Map<String, RnGrammarParser.SchemaFieldSpec>>()
        currentFile?.parent?.children?.forEach { sibling ->
            if (!sibling.isDirectory && (sibling.extension == "rn" || sibling.extension == "rl")) {
                runCatching {
                    val text = String(sibling.contentsToByteArray(), Charsets.UTF_8)
                    allSchemas.putAll(RnGrammarParser.extractSchemasFromText(text))
                }
            }
        }
        allSchemas.putAll(RnGrammarParser.extractSchemasFromText(docText))

        // 2. If cursor is inside a typed schema instance `valid_user UserForm{`, suggest the schema's fields (with wrapper if object/list/enum)!
        val enclosingSchemaName = findEnclosingTypedBlockSchema(docText, offset, allSchemas)
        if (enclosingSchemaName != null) {
            val fields = allSchemas[enclosingSchemaName] ?: emptyMap()
            for ((fieldName, spec) in fields) {
                val reqBadge = if (spec.optional || spec.hasDefault) "Optional ${spec.baseType}" else "Required ${spec.baseType}"
                val fieldTemplate = when (spec.baseType) {
                    "List", "Tuple", "Set" -> "$fieldName (|"
                    "Object", "Map" -> "$fieldName {|"
                    "Enum" -> "$fieldName '|"
                    else -> "$fieldName |"
                }
                result.addElement(
                    createCaretLookup(
                        lookup = fieldName,
                        presentable = fieldName,
                        templateWithCaret = fieldTemplate,
                        typeDesc = "$enclosingSchemaName • $reqBadge",
                        icon = AllIcons.Nodes.Field,
                        tailText = "  (${spec.baseType})",
                        bold = true
                    )
                )
            }
        }

        // 3. Suggest user-defined Schema Constructors (`UserForm{`, etc.) — clean block wrapper or required keys without preset values
        for ((schemaName, fields) in allSchemas) {
            result.addElement(
                createCaretLookup(
                    lookup = schemaName,
                    presentable = "$schemaName{",
                    templateWithCaret = "$schemaName{\n |",
                    typeDesc = "Defined Schema Archetype",
                    icon = AllIcons.Nodes.Class,
                    tailText = "  (${fields.keys.joinToString(", ")})",
                    bold = true
                )
            )
        }

        // 4. Inside `{...}` schema constraint properties, suggest clean constraint keys/wrappers without hardcoded numbers
        val openBraces = linePrefix.count { it == '{' } - linePrefix.count { it == '}' }
        if (openBraces > 0) {
            val constraintProps = listOf(
                Triple("min", "min |", "Minimum value or string length"),
                Triple("max", "max |", "Maximum value or string length"),
                Triple("default", "default |", "Default field value"),
                Triple("label", "label \"|\"", "UI Form field label"),
                Triple("placeholder", "placeholder \"|\"", "UI Form input placeholder"),
                Triple("pattern", "pattern Regex\"|\"", "Regular expression constraint"),
                Triple("examples", "examples (|)", "Example values tuple"),
                Triple("min_items", "min_items |", "Minimum items in List"),
                Triple("max_items", "max_items |", "Maximum items in List"),
                Triple("unique", "unique true|", "Require unique items in List"),
                Triple("widget", "widget '|", "UI widget ('radio, 'textarea, 'select)"),
                Triple("format", "format '|", "String semantic format ('uri, 'uuid)")
            )
            for ((lookup, template, desc) in constraintProps) {
                result.addElement(
                    createCaretLookup(
                        lookup = lookup,
                        presentable = lookup,
                        templateWithCaret = template,
                        typeDesc = desc,
                        icon = AllIcons.Nodes.Property
                    )
                )
            }
        }

        // 5. Standard Scalar, Currency, Form & Pentad Type Constructors — clean type or empty wrapper `{|}` / `(|)` / `"|"`
        val schemaTypes = listOf(
            Triple("Str", "Str|", "Schema String Type"),
            Triple("Str{...}", "Str{|}", "Schema String with Constraints"),
            Triple("Str & Optional", "Str & Optional|", "Optional String Field (& Optional)"),
            Triple("Int", "Int|", "Schema Integer Type"),
            Triple("Int{...}", "Int{|}", "Schema Integer with Constraints"),
            Triple("Float", "Float|", "Schema Float Type"),
            Triple("Float{...}", "Float{|}", "Schema Float with Constraints"),
            Triple("Dec", "Dec|", "Schema Decimal Type"),
            Triple("Usd", "Usd|", "Typed USD Currency"),
            Triple("Usd{...}", "Usd{|}", "Schema USD Currency with Constraints"),
            Triple("Eur", "Eur|", "Typed EUR Currency"),
            Triple("Uah", "Uah|", "Typed UAH Currency"),
            Triple("Pct", "Pct|", "Typed Percentage"),
            Triple("Ver", "Ver|", "Typed Semantic Version"),
            Triple("Bool", "Bool|", "Schema Boolean Type"),
            Triple("Bool{...}", "Bool{|}", "Schema Boolean with Constraints"),
            Triple("Uuid", "Uuid|", "Schema UUID Type"),
            Triple("Uuid{'pk}", "Uuid{'pk|}", "Schema UUID Primary Key"),
            Triple("Email", "Email|", "Schema Email Type"),
            Triple("Email{...}", "Email{|}", "Schema Email with Constraints"),
            Triple("Url", "Url|", "Schema URL / URI Type"),
            Triple("Url & Optional", "Url & Optional|", "Optional URL Field"),
            Triple("Regex\"...\"", "Regex\"|\"", "Constructed Regular Expression Literal"),
            Triple("Date\"...\"", "Date\"|\"", "Constructed ISO Date Literal"),
            Triple("DateTime\"...\"", "DateTime\"|\"", "Constructed ISO DateTime Literal"),
            Triple("Enum(...)", "Enum('|)", "Schema Enum Archetype"),
            Triple("List(...)", "List(|)", "Schema Typed List Archetype"),
            Triple("Map(...)", "Map(|)", "Schema Key-Value Map Archetype"),
            Triple("& Optional", "& Optional|", "Algebraic Optional Field Mixin"),
            Triple("None", "None|", "Unified None / null scalar"),
            Triple("Sql(", "Sql(\n |", "Attached SQL Type Constructor"),
            Triple("Html[", "Html[\n |", "Attached HTML Stream Type Constructor"),
            Triple("Scss{", "Scss{\n |", "Attached SCSS Form Type Constructor"),
            Triple("Md[", "Md[\n |", "Attached Markdown Stream Type Constructor"),
            Triple("Json\"", "Json\"\n|\n\"", "Column-0 JSON Raw Block")
        )

        for ((presentable, template, desc) in schemaTypes) {
            result.addElement(
                createCaretLookup(
                    lookup = presentable.takeWhile { it.isLetterOrDigit() || it == '&' || it == ' ' }.trim(),
                    presentable = presentable,
                    templateWithCaret = template,
                    typeDesc = desc,
                    icon = AllIcons.Nodes.Type
                )
            )
        }
    }

    private fun findEnclosingTypedBlockSchema(
        docText: String,
        offset: Int,
        allSchemas: Map<String, Map<String, RnGrammarParser.SchemaFieldSpec>>
    ): String? {
        val before = docText.substring(0, offset.coerceIn(0, docText.length))
        val lines = before.split("\n")
        if (lines.isEmpty()) return null
        val currentIndent = RnGrammarParser.getIndent(lines.last())
        for (i in (lines.size - 2) downTo 0) {
            val raw = lines[i]
            if (raw.isBlank()) continue
            val ind = RnGrammarParser.getIndent(raw)
            if (ind < currentIndent) {
                val clean = RnGrammarParser.stripInlineComment(raw).trim()
                if (clean.endsWith("{")) {
                    val parts = clean.dropLast(1).trim().split(Regex("\\s+"))
                    val rawLast = parts.lastOrNull() ?: return null
                    val resolved = RnGrammarParser.extractSchemaNameFromPathOrRef(rawLast)
                    if (resolved in allSchemas) return resolved
                }
                break
            }
        }
        return null
    }

    private fun addProjectFileCompletions(
        project: Project,
        currentFile: VirtualFile,
        linePrefix: String,
        result: CompletionResultSet
    ) {
        val currentDir = currentFile.parent ?: return

        // Add sibling and relative files from current directory (up to 2 levels)
        val visited = mutableSetOf<String>()
        collectRelativeFiles(currentDir, currentDir, "", 0, result, visited)

        // Also index project files by name for quick context completion
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (!vf.isDirectory && vf != currentFile && visited.size < 150) {
                val relPath = VfsUtilCore.findRelativePath(currentFile, vf, '/')
                if (relPath != null && visited.add(relPath)) {
                    val normalizedRel = if (relPath.startsWith(".")) relPath else "./$relPath"
                    result.addElement(
                        LookupElementBuilder.create(normalizedRel)
                            .withLookupString(vf.name)
                            .withPresentableText(vf.name)
                            .withTailText("  ($normalizedRel)", true)
                            .withTypeText("Project File", true)
                            .withIcon(vf.fileType.icon ?: AllIcons.FileTypes.Any_type)
                    )
                }
            }
            true
        }
    }

    private fun collectRelativeFiles(
        baseDir: VirtualFile,
        dir: VirtualFile,
        prefix: String,
        depth: Int,
        result: CompletionResultSet,
        visited: MutableSet<String>
    ) {
        if (depth > 2) return
        for (child in dir.children) {
            val rel = if (prefix.isEmpty()) "./${child.name}" else "$prefix/${child.name}"
            if (child.isDirectory) {
                if (!child.name.startsWith(".") && child.name != "build" && child.name != "target" && child.name != "node_modules") {
                    collectRelativeFiles(baseDir, child, rel, depth + 1, result, visited)
                }
            } else if (visited.add(rel)) {
                result.addElement(
                    LookupElementBuilder.create(rel)
                        .withLookupString(child.name)
                        .withPresentableText(rel)
                        .withTailText("  (${child.name})", true)
                        .withTypeText("Relative Path", true)
                        .withIcon(child.fileType.icon ?: AllIcons.FileTypes.Any_type)
                )
                // If this sibling is a .rn or .rl file containing schemas, also suggest `./file.rl/SchemaName`!
                if (child.extension == "rn" || child.extension == "rl") {
                    runCatching {
                        val childText = String(child.contentsToByteArray(), Charsets.UTF_8)
                        val childSchemas = RnGrammarParser.extractSchemasFromText(childText)
                        for (schemaName in childSchemas.keys) {
                            val schemaPath = "$rel/$schemaName"
                            if (visited.add(schemaPath)) {
                                result.addElement(
                                    LookupElementBuilder.create(schemaPath)
                                        .withLookupString(schemaName)
                                        .withLookupString("${child.name}/$schemaName")
                                        .withPresentableText(schemaPath)
                                        .withTypeText("Schema in ${child.name}", true)
                                        .withIcon(AllIcons.Nodes.Class)
                                        .bold()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Splits a schema path reference like `./models.rl/UserForm` or `./models.rl#UserForm`
 * into `(filePath, optionalSymbolName)`.
 */
internal fun splitFileAndSymbolFromSchemaRef(rawPath: String): Pair<String, String?> {
    if (rawPath.contains('#')) {
        val filePart = rawPath.substringBefore('#')
        val symPart = rawPath.substringAfter('#').takeIf { it.isNotEmpty() }
        return Pair(filePart, symPart)
    }
    // Check for `./models.rl/UserForm` or `path/to/schema.rn/MyType` where a segment before the last `/` is a file with an extension
    val extMatch = Regex("""^(.+\.(?:rn|rl))(?:/([A-Za-z_][A-Za-z0-9_]*))$""").find(rawPath)
    if (extMatch != null) {
        return Pair(extMatch.groupValues[1], extMatch.groupValues[2])
    }
    return Pair(rawPath, null)
}

internal fun resolveVirtualFileByRelPath(
    project: Project,
    containingFile: VirtualFile?,
    filePath: String
): VirtualFile? {
    if (filePath.isEmpty()) return containingFile

    // 1. Resolve relative to current file's directory
    if (containingFile?.parent != null) {
        val relResolved = containingFile.parent.findFileByRelativePath(filePath.removePrefix("./"))
        if (relResolved != null && !relResolved.isDirectory) {
            return relResolved
        }
    }

    // 2. Resolve relative to project root basePath
    project.basePath?.let { base ->
        val ioFile = File(base, filePath.removePrefix("/").removePrefix("./"))
        if (ioFile.exists() && ioFile.isFile) {
            LocalFileSystem.getInstance().findFileByIoFile(ioFile)?.let { return it }
        }
    }

    // 3. Search project content by matching filename or suffix path
    val targetName = filePath.substringAfterLast('/')
    var foundFile: VirtualFile? = null
    ProjectFileIndex.getInstance(project).iterateContent { vf ->
        if (!vf.isDirectory && vf.name.equals(targetName, ignoreCase = true)) {
            if (vf.path.endsWith(filePath.removePrefix("./").removePrefix("../"))) {
                foundFile = vf
                return@iterateContent false
            }
            if (foundFile == null) {
                foundFile = vf
            }
        }
        true
    }
    return foundFile
}

internal fun findDefinitionPsiElementInFile(psiFile: PsiFile, symbolName: String): PsiElement? {
    val text = psiFile.text
    // Match `UserForm {` or `UserForm & Mixin {` at start of line
    val defRegex = Regex("""(?m)^[ \t]*(\Q$symbolName\E)\b[^\n]*\{""")
    val match = defRegex.find(text) ?: return null
    val groupRange = match.groups[1]?.range ?: match.range
    return psiFile.findElementAt(groupRange.first) ?: psiFile
}

/**
 * Clickable File Path, Schema Path (`./models.rl/UserForm` and `./models.rl#UserForm`),
 * and Local/Imported Schema Type (`UserForm{`) Reference Contributor for `.rn` / `.rl` files:
 * - Ctrl+Click on `./models.rl` in `./models.rl/UserForm` opens `models.rl`
 * - Ctrl+Click on `UserForm` in `./models.rl/UserForm` (or `./models.rl#UserForm` or `UserForm{`) jumps directly to `UserForm {` definition!
 */
class RnFileReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withLanguage(RnLanguage),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    if (element !is RnFile) return PsiReference.EMPTY_ARRAY
                    val text = element.text
                    val refs = mutableListOf<PsiReference>()

                    // 1. Match file paths & compound schema paths:
                    //    `./models.rl/UserForm`, `./models.rl#UserForm`, `./SPECIFICATION.md.rn`, etc.
                    val pathRegex = Regex(
                        """(?:\.{1,2}/[A-Za-z0-9_.\-/]+(?:#[A-Za-z0-9_]+)?)|(?:[A-Za-z0-9_.\-/]+\.(?:rn|rl|md|json|yaml|yml|sql|scss|html|ts|js|kt|rs|py|proto|tf|csv)(?:[/#][A-Za-z0-9_]+)?)"""
                    )

                    for (match in pathRegex.findAll(text)) {
                        val rawToken = match.value
                        val tokenStart = match.range.first
                        val (filePart, symbolPart) = splitFileAndSymbolFromSchemaRef(rawToken)

                        if (symbolPart != null && filePart.isNotEmpty()) {
                            // Add a reference on the file portion (`./models.rl`) -> resolves to the file
                            val fileRange = TextRange(tokenStart, tokenStart + filePart.length)
                            refs.add(RnFilePathReference(element, fileRange, filePart, targetSymbol = null))

                            // Add a second reference on the symbol portion (`UserForm`) -> resolves to the `UserForm {` definition inside `./models.rl`!
                            val symStart = tokenStart + filePart.length + 1
                            val symRange = TextRange(symStart, tokenStart + rawToken.length)
                            refs.add(RnFilePathReference(element, symRange, filePart, targetSymbol = symbolPart))
                        } else {
                            val range = TextRange(tokenStart, match.range.last + 1)
                            refs.add(RnFilePathReference(element, range, rawToken, targetSymbol = null))
                        }
                    }

                    // 2. Match PascalCase schema usages `UserForm{` or `valid_user UserForm{` for Ctrl+Click to schema definition
                    val typeUsageRegex = Regex("""\b([A-Z][a-zA-Z0-9_]*)\{""")
                    for (match in typeUsageRegex.findAll(text)) {
                        val typeName = match.groupValues[1]
                        if (typeName in RnGrammarParser.BUILTIN_SCALAR_AND_SCHEMA_TYPES) continue
                        val groupRange = match.groups[1]?.range ?: continue
                        val range = TextRange(groupRange.first, groupRange.last + 1)
                        refs.add(RnSchemaSymbolReference(element, range, typeName))
                    }

                    return refs.toTypedArray()
                }
            }
        )
    }
}

class RnFilePathReference(
    element: PsiElement,
    rangeInElement: TextRange,
    private val filePath: String,
    private val targetSymbol: String? = null
) : PsiReferenceBase<PsiElement>(element, rangeInElement, true) {

    override fun resolve(): PsiElement? {
        val project = element.project
        val containingFile = element.containingFile?.originalFile?.virtualFile
        val psiManager = PsiManager.getInstance(project)

        val vf = resolveVirtualFileByRelPath(project, containingFile, filePath) ?: return null
        val psiFile = psiManager.findFile(vf) ?: return null

        if (targetSymbol != null) {
            return findDefinitionPsiElementInFile(psiFile, targetSymbol) ?: psiFile
        }
        return psiFile
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

class RnSchemaSymbolReference(
    element: PsiElement,
    rangeInElement: TextRange,
    private val schemaName: String
) : PsiReferenceBase<PsiElement>(element, rangeInElement, true) {

    override fun resolve(): PsiElement? {
        val currentPsiFile = element.containingFile ?: return null
        val project = element.project
        val psiManager = PsiManager.getInstance(project)

        // 1. Check in current file first
        findDefinitionPsiElementInFile(currentPsiFile, schemaName)?.let { return it }

        // 2. Check in any file referenced by `meta { schema ./models.rl/UserForm }`
        val text = currentPsiFile.text
        val schemaPathRegex = Regex("""(?m)^\s*schema\s+([^\s#]+(?:#[A-Za-z0-9_]+)?)""")
        for (match in schemaPathRegex.findAll(text)) {
            val rawRef = match.groupValues[1].trim().removeSurrounding("\"")
            val (filePart, _) = splitFileAndSymbolFromSchemaRef(rawRef)
            val vf = resolveVirtualFileByRelPath(project, currentPsiFile.originalFile.virtualFile, filePart)
            if (vf != null) {
                val targetPsi = psiManager.findFile(vf)
                if (targetPsi != null) {
                    findDefinitionPsiElementInFile(targetPsi, schemaName)?.let { return it }
                }
            }
        }

        // 3. Check sibling `.rn` / `.rl` files in the same directory
        currentPsiFile.originalFile.virtualFile?.parent?.children?.forEach { sibling ->
            if (!sibling.isDirectory && (sibling.extension == "rn" || sibling.extension == "rl")) {
                val siblingPsi = psiManager.findFile(sibling)
                if (siblingPsi != null) {
                    findDefinitionPsiElementInFile(siblingPsi, schemaName)?.let { return it }
                }
            }
        }

        return null
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/**
 * Smart Auto-Indent on Enter for Razomy Notation (`.rn`):
 * - When pressing Enter after a line ending with `(`, `{`, `[`, or block `#`, the next line automatically
 *   starts with `parentIndent + 1` spaces (never at Column 0!).
 * - When pressing Enter on a regular indented line inside a block, preserves `currentIndent` spaces.
 * - Inside a Column-0 raw string (`Type"` ... `"`), leaves indentation at 0 as required by Axiom 3.4.
 */
class RnEnterHandlerDelegate : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffsetRef: Ref<Int>,
        caretAdvanceRef: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?
    ): EnterHandlerDelegate.Result {
        if (file !is RnFile) return EnterHandlerDelegate.Result.Continue

        val document = editor.document
        val caretOffset = caretOffsetRef.get().coerceIn(0, document.textLength)
        val lineNum = document.getLineNumber(caretOffset)
        val lineStart = document.getLineStartOffset(lineNum)
        val textBeforeCaret = document.charsSequence.subSequence(lineStart, caretOffset).toString()

        // Do not auto-indent if the line opens a Column-0 raw string `(Type)?(N)?"`
        val trimmedBefore = textBeforeCaret.trimEnd(' ', '\t', '\r')
        if (RnGrammarParser.detectCol0RawOpenerRank(trimmedBefore) != null) {
            return EnterHandlerDelegate.Result.Continue
        }

        val currentIndent = RnGrammarParser.getIndent(textBeforeCaret)
        val codeBeforeCaret = RnGrammarParser.stripInlineComment(trimmedBefore).trimEnd()

        val opensNewBlock = codeBeforeCaret.endsWith("(") ||
                codeBeforeCaret.endsWith("{") ||
                codeBeforeCaret.endsWith("[") ||
                codeBeforeCaret.trim() == "#"

        val targetIndent = if (opensNewBlock) {
            currentIndent + 1
        } else {
            currentIndent
        }

        if (targetIndent > 0) {
            val indentSpaces = " ".repeat(targetIndent)
            document.insertString(caretOffset, "\n$indentSpaces")
            editor.caretModel.moveToOffset(caretOffset + 1 + targetIndent)
            return EnterHandlerDelegate.Result.Stop
        }

        return EnterHandlerDelegate.Result.Continue
    }
}