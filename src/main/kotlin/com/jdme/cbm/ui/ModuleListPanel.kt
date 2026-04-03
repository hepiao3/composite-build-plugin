package com.jdme.cbm.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.jdme.cbm.core.CbmProjectService
import com.jdme.cbm.core.GradleSyncTrigger
import com.jdme.cbm.core.ModuleDownloader
import com.jdme.cbm.model.ModuleConfig
import com.jdme.cbm.model.ModuleStatus
import com.jdme.cbm.model.getLocalGitBranch
import com.jdme.cbm.model.getAllBranches
import com.jdme.cbm.model.checkoutBranch
import com.jdme.cbm.model.hasUncommittedChanges
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.AnimatedIcon
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractCellEditor
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
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
    private val syncBtn = JButton("⟳ Sync Gradle")
    private var consoleView: ConsoleView? = null

    // 当前展示的（过滤后的）模块列表
    private var displayedModules: List<ModuleConfig> = emptyList()

    // 状态筛选复选框
    private val filterLocalCheckBox = JCheckBox("LOCAL")
    private val filterMavenCheckBox = JCheckBox("MAVEN")
    private var filterStatus: ModuleStatus? = null

    // 添加组件按钮
    private val addModuleBtn = JButton(AllIcons.General.Add).apply {
        toolTipText = "手动添加本地组件文件夹"
        preferredSize = java.awt.Dimension(JBUI.scale(24), JBUI.scale(24))
        border = JBUI.Borders.empty(2)
        isContentAreaFilled = false
    }

    // 保存/恢复图标按钮
    private val saveBtn = JButton(AllIcons.Actions.MenuSaveall).apply {
        isEnabled = false
        toolTipText = "保存当前 LOCAL 模块列表到当前分支快照"
        preferredSize = java.awt.Dimension(JBUI.scale(24), JBUI.scale(24))
        border = JBUI.Borders.empty(2)
        isContentAreaFilled = false
    }
    private val restoreBtn = JButton(AllIcons.Actions.Rollback).apply {
        isEnabled = false
        toolTipText = "恢复当前分支已保存的 LOCAL 模块快照"
        preferredSize = java.awt.Dimension(JBUI.scale(24), JBUI.scale(24))
        border = JBUI.Borders.empty(2)
        isContentAreaFilled = false
    }

    // 表头全选复选框
    private val headerCheckBox = JCheckBox().apply {
        horizontalAlignment = SwingConstants.CENTER
        border = BorderFactory.createEmptyBorder()
        isOpaque = true
    }

    // 分支名缓存：moduleName -> 当前本地分支名
    private val branchCache = ConcurrentHashMap<String, String>()
    private val branchLoadInProgress = AtomicBoolean(false)

    // 正在加载分支的模块名集合
    private val branchLoadingModules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 驱动分支 loading 动画刷新的定时器（EDT 线程）
    private val branchRepaintTimer = javax.swing.Timer(80) {
        val rowCount = tableModel.rowCount
        if (rowCount > 0) tableModel.fireTableRowsUpdated(0, rowCount - 1)
        if (branchLoadingModules.isEmpty()) (it.source as? javax.swing.Timer)?.stop()
    }

    // 正在下载的模块名集合
    private val downloadingModules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 下载期间驱动 loading 动画刷新的定时器（EDT 线程）
    private val downloadRepaintTimer = javax.swing.Timer(80) {
        val rowCount = tableModel.rowCount
        if (rowCount > 0) tableModel.fireTableRowsUpdated(0, rowCount - 1)
        if (downloadingModules.isEmpty()) (it.source as? javax.swing.Timer)?.stop()
    }

    init {
        buildUI()
        registerServiceListener()
        registerVisibilityListener()
        service.loadModules()
        loadBranchesAsync()
    }

    /**
     * 监听面板显示状态，当面板变得可见时从状态文件刷新勾选状态。
     * 解决工具窗口收起再展开时无法刷新勾选状态的问题。
     */
    private fun registerVisibilityListener() {
        addComponentListener(object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) {
                service.refreshFromStateFile()
            }
        })
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
        val refreshBranchBtn = JButton("↺ Refresh").apply {
            toolTipText = "从 project-repos.json5 重新加载所有模块"
            addActionListener {
                // 只清除 LOCAL 状态模块的分支缓存，MAVEN 模块保持现有缓存
                service.modules.filter { it.status == ModuleStatus.LOCAL }
                    .forEach { branchCache.remove(it.name) }
                service.loadModules()
                loadBranchesAsync()
            }
        }
        syncBtn.addActionListener {
            // 先从状态文件同步勾选状态，再触发 Gradle Sync
            service.refreshFromStateFile()
            service.markAsSynced()
            GradleSyncTrigger.sync(project)
            refreshTable()
        }
        val eastPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(refreshBranchBtn)
            add(syncBtn)
        }
        topPanel.add(searchField, BorderLayout.CENTER)
        topPanel.add(eastPanel, BorderLayout.EAST)

        // ─── 表格 ───────────────────────────────────────────
        table.apply {
            setShowGrid(false)
            rowHeight = JBUI.scale(28)
            intercellSpacing = Dimension(0, 0)
            expandableItemsHandler.setEnabled(false)
            autoResizeMode = javax.swing.JTable.AUTO_RESIZE_OFF
            columnModel.apply {
                getColumn(COL_CHECKED).apply {
                    maxWidth = JBUI.scale(30)
                    minWidth = JBUI.scale(30)
                    preferredWidth = JBUI.scale(30)
                    headerValue = ""
                }
                getColumn(COL_NAME).apply {
                    headerValue = "模块名"
                    preferredWidth = JBUI.scale(150)
                }
                getColumn(COL_STATUS).apply {
                    headerValue = "状态"
                    maxWidth = JBUI.scale(100)
                    minWidth = JBUI.scale(80)
                    preferredWidth = JBUI.scale(90)
                }
                getColumn(COL_BRANCH).apply {
                    headerValue = "分支"
                    minWidth = JBUI.scale(120)
                    preferredWidth = JBUI.scale(160)
                    cellRenderer = BranchRenderer()
                }
                getColumn(COL_ACTION).apply {
                    headerValue = ""
                    maxWidth = JBUI.scale(70)
                    minWidth = JBUI.scale(70)
                    preferredWidth = JBUI.scale(70)
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

        // 表头全选复选框
        table.tableHeader.columnModel.getColumn(COL_CHECKED).headerRenderer =
            object : javax.swing.table.TableCellRenderer {
                override fun getTableCellRendererComponent(
                    t: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, col: Int
                ): java.awt.Component {
                    headerCheckBox.background = table.tableHeader.background
                    val allChecked = displayedModules.isNotEmpty() && displayedModules.all { it.includeBuild }
                    headerCheckBox.isSelected = allChecked
                    return headerCheckBox
                }
            }
        table.tableHeader.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val col = table.tableHeader.columnAtPoint(e.point)
                if (col != COL_CHECKED) return
                val allChecked = displayedModules.isNotEmpty() && displayedModules.all { it.includeBuild }
                if (allChecked) onAllMaven() else onAllLocal()
            }
        })

        val scrollPane = JBScrollPane(table)

        val resizeDebounce = javax.swing.Timer(80) { adjustBranchWidth() }.apply { isRepeats = false }
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                resizeDebounce.restart()
            }
        })

        // 分支列点击监听（整个单元格均可触发）
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                val col = table.columnAtPoint(e.point)
                val row = table.rowAtPoint(e.point)
                if (col != COL_BRANCH || row < 0) return
                val module = displayedModules.getOrNull(row) ?: return
                if (!module.localDirExists) return
                showBranchPopup(row, module)
            }
        })

        // ─── 底栏 ───────────────────────────────────────────
        filterLocalCheckBox.addActionListener {
            if (filterLocalCheckBox.isSelected) {
                filterMavenCheckBox.isSelected = false
                filterStatus = ModuleStatus.LOCAL
            } else {
                filterStatus = null
            }
            applyFilter()
        }
        filterMavenCheckBox.addActionListener {
            if (filterMavenCheckBox.isSelected) {
                filterLocalCheckBox.isSelected = false
                filterStatus = ModuleStatus.MAVEN
            } else {
                filterStatus = null
            }
            applyFilter()
        }
        saveBtn.addActionListener {
            saveBtn.isEnabled = false
            service.saveLocalSnapshot { success ->
                if (success) {
                    flashSuccessIcon(saveBtn, AllIcons.Actions.MenuSaveall)
                } else {
                    Messages.showMessageDialog(
                        project,
                        "保存失败：无法获取当前分支名，请确认工程目录是 git 仓库。",
                        "保存失败",
                        Messages.getErrorIcon()
                    )
                    updateSaveRestoreButtonStates()
                }
            }
        }
        restoreBtn.addActionListener {
            restoreBtn.isEnabled = false
            service.restoreLocalSnapshot { success ->
                if (success) {
                    flashSuccessIcon(restoreBtn, AllIcons.Actions.Rollback)
                } else {
                    Messages.showMessageDialog(
                        project,
                        "恢复失败：当前分支没有已保存的模块快照，或无法获取分支名。",
                        "恢复失败",
                        Messages.getErrorIcon()
                    )
                    updateSaveRestoreButtonStates()
                }
            }
        }
        addModuleBtn.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
                title = "选择本地组件文件夹"
            }
            FileChooser.chooseFile(descriptor, project, null) { vFile ->
                val name = vFile.name
                val path = vFile.path
                service.addCustomModule(name, path)
            }
        }
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(filterLocalCheckBox)
            add(filterMavenCheckBox)
        }
        val bottomEastPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(statusLabel)
            add(addModuleBtn)
            add(saveBtn)
            add(restoreBtn)
        }
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0, 4, 4)
        }
        bottomPanel.add(filterPanel, BorderLayout.WEST)
        bottomPanel.add(bottomEastPanel, BorderLayout.EAST)

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun registerServiceListener() {
        service.addListener { applyFilterAndUpdateUI() }
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        val statusFilter = filterStatus
        displayedModules = service.modules.filter { module ->
            (query.isEmpty() || module.name.lowercase().contains(query)) &&
            (statusFilter == null || module.status == statusFilter)
        }
        tableModel.setData(displayedModules)
        updateStatusLabel()
        adjustBranchWidth()
        table.tableHeader.repaint()
    }

    private fun adjustBranchWidth() {
        val cm = table.columnModel
        val scrollPane = (table.parent?.parent) as? javax.swing.JScrollPane ?: return
        val viewportWidth = scrollPane.viewport.width
        if (viewportWidth <= 0) return
        // 无 MISSING 模块时隐藏 ACTION 列，让分支列贴紧右侧
        val hasMissing = displayedModules.any { it.status == ModuleStatus.MISSING }
        val actionWidth = if (hasMissing) JBUI.scale(70) else 0
        cm.getColumn(COL_ACTION).apply {
            minWidth = actionWidth
            maxWidth = actionWidth
            preferredWidth = actionWidth
        }
        val otherWidth = (0 until cm.columnCount)
            .filter { it != COL_BRANCH }
            .sumOf { cm.getColumn(it).preferredWidth }
        val newWidth = maxOf(JBUI.scale(120), viewportWidth - otherWidth)
        cm.getColumn(COL_BRANCH).preferredWidth = newWidth
        table.revalidate()
        table.repaint()
    }

    private fun refreshTable() {
        applyFilter()
        loadBranchesAsync()
    }

    /** 仅刷新表格显示（过滤+UI），不触发分支加载。供 service listener 使用。 */
    private fun applyFilterAndUpdateUI() {
        applyFilter()
    }

    /**
     * 在后台线程批量执行 git rev-parse，完成后回到 EDT 刷新分支列。
     * 用 AtomicBoolean 防止并发重入。
     */
    private fun loadBranchesAsync() {
        if (!branchLoadInProgress.compareAndSet(false, true)) return
        val projectRoot = java.io.File(project.basePath ?: "")
        val localModules = service.modules.filter { it.localDirExists }

        // 只对 LOCAL 状态模块标记 loading 动画
        localModules.filter { it.status == ModuleStatus.LOCAL }
            .forEach { branchLoadingModules.add(it.name) }
        SwingUtilities.invokeLater {
            if (!branchRepaintTimer.isRunning) branchRepaintTimer.start()
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                for (module in localModules) {
                    val branch = module.getLocalGitBranch(projectRoot)
                    if (branch != null) {
                        branchCache[module.name] = branch
                    }
                    branchLoadingModules.remove(module.name)
                }
            } finally {
                branchLoadInProgress.set(false)
                branchLoadingModules.clear()
            }
            SwingUtilities.invokeLater {
                val rowCount = tableModel.rowCount
                if (rowCount > 0) {
                    tableModel.fireTableRowsUpdated(0, rowCount - 1)
                }
            }
        }
    }

    private fun updateStatusLabel() {
        val all = service.modules
        val localCount = all.count { it.status == ModuleStatus.LOCAL }
        val mavenCount = all.count { it.status == ModuleStatus.MAVEN }
        val missingCount = all.count { it.status == ModuleStatus.MISSING }
        filterLocalCheckBox.text = "LOCAL ($localCount)"
        filterMavenCheckBox.text = "MAVEN ($mavenCount)"
        statusLabel.text = if (missingCount > 0) "未下载: $missingCount" else ""
        if (service.hasUnsavedChanges) {
            syncBtn.text = "⚠ Sync Gradle"
            syncBtn.foreground = java.awt.Color(0xE65100)
            syncBtn.toolTipText = "有未同步的改动，点击使配置生效"
        } else {
            syncBtn.text = "⟳ Sync Gradle"
            syncBtn.foreground = null
            syncBtn.toolTipText = null
        }
        updateSaveRestoreButtonStates()
    }

    private fun updateSaveRestoreButtonStates() {
        saveBtn.isEnabled = service.hasLocalModules()
        service.hasSavedSnapshotForCurrentBranch { hasSnapshot ->
            restoreBtn.isEnabled = hasSnapshot
        }
    }

    /**
     * 将按钮图标短暂替换为对勾，1.5 秒后还原为 [originalIcon] 并恢复 enabled 状态。
     * 在 EDT 调用。
     */
    private fun flashSuccessIcon(btn: JButton, originalIcon: javax.swing.Icon) {
        btn.icon = AllIcons.Actions.Checked
        btn.isEnabled = true
        javax.swing.Timer(1500) {
            btn.icon = originalIcon
            updateSaveRestoreButtonStates()
        }.apply { isRepeats = false; start() }
    }

    private fun onToggleIncludeBuild(row: Int, value: Boolean) {
        val module = displayedModules.getOrNull(row) ?: return
        if (value && !module.localDirExists) {
            val choice = Messages.showYesNoDialog(
                project,
                "模块 ${module.name} 的本地目录不存在（${module.localDirName}）。\n\n" +
                "是否现在下载？",
                "模块未下载",
                "下载",
                "取消",
                Messages.getWarningIcon()
            )
            if (choice != Messages.YES) return
            downloadModule(module)
            return
        }
        service.setIncludeBuild(module.name, value)
    }

    private fun onDownloadClick(row: Int) {
        val module = displayedModules.getOrNull(row) ?: return
        downloadModule(module)
    }

    private fun downloadModule(module: ModuleConfig) {
        downloadingModules.add(module.name)
        if (!downloadRepaintTimer.isRunning) downloadRepaintTimer.start()
        val rowCount = tableModel.rowCount
        if (rowCount > 0) tableModel.fireTableRowsUpdated(0, rowCount - 1)

        val console = getOrCreateConsole()
        ModuleDownloader.download(
            module = module,
            project = project,
            projectRoot = java.io.File(project.basePath ?: ""),
            console = console
        ) { success ->
            downloadingModules.remove(module.name)
            if (success) {
                // 重新加载以更新 localDirExists 状态
                service.loadModules()
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

    private fun showBranchPopup(row: Int, module: ModuleConfig) {
        val projectRoot = java.io.File(project.basePath ?: "")
        val allBranches = module.getAllBranches(projectRoot)
        val currentBranch = module.getLocalGitBranch(projectRoot)

        // 当前分支置顶（currentBranch 为 null 时直接显示全部分支），其余按原顺序
        val sortedBranches = if (currentBranch != null) {
            listOf(currentBranch) + allBranches.filter { it != currentBranch }
        } else {
            allBranches
        }

        val listModel = javax.swing.DefaultListModel<String>()
        sortedBranches.forEach { listModel.addElement(it) }
        val branchList = JBList(listModel)
        branchList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        branchList.setCellRenderer(object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): java.awt.Component {
                val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val branch = value as? String ?: ""
                text = branch
                if (branch == currentBranch) {
                    icon = AllIcons.Actions.Checked
                    horizontalTextPosition = SwingConstants.LEFT
                } else {
                    icon = null
                }
                return comp
            }
        })

        val searchField = com.intellij.ui.components.JBTextField().apply {
            emptyText.text = "搜索分支..."
            border = JBUI.Borders.empty(2, 4)
        }

        fun filterList() {
            val q = searchField.text.trim().lowercase()
            listModel.clear()
            val filtered = if (q.isEmpty()) sortedBranches
                           else sortedBranches.filter { it.lowercase().contains(q) }
            filtered.forEach { listModel.addElement(it) }
        }
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = filterList()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = filterList()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = filterList()
        })

        val cellRect = table.getCellRect(row, COL_BRANCH, false)
        val popupWidth = JBUI.scale(216)
        val popupPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(6)
            preferredSize = Dimension(popupWidth, JBUI.scale(260))
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(branchList), BorderLayout.CENTER)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupPanel, searchField)
            .setRequestFocus(true)
            .createPopup()

        fun selectAndClose() {
            val selected = branchList.selectedValue ?: return
            popup.cancel()
            onBranchSelected(row, selected)
        }

        fun isOnItem(point: java.awt.Point): Boolean {
            val index = branchList.locationToIndex(point)
            if (index < 0) return false
            val cellBounds = branchList.getCellBounds(index, index) ?: return false
            return cellBounds.contains(point)
        }
        branchList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (!isOnItem(e.point)) e.consume()
            }
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (!isOnItem(e.point)) e.consume()
            }
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (!isOnItem(e.point)) { e.consume(); return }
                selectAndClose()
            }
        })
        branchList.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) selectAndClose()
            }
        })

        val tableScreenLoc = table.locationOnScreen
        // 右对齐：弹窗右边缘与箭头图标右边缘对齐
        val branchText = branchCache[module.name] ?: ""
        val fm = table.getFontMetrics(table.font)
        val textWidth = fm.stringWidth(branchText)
        val arrowIcon = AllIcons.General.ArrowDown
        val arrowRightInCell = JBUI.scale(2) + textWidth + JBUI.scale(4) + arrowIcon.iconWidth
        val popupX = tableScreenLoc.x + cellRect.x + arrowRightInCell - popupWidth
        val popupHeight = JBUI.scale(260)
        val screenBounds = table.graphicsConfiguration?.bounds
            ?: java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().screenSize)
        val spaceBelow = screenBounds.y + screenBounds.height -
                (tableScreenLoc.y + cellRect.y + cellRect.height)
        val popupY = if (spaceBelow >= popupHeight) {
            tableScreenLoc.y + cellRect.y + cellRect.height
        } else {
            tableScreenLoc.y + cellRect.y - popupHeight
        }
        popup.showInScreenCoordinates(table, java.awt.Point(popupX, popupY))
    }

    private fun onBranchSelected(row: Int, branchName: String) {
        val module = displayedModules.getOrNull(row) ?: return
        if (!module.localDirExists) return

        val projectRoot = java.io.File(project.basePath ?: "")

        // 切换前检查是否有未提交修改
        if (module.hasUncommittedChanges(projectRoot)) {
            val choice = Messages.showYesNoDialog(
                project,
                "模块 ${module.name} 存在未提交的修改。\n\n" +
                "强制切换分支可能导致修改丢失，建议先 commit 或 stash。\n\n" +
                "是否仍要切换到 $branchName？",
                "存在未提交修改",
                "继续切换",
                "取消",
                Messages.getWarningIcon()
            )
            if (choice != Messages.YES) return
        }

        val error = module.checkoutBranch(projectRoot, branchName)
        if (error == null) {
            // 远程分支切换后实际所在的是同名本地分支
            val actualBranch = if (branchName.startsWith("origin/")) branchName.removePrefix("origin/") else branchName
            branchCache[module.name] = actualBranch
            val rowCount = tableModel.rowCount
            if (rowCount > 0) tableModel.fireTableRowsUpdated(0, rowCount - 1)
        } else {
            Messages.showMessageDialog(
                project,
                "切换到分支 $branchName 失败。\n\n$error",
                "切换分支失败",
                Messages.getErrorIcon()
            )
        }
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

        override fun getValueAt(row: Int, col: Int): Any? {
            val m = data[row]
            return when (col) {
                COL_CHECKED -> m.includeBuild
                COL_NAME -> m.name
                COL_STATUS -> m.status
                COL_BRANCH -> branchCache[m.name]
                COL_ACTION -> if (m.status == ModuleStatus.MISSING) "↓ 下载" else ""
                else -> ""
            }
        }

        override fun getColumnClass(col: Int) = when (col) {
            COL_CHECKED -> Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, col: Int): Boolean {
            if (col == COL_CHECKED && data.getOrNull(row)?.name in downloadingModules) return false
            return col == COL_CHECKED || (col == COL_ACTION && data[row].status == ModuleStatus.MISSING)
        }
    }

    // ─── Cell Renderers / Editors ──────────────────────────

    private inner class CheckBoxRenderer : javax.swing.table.TableCellRenderer {
        private val cb = JCheckBox().apply {
            horizontalAlignment = SwingConstants.CENTER
            border = BorderFactory.createEmptyBorder()
        }
        private val loadingLabel = JLabel(AnimatedIcon.Default.INSTANCE).apply {
            horizontalAlignment = SwingConstants.CENTER
            isOpaque = true
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): java.awt.Component {
            val bg = if (isSelected) table.selectionBackground else table.background
            val module = displayedModules.getOrNull(row)
            if (module != null && module.name in downloadingModules) {
                loadingLabel.background = bg
                return loadingLabel
            }
            cb.isSelected = value as? Boolean ?: false
            cb.background = bg
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
            toolTipText = null
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

    /** 分支列的 Renderer：显示分支名和下拉箭头，LOCAL 状态时鼠标变为手型 */
    private inner class BranchRenderer : DefaultTableCellRenderer() {
        private var currentRow: Int = -1
        private var showArrow: Boolean = false
        private val branchLoadingLabel = JLabel(AnimatedIcon.Default.INSTANCE).apply {
            horizontalAlignment = SwingConstants.LEFT
            border = BorderFactory.createEmptyBorder(0, 2, 0, 2)
            isOpaque = true
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): java.awt.Component {
            currentRow = row
            val module = displayedModules.getOrNull(row)
            val isLocal = module?.localDirExists == true
            val branchName = value as? String

            // 正在加载分支时显示 loading 动画
            if (module != null && module.name in branchLoadingModules) {
                branchLoadingLabel.background = if (isSelected) table.selectionBackground else table.background
                return branchLoadingLabel
            }

            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)

            text = branchName ?: if (!isLocal) "暂无（未下载）" else ""
            foreground = when {
                isSelected -> table.selectionForeground
                !isLocal && branchName == null -> java.awt.Color(0x888888)
                else -> table.foreground
            }
            showArrow = isLocal && !branchName.isNullOrEmpty()

            cursor = if (isLocal) {
                java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            } else {
                java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.DEFAULT_CURSOR)
            }
            toolTipText = null
            border = BorderFactory.createEmptyBorder(0, 2, 0, 2)

            return comp
        }

        override fun paint(g: Graphics) {
            super.paint(g)
            if (!showArrow) return

            val icon = AllIcons.General.ArrowDown
            val textWidth = g.fontMetrics.stringWidth(text)
            val x = JBUI.scale(2) + textWidth + JBUI.scale(4)
            val y = (height - icon.iconHeight) / 2
            if (x + icon.iconWidth <= width) {
                icon.paintIcon(this, g, x, y)
            }
        }
    }

}
