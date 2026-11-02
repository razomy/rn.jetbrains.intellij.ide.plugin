package org.razomy.rn.jetbrains.intellij.ide.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow(private val project: Project) {
        private val statusLabel = JBLabel(MyMessageBundle.message("toolwindow.rn.status.ready"))
        private val previewArea = JBTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }

        private val content = JBPanel<JBPanel<*>>(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

            val topPanel = JBPanel<JBPanel<*>>(BorderLayout(4, 4))
            topPanel.add(JBLabel(MyMessageBundle.message("toolwindow.rn.header.title")), BorderLayout.NORTH)

            val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 4))
            for (format in ExportTargetFormat.entries) {
                buttonsPanel.add(JButton(format.extension.uppercase()).apply {
                    toolTipText = "${format.label} (${format.description})"
                    addActionListener {
                        val active = RnExporterService.getActiveFileAndText(project)
                        if (active == null) {
                            statusLabel.text = MyMessageBundle.message("toolwindow.rn.status.nofile")
                            return@addActionListener
                        }
                        val converted = RnExporterService.convertRnContent(project, active.first, active.second, format)
                        converted.onSuccess { out ->
                            previewArea.text = out
                            previewArea.caretPosition = 0
                        }
                        RnExporterService.exportFileToDisk(project, active.first, active.second, format) { outFile, _ ->
                            statusLabel.text = MyMessageBundle.message(
                                "toolwindow.rn.status.exported",
                                outFile.name,
                                format.description
                            )
                        }
                    }
                })
            }

            topPanel.add(buttonsPanel, BorderLayout.CENTER)
            topPanel.add(statusLabel, BorderLayout.SOUTH)

            add(topPanel, BorderLayout.NORTH)
            add(JBScrollPane(previewArea), BorderLayout.CENTER)
        }

        fun getContent(): JBPanel<JBPanel<*>> = content
    }
}
