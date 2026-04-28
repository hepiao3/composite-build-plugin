package com.jdme.cbm.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.jdme.cbm.CbmBundle
import com.jdme.cbm.core.CbmProjectService
import com.jdme.cbm.core.GradleSyncTrigger
import com.jdme.cbm.model.ModuleConfig
import com.jdme.cbm.model.ModuleStatus
import javax.swing.*

/**
 * 右键菜单 Action：弹出对话框，让用户选择模块并批量切换 includeBuild 状态。
 */
class SwitchBuildModeAction : AnAction() {

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
    private val toLocalRadio = JRadioButton(CbmBundle.message("dialog.switch_mode.radio_local"), true)
    private val toMavenRadio = JRadioButton(CbmBundle.message("dialog.switch_mode.radio_maven"))

    init {
        title = CbmBundle.message("dialog.switch_mode.title")
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

        val topLabel = JLabel(CbmBundle.message("dialog.switch_mode.select_label"))
        panel.add(topLabel, java.awt.BorderLayout.NORTH)
        panel.add(JBScrollPane(moduleList), java.awt.BorderLayout.CENTER)

        val modePanel = JPanel(java.awt.GridLayout(2, 1)).apply {
            border = BorderFactory.createTitledBorder(CbmBundle.message("dialog.switch_mode.target_mode_border"))
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
                CbmBundle.message("dialog.switch_mode.confirm_message", selectedNames.size),
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
