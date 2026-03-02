package com.jdme.cbm.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.jdme.cbm.core.CbmProjectService
import com.jdme.cbm.core.GradleSyncTrigger
import com.jdme.cbm.core.ModuleDownloader
import com.jdme.cbm.model.ModuleConfig
import com.jdme.cbm.model.ModuleStatus
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * 复合构建管理器主面板。
 *
 * 布局：
 * ```
 * ┌─ NORTH ──────────────────────────────────────────────────┐
 * │  [搜索框]                              [⟳ Sync Gradle]   │
 * ├─ CENTER ─────────────────────────────────────────────────┤
 * │  JBTable: 模块名 | 状态 | 分支 | 操作                    │
 * ├─ SOUTH ──────────────────────────────────────────────────┤
 * │  [全部 LOCAL]  [全部 MAVEN]       LOCAL:2 / MAVEN:43     │
 * └──────────────────────────────────────────────────────────┘
 * ```
 */
class ModuleListPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = CbmProjectService.getInstance(project)
    private val tableModel = ModuleTableModel()
    private val table = JBTable(tableModel)
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("")
    private var consoleView: ConsoleView? = null

    // 当前展示的（过滤后的）模块列表
    private var displayedModules: List<ModuleConfig> = emptyList()

    init {
        buildUI()
        registerServiceListener()
        service.loadModules()
    }

    private fun buildUI() {
        // ─── 顶栏 ───────────────────────────────────────────
        val topPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
        }
        searchField.apply {
            textEditor.emptyText.text = "搜索模块名..."
            addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
            })
        }
        val syncBtn = JButton("⟳ Sync Gradle").apply {
            addActionListener {
                GradleSyncTrigger.sync(project)
            }
        }
        topPanel.add(searchField, BorderLayout.CENTER)
        topPanel.add(syncBtn, BorderLayout.EAST)

        // ─── 表格 ───────────────────────────────────────────
        table.apply {
            setShowGrid(false)
            rowHeight = JBUI.scale(28)
            intercellSpacing = Dimension(0, 0)
            columnModel.apply {
                getColumn(COL_CHECKED).apply {
                    maxWidth = JBUI.scale(30)
                    minWidth = JBUI.scale(30)
                    headerValue = ""
                }
                getColumn(COL_NAME).headerValue = "模块名"
                getColumn(COL_STATUS).apply {
                    headerValue = "状态"
                    maxWidth = JBUI.scale(100)
                    minWidth = JBUI.scale(80)
                }
                getColumn(COL_BRANCH).apply {
                    headerValue = "分支"
                    maxWidth = JBUI.scale(180)
                }
                getColumn(COL_ACTION).apply {
                    headerValue = ""
                    maxWidth = JBUI.scale(70)
                    minWidth = JBUI.scale(70)
                    cellRenderer = ButtonRenderer()
                    cellEditor = ButtonEditor(table, ::onDownloadClick)
                }
            }
            // 勾选框列
            columnModel.getColumn(COL_CHECKED).cellRenderer = CheckBoxRenderer()
            columnModel.getColumn(COL_CHECKED).cellEditor = CheckBoxEditor(::onToggleIncludeBuild)
            // 状态列着色
            columnModel.getColumn(COL_STATUS).cellRenderer = StatusRenderer()
        }

        val scrollPane = JBScrollPane(table)

        // ─── 底栏 ───────────────────────────────────────────
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
        }
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        val allLocalBtn = JButton("全部切 LOCAL").apply {
            addActionListener { onAllLocal() }
        }
        val allMavenBtn = JButton("全部切 MAVEN").apply {
            addActionListener { onAllMaven() }
        }
        buttonPanel.add(allLocalBtn)
        buttonPanel.add(allMavenBtn)
        bottomPanel.add(buttonPanel, BorderLayout.WEST)
        bottomPanel.add(statusLabel, BorderLayout.EAST)

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun registerServiceListener() {
        service.addListener { refreshTable() }
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        displayedModules = if (query.isEmpty()) {
            service.modules
        } else {
            service.modules.filter { it.name.lowercase().contains(query) }
        }
        tableModel.setData(displayedModules)
        updateStatusLabel()
    }

    private fun refreshTable() {
        applyFilter()
    }

    private fun updateStatusLabel() {
        val all = service.modules
        val localCount = all.count { it.status == ModuleStatus.LOCAL }
        val mavenCount = all.count { it.status == ModuleStatus.MAVEN }
        val missingCount = all.count { it.status == ModuleStatus.MISSING }
        statusLabel.text = buildString {
            append("LOCAL: $localCount / MAVEN: $mavenCount")
            if (missingCount > 0) append(" / 未下载: $missingCount")
        }
    }

    private fun onToggleIncludeBuild(row: Int, value: Boolean) {
        val module = displayedModules.getOrNull(row) ?: return
        if (value && !module.localDirExists) {
            val choice = Messages.showYesNoCancelDialog(
                project,
                "模块 ${module.name} 的本地目录不存在（${module.localDirName}）。\n" +
                "切换为 LOCAL 模式前需要先下载模块。\n\n" +
                "是否现在下载？",
                "模块未下载",
                "下载并切换",
                "仅切换配置",
                "取消",
                Messages.getWarningIcon()
            )
            when (choice) {
                Messages.YES -> {
                    downloadModule(module)
                    return
                }
                Messages.CANCEL -> return
                // Messages.NO: 继续切换配置（即使目录不存在）
            }
        }
        service.setIncludeBuild(module.name, value)
    }

    private fun onDownloadClick(row: Int) {
        val module = displayedModules.getOrNull(row) ?: return
        downloadModule(module)
    }

    private fun downloadModule(module: ModuleConfig) {
        val console = getOrCreateConsole()
        ModuleDownloader.download(
            module = module,
            project = project,
            projectRoot = java.io.File(project.basePath ?: ""),
            console = console
        ) { success ->
            if (success) {
                // 重新加载以更新 localDirExists 状态
                service.loadModules {
                    if (!module.includeBuild) {
                        service.setIncludeBuild(module.name, true)
                    }
                }
            }
        }
    }

    private fun onAllLocal() {
        val missingModules = service.modules.filter {
            !it.includeBuild && !it.localDirExists
        }
        val missingNames = missingModules.map { it.name }

        val allNames = service.modules.filter { !it.includeBuild }.map { it.name }
        if (allNames.isEmpty()) return

        if (missingNames.isNotEmpty()) {
            val choice = Messages.showYesNoDialog(
                project,
                "有 ${missingNames.size} 个模块的本地目录不存在：\n" +
                missingNames.take(5).joinToString("\n") { "  - $it" } +
                (if (missingNames.size > 5) "\n  ... 等" else "") +
                "\n\n这些模块切换为 LOCAL 后状态将为「未下载」。继续吗？",
                "批量切换 LOCAL",
                Messages.getWarningIcon()
            )
            if (choice != Messages.YES) return
        }

        service.setIncludeBuildBatch(allNames, true)
    }

    private fun onAllMaven() {
        val allNames = service.modules.filter { it.includeBuild }.map { it.name }
        if (allNames.isEmpty()) return
        service.setIncludeBuildBatch(allNames, false)
    }

    private fun getOrCreateConsole(): ConsoleView {
        if (consoleView == null) {
            consoleView = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .console
        }
        return consoleView!!
    }

    // ─── 表格常量 ──────────────────────────────────────────
    companion object {
        const val COL_CHECKED = 0
        const val COL_NAME = 1
        const val COL_STATUS = 2
        const val COL_BRANCH = 3
        const val COL_ACTION = 4
    }

    // ─── 表格数据模型 ──────────────────────────────────────

    inner class ModuleTableModel : AbstractTableModel() {
        private var data: List<ModuleConfig> = emptyList()

        fun setData(modules: List<ModuleConfig>) {
            data = modules
            fireTableDataChanged()
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = 5

        override fun getValueAt(row: Int, col: Int): Any {
            val m = data[row]
            return when (col) {
                COL_CHECKED -> m.includeBuild
                COL_NAME -> m.name
                COL_STATUS -> m.status
                COL_BRANCH -> m.branch
                COL_ACTION -> if (m.status == ModuleStatus.MISSING) "↓ 下载" else ""
                else -> ""
            }
        }

        override fun getColumnClass(col: Int) = when (col) {
            COL_CHECKED -> Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, col: Int) =
            col == COL_CHECKED || (col == COL_ACTION && data[row].status == ModuleStatus.MISSING)
    }

    // ─── Cell Renderers / Editors ──────────────────────────

    private inner class CheckBoxRenderer : javax.swing.table.TableCellRenderer {
        private val cb = JCheckBox().apply {
            horizontalAlignment = SwingConstants.CENTER
            border = BorderFactory.createEmptyBorder()
        }
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): java.awt.Component {
            cb.isSelected = value as? Boolean ?: false
            cb.background = if (isSelected) table.selectionBackground else table.background
            // 设置不透明确保背景完全覆盖，防止相邻列溢出
            cb.isOpaque = true
            return cb
        }
    }

    private inner class CheckBoxEditor(
        private val onToggle: (Int, Boolean) -> Unit
    ) : DefaultCellEditor(JCheckBox()) {
        private var currentListener: java.awt.event.ActionListener? = null

        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, col: Int
        ): java.awt.Component {
            // 移除旧的 listener，避免重复触发
            currentListener?.let { (editorComponent as JCheckBox).removeActionListener(it) }

            val comp = super.getTableCellEditorComponent(table, value, isSelected, row, col)
            currentListener = java.awt.event.ActionListener {
                SwingUtilities.invokeLater { onToggle(row, (comp as JCheckBox).isSelected) }
            }
            (comp as JCheckBox).addActionListener(currentListener)
            return comp
        }
    }

    private inner class StatusRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): java.awt.Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            val status = value as? ModuleStatus
            text = status?.displayName ?: ""
            foreground = when (status) {
                ModuleStatus.LOCAL -> java.awt.Color(0x4CAF50)
                ModuleStatus.MISSING -> java.awt.Color(0xFF9800)
                ModuleStatus.MAVEN -> java.awt.Color(0x2196F3)
                null -> table.foreground
            }
            // 关键：防止文本溢出到相邻列（默认边框即可，Swing 会自动裁剪）
            toolTipText = if (status != null) status.displayName else null
            return comp
        }
    }

    private inner class ButtonRenderer : DefaultTableCellRenderer() {
        private val btn = JButton()
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): java.awt.Component {
            val text = value as? String ?: ""
            if (text.isBlank()) return JLabel()
            btn.text = text
            return btn
        }
    }

    private inner class ButtonEditor(
        private val table: JBTable,
        private val onClick: (Int) -> Unit
    ) : DefaultCellEditor(JCheckBox()) {
        private val btn = JButton()
        private var row = -1

        init {
            btn.addActionListener {
                fireEditingStopped()
                if (row >= 0) onClick(row)
            }
        }

        override fun getTableCellEditorComponent(
            t: JTable, value: Any?, isSelected: Boolean, r: Int, col: Int
        ): java.awt.Component {
            row = r
            btn.text = value as? String ?: ""
            return btn
        }

        override fun getCellEditorValue(): Any = btn.text
    }
}
