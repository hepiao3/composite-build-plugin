package com.jdme.cbm.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.jdme.cbm.core.CbmProjectService
import com.jdme.cbm.core.GradleSyncTrigger
import com.jdme.cbm.model.ModuleConfig
import com.jdme.cbm.model.ModuleStatus
import javax.swing.*

/**
 * 右键菜单 Action：弹出对话框，让用户选择模块并批量切换 includeBuild 状态。
 */
class SwitchBuildModeAction : AnAction("切换构建模式…") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = CbmProjectService.getInstance(project)

        if (service.modules.isEmpty()) {
            service.loadModules {
                showSwitchDialog(project, service)
            }
        } else {
            showSwitchDialog(project, service)
        }
    }

    private fun showSwitchDialog(project: Project, service: CbmProjectService) {
        SwitchBuildModeDialog(project, service).show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/**
 * 切换构建模式对话框：
 * - 左侧：模块多选列表（当前状态标注）
 * - 右侧：目标状态单选（LOCAL / MAVEN）
 */
private class SwitchBuildModeDialog(
    private val project: Project,
    private val service: CbmProjectService
) : DialogWrapper(project) {

    private val moduleList = JBList(service.modules.map { "${it.status.icon} ${it.name} [${it.status.displayName}]" })
    private val toLocalRadio = JRadioButton("切换为 LOCAL（启用复合构建）", true)
    private val toMavenRadio = JRadioButton("切换为 MAVEN（使用 Maven 依赖）")

    init {
        title = "切换构建模式"
        moduleList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        ButtonGroup().apply {
            add(toLocalRadio)
            add(toMavenRadio)
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(java.awt.BorderLayout(8, 8)).apply {
            preferredSize = java.awt.Dimension(450, 400)
            border = com.intellij.util.ui.JBUI.Borders.empty(8)
        }

        val topLabel = JLabel("选择要切换的模块（可多选）：")
        panel.add(topLabel, java.awt.BorderLayout.NORTH)
        panel.add(JBScrollPane(moduleList), java.awt.BorderLayout.CENTER)

        val modePanel = JPanel(java.awt.GridLayout(2, 1)).apply {
            border = BorderFactory.createTitledBorder("目标模式")
            add(toLocalRadio)
            add(toMavenRadio)
        }
        panel.add(modePanel, java.awt.BorderLayout.SOUTH)

        return panel
    }

    override fun doOKAction() {
        val selectedIndices = moduleList.selectedIndices
        if (selectedIndices.isEmpty()) {
            super.doOKAction()
            return
        }

        val modules = service.modules
        val selectedNames = selectedIndices.map { modules[it].name }
        val targetValue = toLocalRadio.isSelected

        service.setIncludeBuildBatch(selectedNames, targetValue) {
            val choice = JOptionPane.showConfirmDialog(
                null,
                "已切换 ${selectedNames.size} 个模块，是否立即 Sync Gradle？",
                "Sync Gradle",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )
            if (choice == JOptionPane.YES_OPTION) {
                GradleSyncTrigger.sync(project)
            }
        }

        super.doOKAction()
    }
}
