package org.razomy.rn.jetbrains.intellij.ide.plugin

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
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

        val syntaxDiagnostics = RnGrammarParser.validateDocument(text, isMd)
        val schemaDiagnostics = RnGrammarParser.validateSchemasInDocument(text)

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

                    // 1.Md Document & HTML Tag Completion (active in *.md.rn or inside Md[/Html[ streams)
                    addMdAndHtmlCompletions(result, linePrefix)

                    // 2. Schema Type & Type Constructor Completion
                    addSchemaAndTypeCompletions(result)

                    // 3. Relative & Project File Path Completion
                    if (vFile != null) {
                        addProjectFileCompletions(project, vFile, linePrefix, result)
                    }
                }
            }
        )
    }

    private fun addMdAndHtmlCompletions(result: CompletionResultSet, linePrefix: String) {
        val trimmedPrefix = linePrefix.trimStart()
        val isLineStart = !trimmedPrefix.contains(' ')

        val blockElements = listOf(
            Triple("title", "title Heading 1", "Md H1 Document Title (#)"),
            Triple("part", "part Heading 2", "Md H2 Document Part (##)"),
            Triple("section", "section Heading 3", "Md H3 Section (###)"),
            Triple("topic", "topic Heading 4", "Md H4 Topic (####)"),
            Triple("ul [", "ul [\n ", "Md Unordered Bullet List"),
            Triple("ol [", "ol [\n ", "Md Ordered Numbered List"),
            Triple("quote [", "quote [\n ", "Md Blockquote (>)"),
            Triple("note [", "note [\n ", "Md Callout Note (> [!NOTE])"),
            Triple("warn [", "warn [\n ", "Md Callout Warning (> [!WARNING])"),
            Triple("code Ts\"", "code Ts\"\n\n\"", "Md Fenced TypeScript Code Block"),
            Triple("code Rn\"", "code Rn\"\n\n\"", "Md Fenced Razomy Notation Block"),
            Triple("table (", "table (\n col1, col2\n ", "Md 2D Relational Table"),
            Triple("example {", "example {\n input Rn\"\n\"\n output Md\"\n\"", "Specification Example Block")
        )

        if (isLineStart) {
            for ((lookup, insertText, typeDesc) in blockElements) {
                result.addElement(
                    LookupElementBuilder.create(insertText)
                        .withPresentableText(lookup)
                        .withTypeText(typeDesc, true)
                        .withIcon(AllIcons.Nodes.Tag)
                        .bold()
                )
            }
        }

        val inlineElements = listOf(
            Triple("b[...]", "b[]", "Bold inline text (**bold**)"),
            Triple("i[...]", "i[]", "Italic inline text (*italic*)"),
            Triple("s[...]", "s[]", "Strikethrough inline text (~~strike~~)"),
            Triple("a(/url)[text]", "a(/path)[text]", "Hyperlink or relative file link"),
            Triple("img(/url)[alt]", "img(./image.png)[alt]", "Embedded image"),
            Triple("span{class x}[text]", "span{class highlight}[text]", "Inline HTML span with Props"),
            Triple("div{class card} [", "div{class card} [\n ", "HTML div container block"),
            Triple("article{id post} [", "article{id post} [\n ", "HTML article block"),
            Triple("p [", "p [ ", "HTML paragraph stream")
        )

        for ((presentable, insertText, desc) in inlineElements) {
            result.addElement(
                LookupElementBuilder.create(insertText)
                    .withPresentableText(presentable)
                    .withTypeText(desc, true)
                    .withIcon(AllIcons.Nodes.Tag)
            )
        }
    }

    private fun addSchemaAndTypeCompletions(result: CompletionResultSet) {
        val schemaTypes = listOf(
            Triple("Str(min, max){label \"...\"}", "Str(1, 100){label \"\"}", "Schema String Archetype"),
            Triple("Int(min, max){default 0}", "Int(0, 100){default 0}", "Schema Integer Archetype"),
            Triple("Float(min, max)", "Float(0.0, 1.0)", "Schema Float Archetype"),
            Triple("Bool{default true}", "Bool{default true}", "Schema Boolean Archetype"),
            Triple("Uuid{'pk}", "Uuid{'pk}", "Schema UUID Primary Key"),
            Triple("Email{label \"Email\"}", "Email{label \"Email\"}", "Schema Email Archetype"),
            Triple("Date\"YYYY-MM-DD\"", "Date\"2026-03-30\"", "Constructed Date Literal"),
            Triple("Enum('a, 'b){default 'a}", "Enum('admin, 'member){default 'member}", "Schema Enum Archetype"),
            Triple("List(Str){min_items 1}", "List(Str){min_items 1}", "Schema Typed Array Archetype"),
            Triple("None", "None", "Unified None / null scalar"),
            Triple("Sql(", "Sql(\n select, from, where\n ", "Attached SQL Type Constructor"),
            Triple("Html[", "Html[\n ", "Attached HTML Stream Type Constructor"),
            Triple("Scss{", "Scss{\n ", "Attached SCSS Form Type Constructor"),
            Triple("Md[", "Md[\n ", "Attached Markdown Stream Type Constructor"),
            Triple("Json\"", "Json\"\n\n\"", "Column-0 JSON Raw Block")
        )

        for ((presentable, insert, desc) in schemaTypes) {
            result.addElement(
                LookupElementBuilder.create(insert)
                    .withPresentableText(presentable)
                    .withTypeText(desc, true)
                    .withIcon(AllIcons.Nodes.Type)
            )
        }
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
            }
        }
    }
}

