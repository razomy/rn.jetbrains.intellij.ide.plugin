package org.razomy.rn.jetbrains.intellij.ide.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

abstract class BaseRnExportAction(
    private val targetFormat: ExportTargetFormat
) : AnAction(targetFormat.label, targetFormat.description, RnIcons.FILE) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val isRnFile = file != null && (file.extension == "rn" || file.extension == "rl")
        e.presentation.isEnabledAndVisible = e.project != null && (isRnFile || file == null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val active = RnExporterService.getActiveFileAndText(project) ?: return
        RnExporterService.exportFileToDisk(project, active.first, active.second, targetFormat)
    }
}

class ExportRnToJsonAction : BaseRnExportAction(ExportTargetFormat.JSON)
class ExportRnToYamlAction : BaseRnExportAction(ExportTargetFormat.YAML)
class ExportRnToMarkdownAction : BaseRnExportAction(ExportTargetFormat.MARKDOWN)
class ExportRnToHtmlAction : BaseRnExportAction(ExportTargetFormat.HTML)
class ExportRnToSqlAction : BaseRnExportAction(ExportTargetFormat.SQL)
class ExportRnToScssAction : BaseRnExportAction(ExportTargetFormat.SCSS)
class ExportRnToTerraformAction : BaseRnExportAction(ExportTargetFormat.TERRAFORM)
class ExportRnToProtoAction : BaseRnExportAction(ExportTargetFormat.PROTO)
class ExportRnToMermaidAction : BaseRnExportAction(ExportTargetFormat.MERMAID)
class ExportRnToCsvAction : BaseRnExportAction(ExportTargetFormat.CSV)
class FormatRnCanonicalAction : BaseRnExportAction(ExportTargetFormat.RN_FMT)

/**
 * Converts `.json`, `.yaml`, `.yml`, `.csv`, `.tsv`, `.env`, `.properties`, or `.md` files into `.rn`.
 */
class ConvertFileToRnAction : AnAction(
    "Convert File to Razomy Notation (.rn)",
    "Convert JSON, YAML, CSV, .env, .properties, or Markdown file to Razomy Notation (.rn)",
    RnIcons.FILE
) {
    private val supportedExtensions = setOf("json", "json5", "yaml", "yml", "csv", "tsv", "env", "properties", "ini", "md", "markdown")

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val ext = file?.extension?.lowercase()
        e.presentation.isEnabledAndVisible = e.project != null && (ext in supportedExtensions || file?.name == ".env")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (file != null && !file.isDirectory) {
            val text = String(file.contentsToByteArray(), Charsets.UTF_8)
            RnExporterService.importFileToRnDisk(project, file, text, openInEditor = true)
            return
        }
        val active = RnExporterService.getActiveFileAndText(project) ?: return
        RnExporterService.importFileToRnDisk(project, active.first, active.second, openInEditor = true)
    }
}