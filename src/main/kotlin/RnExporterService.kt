package org.razomy.rn.intellij.plugin

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.TimeUnit

enum class ExportTargetFormat(
    val id: String,
    val label: String,
    val extension: String,
    val description: String
) {
    JSON("json", "Export to JSON", "json", "Canonical JSON (.json)"),
    YAML("yaml", "Export to YAML", "yaml", "YAML 1.2 (.yaml)"),
    MARKDOWN("md", "Export to Markdown", "md", "Markdown Document (.md)"),
    HTML("html", "Export to HTML", "html", "HTML5 DOM (.html)"),
    SQL("sql", "Export to SQL", "sql", "SQL DDL & DML (.sql)"),
    SCSS("scss", "Export to SCSS / CSS", "scss", "Stylesheet (.scss)"),
    TERRAFORM("tf", "Export to Docker & Terraform", "tf", "Infrastructure (.tf)"),
    PROTO("proto", "Export to Protobuf", "proto", "Protocol Buffers v3 (.proto)"),
    MERMAID("mmd", "Export to Mermaid Diagram", "mmd", "Mermaid Flowchart (.mmd)"),
    CSV("csv", "Export to CSV", "csv", "Comma-Separated Values (.csv)"),
    RN_FMT("rn", "Format Canonical .rn", "rn", "1-Space Topological Razomy Notation (.rn)")
}

object RnExporterService {
    /**
     * Converts `.rn` content to the target format using the `rn` CLI binary if available on PATH
     * or in workspace target directories, with a built-in JVM fallback converter.
     */
    fun convertRnContent(
        project: Project?,
        inputFile: VirtualFile?,
        sourceText: String,
        targetFormat: ExportTargetFormat
    ): Result<String> {
        val rnBinary = findRnBinary(project)
        if (rnBinary != null && inputFile != null) {
            try {
                val proc = ProcessBuilder(
                    rnBinary.absolutePath,
                    "convert",
                    inputFile.path,
                    "--to",
                    targetFormat.id
                )
                    .redirectErrorStream(false)
                    .start()

                val completed = proc.waitFor(5, TimeUnit.SECONDS)
                if (completed && proc.exitValue() == 0) {
                    val out = proc.inputStream.bufferedReader().readText()
                    if (out.isNotBlank()) {
                        return Result.success(out)
                    }
                }
            } catch (_: Exception) {
                // Fall back to built-in converter below
            }
        }
        return runCatching { builtinConvert(sourceText, targetFormat) }
    }

    /**
     * Exports the currently active `.rn` file (or provided VirtualFile) to a sibling file
     * with the target format's extension and opens it in the editor.
     */
    fun exportFileToDisk(
        project: Project,
        sourceFile: VirtualFile?,
        sourceText: String,
        targetFormat: ExportTargetFormat,
        onComplete: ((File, String) -> Unit)? = null
    ) {
        val convertedResult = convertRnContent(project, sourceFile, sourceText, targetFormat)
        convertedResult.onSuccess { outputText ->
            ApplicationManager.getApplication().invokeLater {
                try {
                    val baseDir = sourceFile?.parent?.path ?: project.basePath ?: System.getProperty("user.home")
                    val rawName = sourceFile?.nameWithoutExtension ?: "exported"
                    val cleanBaseName = rawName.removeSuffix(".md").removeSuffix(".json")
                        .removeSuffix(".scss").removeSuffix(".sql")
                    val outFileName = if (targetFormat == ExportTargetFormat.RN_FMT) {
                        "${cleanBaseName}.formatted.rn"
                    } else {
                        "${cleanBaseName}.${targetFormat.extension}"
                    }
                    val outFile = File(baseDir, outFileName)
                    outFile.writeText(outputText, Charsets.UTF_8)

                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(outFile)
                    if (vf != null) {
                        FileEditorManager.getInstance(project).openFile(vf, true)
                    }
                    notify(project, "Exported ${outFile.name} (${targetFormat.description})", NotificationType.INFORMATION)
                    onComplete?.invoke(outFile, outputText)
                } catch (e: Exception) {
                    notify(project, "Failed to write exported file: ${e.message}", NotificationType.ERROR)
                }
            }
        }.onFailure { err ->
            notify(project, "Conversion error: ${err.message}", NotificationType.ERROR)
        }
    }

