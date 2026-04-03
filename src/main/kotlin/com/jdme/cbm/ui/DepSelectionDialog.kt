package com.jdme.cbm.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.jdme.cbm.core.LocalBuildScanner
import com.jdme.cbm.model.DepSubstitution
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Dialog for adding a custom composite build component.
 * User fills in Group:Artifact (auto-filled from dependency line, editable) and selects one module to replace.
 * After clicking "Add", use [getSelectedDeps] to get the selected dependency.
 */
class DepSelectionDialog(
    private val project: Project,
    private val moduleName: String,
    private val scanResult: LocalBuildScanner.ScanResult,
    private val projectService: com.jdme.cbm.core.CbmProjectService,
    suggestedDep: String? = null
) : DialogWrapper(project) {

    private val resolvedDep: String? = suggestedDep ?: scanResult.groupId?.let { "$it:$moduleName" }

    private val depField = JTextField(resolvedDep ?: "", 22)
    private val radioGroup = ButtonGroup()
    private val radioButtons = mutableListOf<Pair<JRadioButton, LocalBuildScanner.ProjectEntry>>()

    init {
        title = "添加复合构建组件（$moduleName）"
        setOKButtonText("添加")
        setCancelButtonText("取消")
        isResizable = true
        init()
        setupListeners()
        // 初始化：根据当前 dep 更新 checkbox 状态，然后更新按钮状态
        updateModuleRadioButtons()
        updateOKButtonState()
        pack()
        setSize(520, 300)
    }

    private fun setupListeners() {
        // 监听输入框内容变化：更新 checkbox 状态和按钮状态
        depField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                updateModuleRadioButtons()
                updateOKButtonState()
            }
            override fun removeUpdate(e: DocumentEvent?) {
                updateModuleRadioButtons()
                updateOKButtonState()
            }
            override fun changedUpdate(e: DocumentEvent?) {
                updateModuleRadioButtons()
                updateOKButtonState()
            }
        })
    }

    private fun updateOKButtonState() {
        okAction.isEnabled = canAdd()
    }

    /** 根据当前 dep 更新 module checkbox 的启用/禁用状态 */
    private fun updateModuleRadioButtons() {
        val cleanedDep = cleanDep(depField.text.trim())
        val existingDepSubs = getExistingDepSubstitutions()

        // 找出当前 dep 已经添加的 modules 对应的 project 路径
        val addedProjects = if (cleanedDep.isNotBlank()) {
            existingDepSubs
                .filter { it.dep == cleanedDep }
                .map { it.project }
                .toSet()
        } else {
            emptySet()
        }

        // 更新每个 radio button：已添加的置灰+选中，未添加的启用+不选
        radioButtons.forEach { (rb, entry) ->
            val projectPath = ":${entry.gradlePath}"
            val isAdded = addedProjects.contains(projectPath)
            if (isAdded) {
                rb.isEnabled = false
                rb.isSelected = true
            } else {
                rb.isEnabled = true
                rb.isSelected = false
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))

        // Group:Artifact 输入区
        val groupPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { fill = GridBagConstraints.HORIZONTAL }
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; gbc.insets = JBUI.insetsRight(8)
        groupPanel.add(JBLabel("Group:Artifact："), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.insets = JBUI.emptyInsets()
        groupPanel.add(depField, gbc)
        if (resolvedDep == null) {
            gbc.gridx = 1; gbc.gridy = 1; gbc.insets = JBUI.insetsTop(2)
            groupPanel.add(JBLabel("<html><small style='color:gray'>未检测到 Group:Artifact，请手动填写</small></html>"), gbc)
        }
        panel.add(groupPanel, BorderLayout.NORTH)

        // 模块列表（单选）
        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder("选择要替换的模块")
            minimumSize = Dimension(440, 100)
            maximumSize = Dimension(440, 500)
        }
        if (scanResult.allProjects.isEmpty()) {
            listPanel.add(JBLabel("未检测到可用模块"))
        } else {
            scanResult.allProjects.forEach { entry ->
                val rb = JRadioButton(entry.name).apply {
                    alignmentX = JRadioButton.LEFT_ALIGNMENT
                    isSelected = false
                }
                radioGroup.add(rb)
                radioButtons.add(rb to entry)
                // 添加选中变化监听器
                rb.addItemListener { updateOKButtonState() }
                listPanel.add(rb)
            }
        }
        val scroll = JBScrollPane(listPanel, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply {
            preferredSize = Dimension(460, (scanResult.allProjects.size * 28 + 40).coerceIn(150, 400))
        }
        panel.add(scroll, BorderLayout.CENTER)

        panel.add(
            JBLabel("<html><small>依赖替换规则：Group ID + 模块名 → 本地 project 路径</small></html>"),
            BorderLayout.SOUTH
        )
        panel.preferredSize = Dimension(500, -1)
        return panel
    }

    /** 清理 dep：仅保留 group:artifact，移除版本号 */
    private fun cleanDep(dep: String): String {
        if (dep.isBlank()) return ""
        val parts = dep.split(":")
        return if (parts.size >= 2) "${parts[0]}:${parts[1]}" else dep
    }

    /** 获取已存在的所有依赖替换规则 */
    private fun getExistingDepSubstitutions(): List<DepSubstitution> {
        return projectService.modules.flatMap { it.customDeps }
    }

    /** 获取当前选中且启用的模块对应的 project 路径集合（Gradle 格式，带冒号前缀） */
    private fun getSelectedEnabledProjects(): Set<String> {
        return radioButtons.filter { (rb, _) -> rb.isEnabled && rb.isSelected }
            .map { (_, entry) -> ":${entry.gradlePath}" }
            .toSet()
    }

    /** 检查是否存在重复的 dep + project 组合 */
    private fun hasDuplicateCombination(): Boolean {
        val cleanedDep = cleanDep(depField.text.trim())
        if (cleanedDep.isBlank()) return false

        val selectedProjects = getSelectedEnabledProjects()
        val existingDeps = getExistingDepSubstitutions()

        // 检查是否存在相同的 dep 和相同的 project 组合
        return existingDeps.any { existing ->
            existing.dep == cleanedDep && selectedProjects.contains(existing.project)
        }
    }

    /** 检查添加按钮是否可用：dep 填写 AND 至少勾选一个启用的模块 AND 没有重复的 dep+project 组合 */
    private fun canAdd(): Boolean {
        val dep = depField.text.trim()
        // 只检查启用且被勾选的 checkbox（已添加的灰色 checkbox 不计入）
        val hasSelectedEnabledModule = radioButtons.any { (cb, _) -> cb.isEnabled && cb.isSelected }
        return dep.isNotBlank() && hasSelectedEnabledModule && !hasDuplicateCombination()
    }

    override fun isOKActionEnabled(): Boolean = canAdd()

    override fun doOKAction() {
        if (!canAdd()) {
            val errors = mutableListOf<String>()
            val dep = depField.text.trim()
            if (dep.isBlank()) {
                errors.add("请填写 Group:Artifact")
            } else if (hasDuplicateCombination()) {
                val cleanedDep = cleanDep(dep)
                val selectedProjects = getSelectedEnabledProjects()
                val duplicateProjects = getExistingDepSubstitutions()
                    .filter { it.dep == cleanedDep && selectedProjects.contains(it.project) }
                    .map { it.project }
                errors.add("Group:Artifact '$cleanedDep' 与模块 $duplicateProjects 的组合已存在")
            }
            if (!radioButtons.any { (cb, _) -> cb.isEnabled && cb.isSelected }) errors.add("请至少选择一个模块")
            Messages.showErrorDialog(
                project,
                errors.joinToString("\n"),
                "无法添加"
            )
            return
        }
        // 验证通过，关闭 Dialog
        close(OK_EXIT_CODE)
    }

    fun getSelectedDeps(): List<DepSubstitution> {
        val dep = depField.text.trim()
        val cleanedDep = cleanDep(dep)

        // 只返回启用且勾选的 radioButton（禁用的已经存在，不需要再添加）
        return radioButtons.filter { (rb, _) -> rb.isEnabled && rb.isSelected }.map { (_, entry) ->
            DepSubstitution(
                dep = if (cleanedDep.isNotBlank()) cleanedDep else entry.name,
                project = ":${entry.gradlePath}"  // 添加冒号前缀
            )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = depField
}