/**
 * Clickable File Path & Hyperlink Reference Contributor for `.rn` files:
 * Enables `Ctrl+Click` / `Cmd+Click` navigation and path intelligence on:
 * - Links `a(./SPECIFICATION.md.rn)[Spec]` or `a(/docs/guide.rn)[Guide]`
 * - Quoted paths `"./schema.rn"`, `"../config.json.rn"`
 * - Unquoted relative paths `./foo.rn`, `../bar.md`, or project-relative paths
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

                    // Match path-like tokens:
                    // 1. Inside `a(path)[...]` or `img(path)[...]`
                    // 2. Relative paths `./...` or `../...`
                    // 3. Explicit `.rn`, `.rl`, `.md`, `.json`, `.sql`, `.scss`, `.ts`, `.kt` file references
                    val pathRegex = Regex(
                        """(?:\.{1,2}/[A-Za-z0-9_.\-/]+)|(?:[A-Za-z0-9_.\-/]+\.(?:rn|rl|md|json|yaml|yml|sql|scss|html|ts|js|kt|rs|py|proto|tf|csv))"""
                    )

                    for (match in pathRegex.findAll(text)) {
                        val rawPath = match.value
                        val range = TextRange(match.range.first, match.range.last + 1)
                        refs.add(RnFilePathReference(element, range, rawPath))
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
    private val rawPath: String
) : PsiReferenceBase<PsiElement>(element, rangeInElement, true) {

    override fun resolve(): PsiElement? {
        val project = element.project
        val containingFile = element.containingFile?.originalFile?.virtualFile
        val psiManager = PsiManager.getInstance(project)

        // 1. Resolve relative to current file's directory
        if (containingFile?.parent != null) {
            val relResolved = containingFile.parent.findFileByRelativePath(rawPath.removePrefix("./"))
            if (relResolved != null && !relResolved.isDirectory) {
                return psiManager.findFile(relResolved)
            }
        }

        // 2. Resolve relative to project root basePath
        project.basePath?.let { base ->
            val ioFile = File(base, rawPath.removePrefix("/").removePrefix("./"))
            if (ioFile.exists() && ioFile.isFile) {
                LocalFileSystem.getInstance().findFileByIoFile(ioFile)?.let { vf ->
                    return psiManager.findFile(vf)
                }
            }
        }

        // 3. Search project content by matching filename or suffix path
        val targetName = rawPath.substringAfterLast('/')
        var foundFile: VirtualFile? = null
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (!vf.isDirectory && vf.name.equals(targetName, ignoreCase = true)) {
                if (vf.path.endsWith(rawPath.removePrefix("./").removePrefix("../"))) {
                    foundFile = vf
                    return@iterateContent false
                }
                if (foundFile == null) {
                    foundFile = vf
                }
            }
            true
        }

        return foundFile?.let { psiManager.findFile(it) }
    }

    override fun getVariants(): Array<Any> = emptyArray()
}