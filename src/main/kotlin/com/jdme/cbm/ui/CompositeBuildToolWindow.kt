package com.jdme.cbm.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerAdapter
import com.intellij.ui.content.ContentManagerEvent
import com.jdme.cbm.core.CbmProjectService
import java.io.File
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Tool Window 工厂类。
 *
 * 职责：
 * 1. 检测是否能找到 project-repos.json5
 * 2. 能找到：展示 ModuleListPanel
 * 3. 找不到：展示引导界面，让用户手动指定路径
 */
class CompositeBuildToolWindow : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = CbmProjectService.getInstance(project)
        val contentFactory = ContentFactory.getInstance()

        val content: Content = if (service.configFile.exists()) {
            // 正常情况：展示模块列表面板
            val panel = ModuleListPanel(project)
            contentFactory.createContent(panel, "", false)
        } else {
            // 找不到配置文件：展示引导面板
            val guidePanel = buildGuidePanel(project, toolWindow, service.configFile)
            contentFactory.createContent(guidePanel, "", false)
        }

        toolWindow.contentManager.addContent(content)

        // 监听内容显示事件，面板每次显示时从状态文件刷新勾选状态
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerAdapter() {
            override fun selectionChanged(event: ContentManagerEvent) {
                val selectedContent = event.content
                // 找到对应的 ModuleListPanel 并刷新
                val component = selectedContent.component
                if (component is ModuleListPanel) {
                    service.refreshFromStateFile()
                }
            }
        })
    }

    private fun buildGuidePanel(
        project: Project,
        toolWindow: ToolWindow,
        expectedFile: File
    ): JPanel {
        val panel = JPanel(java.awt.GridBagLayout())
        val gbc = java.awt.GridBagConstraints().apply {
            gridx = 0; gridy = 0
            insets = java.awt.Insets(8, 16, 8, 16)
            anchor = java.awt.GridBagConstraints.CENTER
        }

        panel.add(JLabel("⚠ 未找到配置文件", SwingConstants.CENTER), gbc)

        gbc.gridy++
        val pathLabel = JLabel(
            "<html><center>期望路径：<br/><code>${expectedFile.absolutePath}</code></center></html>",
            SwingConstants.CENTER
        )
        panel.add(pathLabel, gbc)

        gbc.gridy++
        val specifyBtn = javax.swing.JButton("手动指定配置文件路径…")
        specifyBtn.addActionListener {
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = "选择 project-repos.json5"
                fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
                fileFilter = object : javax.swing.filechooser.FileFilter() {
                    override fun accept(f: File) = f.isDirectory || f.name.endsWith(".json5")
                    override fun getDescription() = "JSON5 文件 (*.json5)"
                }
            }
            if (chooser.showOpenDialog(panel) == javax.swing.JFileChooser.APPROVE_OPTION) {
                com.jdme.cbm.core.CbmSettings.getInstance().customConfigPath =
                    chooser.selectedFile.absolutePath
                // 重新创建内容
                toolWindow.contentManager.removeAllContents(true)
                val newPanel = ModuleListPanel(project)
                val content = ContentFactory.getInstance().createContent(newPanel, "", false)
                toolWindow.contentManager.addContent(content)
            }
        }
        panel.add(specifyBtn, gbc)

        return panel
    }

    override fun shouldBeAvailable(project: Project) = true
}