    fun getActiveFileAndText(project: Project): Pair<VirtualFile?, String>? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null) {
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            return Pair(file, editor.document.text)
        }
        val selectedFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (selectedFile != null) {
            val text = String(selectedFile.contentsToByteArray(), Charsets.UTF_8)
            return Pair(selectedFile, text)
        }
        return null
    }

    private fun findRnBinary(project: Project?): File? {
        val candidates = mutableListOf<File>()
        project?.basePath?.let { base ->
            candidates.add(File(base, "target/release/rn"))
            candidates.add(File(base, "target/debug/rn"))
            candidates.add(File(base, "../rust.chunk/src/notation/target/release/rn"))
            candidates.add(File(base, "../rust.chunk/src/notation/target/debug/rn"))
        }
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isNotBlank()) {
                candidates.add(File(dir, "rn"))
                candidates.add(File(dir, "rn.exe"))
            }
        }
        return candidates.firstOrNull { it.exists() && it.canExecute() }
    }

    private fun builtinConvert(source: String, format: ExportTargetFormat): String {
        val lines = source.lines()
        return when (format) {
            ExportTargetFormat.RN_FMT -> source.trimEnd() + "\n"
            ExportTargetFormat.MARKDOWN -> builtinToMarkdown(lines)
            ExportTargetFormat.HTML -> builtinToHtml(lines)
            ExportTargetFormat.YAML -> builtinToYaml(lines)
            ExportTargetFormat.SQL -> builtinToSql(lines)
            ExportTargetFormat.CSV -> builtinToCsv(lines)
            else -> builtinToJson(lines)
        }
    }

    private fun builtinToJson(lines: List<String>): String {
        val entries = mutableListOf<String>()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val clean = t.removeSuffix("{").removeSuffix("(").removeSuffix("[").trim()
            val parts = clean.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                val k = parts[0].trim('"')
                val v = parts[1]
                val jsonVal = when {
                    v == "true" || v == "false" || v == "null" -> v
                    v.toDoubleOrNull() != null -> v
                    v.startsWith("\"") && v.endsWith("\"") -> v
                    else -> "\"${v.replace("\"", "\\\"")}\""
                }
                entries.add("  \"$k\": $jsonVal")
            }
        }
        return "{\n${entries.joinToString(",\n")}\n}\n"
    }

    private fun builtinToYaml(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val indent = "  ".repeat(raw.takeWhile { it == ' ' }.length)
            if (t.endsWith("{")) {
                sb.append("$indent${t.removeSuffix("{").trim()}:\n")
            } else {
                val parts = t.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    sb.append("$indent${parts[0]}: ${parts[1]}\n")
                } else {
                    sb.append("$indent- $t\n")
                }
            }
        }
        return sb.toString()
    }

    private fun builtinToMarkdown(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) {
                sb.append("\n")
                continue
            }
            when {
                t.startsWith("title ") || t.startsWith("part ") ->
                    sb.append("# ${t.substringAfter(" ")}\n")
                t.startsWith("section ") || t.startsWith("subtitle ") ->
                    sb.append("## ${t.substringAfter(" ")}\n")
                t.startsWith("topic ") ->
                    sb.append("### ${t.substringAfter(" ")}\n")
                else -> sb.append("$t\n")
            }
        }
        return sb.toString()
    }

    private fun builtinToHtml(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (t.contains("[") && t.endsWith("]")) {
                val tag = t.substringBefore("[").trim()
                val inner = t.substringAfter("[").removeSuffix("]").trim()
                sb.append("<$tag>$inner</$tag>\n")
            }
        }
        return sb.toString()
    }

    private fun builtinToSql(lines: List<String>): String {
        return "-- Generated SQL from Razomy Notation (.rn)\n" +
            lines.filter { it.isNotBlank() }.joinToString("\n") { "-- $it" } + "\n"
    }

    private fun builtinToCsv(lines: List<String>): String {
        return lines.map { it.trim() }
            .filter { it.contains(",") && !it.endsWith("(") }
            .joinToString("\n") + "\n"
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("RazomyNotation.Notifications")
                .createNotification("Razomy Notation (.rn)", content, type)
                .notify(project)
        } catch (_: Exception) {
            // Ignore if notification group is not initialized in headless test
        }
    }
}