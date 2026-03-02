package com.jdme.cbm.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.jdme.cbm.model.ModuleConfig
import java.io.File

/**
 * 项目级服务：协调配置解析、文件写入和 Sync 触发的业务逻辑。
 * 作为 UI 组件和 Actions 的共用后端。
 *
 * 存储结构：
 * - project-repos.json5：仅作为组件配置表（模块名、URL、branch）
 * - .cbm-include-build.json：插件内部存储的复合构建启用状态（供 settings.gradle 读取）
 *
 * 工作原理：
 * - 加载时：解析 project-repos.json5 获取模块列表，从 .cbm-include-build.json 获取启用状态
 * - 切换时：只更新 .cbm-include-build.json，不再修改 project-repos.json5
 * - settings.gradle：读取 .cbm-include-build.json 动态生成 includeBuild 配置
 */
@Service(Service.Level.PROJECT)
class CbmProjectService(private val project: Project) {

    private val LOG = logger<CbmProjectService>()

    private val projectRoot: File get() {
        val path = project.basePath
        if (path.isNullOrBlank()) {
            LOG.warn("project.basePath is null or blank, state file may be created in wrong location")
        }
        return File(path ?: "")
    }

    val configFile: File
        get() {
            val customPath = CbmSettings.getInstance().customConfigPath
            return if (customPath.isNotBlank()) File(customPath)
                   else File(projectRoot, "scripts/module_manager/project-repos.json5")
        }

    /**
     * 插件状态文件，供 settings.gradle 读取以动态生成 includeBuild 配置。
     * 文件格式：{ "enabledModules": ["jm_common", "jm_login", ...] }
     */
    val stateFile: File get() = File(projectRoot, ".cbm-include-build.json")

    private var _modules: MutableList<ModuleConfig> = mutableListOf()
    val modules: List<ModuleConfig> get() = _modules.toList()

    /** 已启用复合构建的模块名集合 */
    private var _enabledModules: MutableSet<String> = mutableSetOf()
    val enabledModules: Set<String> get() = _enabledModules.toSet()

    /** 是否有未同步到 Gradle 的更改（用户已勾选但还未 Sync） */
    private var _hasUnsavedChanges: Boolean = false
    val hasUnsavedChanges: Boolean get() = _hasUnsavedChanges

    /**
     * 标记已同步完成，清除待 Sync 提示。
     * 在用户点击 Sync Gradle 后调用。
     */
    fun markAsSynced() {
        _hasUnsavedChanges = false
        notifyListeners()
    }

    // ========== 状态文件读写 ==========

    /**
     * 从 .cbm-include-build.json 加载启用状态。
     * 文件格式：{ "enabledModules": ["jm_common", "jm_login"] }
     */
    private fun loadEnabledModulesFromStateFile(): Set<String> {
        val file = stateFile
        if (!file.exists()) {
            LOG.info("State file not found, using empty enabled modules")
            return emptySet()
        }

        return try {
            val content = file.readText()
            // 简单解析 JSON：提取 enabledModules 数组（使用贪婪匹配支持多行）
            val regex = Regex("""\"enabledModules\"\s*:\s*\[([\s\S]*?)\]""")
            val match = regex.find(content)

            if (match != null) {
                val arrayContent = match.groupValues[1]
                if (arrayContent.isBlank()) {
                    emptySet()
                } else {
                    // 提取引号内的模块名
                    val moduleRegex = Regex("\"([^\"]+)\"")
                    moduleRegex.findAll(arrayContent).map { it.groupValues[1] }.toSet()
                }
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            LOG.error("Failed to parse state file", e)
            emptySet()
        }
    }

    /**
     * 将启用状态保存到 .cbm-include-build.json。
     */
    private fun saveEnabledModulesToStateFile() {
        val file = stateFile
        LOG.info("Attempting to save state file to: ${file.absolutePath}")
        try {
            val sb = StringBuilder()
            sb.appendLine("{")
            sb.appendLine("  \"enabledModules\": [")
            val modules = _enabledModules.sorted()
            modules.forEachIndexed { index, module ->
                val comma = if (index < modules.size - 1) "," else ""
                sb.appendLine("    \"$module\"$comma")
            }
            sb.appendLine("  ],")
            sb.appendLine("  \"updatedAt\": \"${java.time.LocalDateTime.now()}\"")
            sb.appendLine("}")
            file.writeText(sb.toString())
            LOG.info("Saved ${_enabledModules.size} enabled modules to state file")
        } catch (e: Exception) {
            LOG.error("Failed to save state file", e)
            throw e
        }
    }

    /** 检查指定模块是否启用复合构建 */
    fun isIncludeBuildEnabled(moduleName: String): Boolean = _enabledModules.contains(moduleName)

    // 加载状态监听器列表（UI 订阅）
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun notifyListeners() { listeners.forEach { it() } }

    /**
     * 从 JSON5 文件加载模块列表，同时从状态文件获取启用状态。
     * 只按 .cbm-include-build.json 中 enabledModules 列表来勾选模块。
     * 在后台线程执行。
     */
    fun loadModules(onComplete: ((List<ModuleConfig>) -> Unit)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // 1. 从状态文件加载启用状态
            _enabledModules = loadEnabledModulesFromStateFile().toMutableSet()

            // 2. 加载 JSON5 配置
            val loaded = if (configFile.exists()) {
                Json5ConfigManager.load(configFile, projectRoot)
            } else {
                LOG.warn("Config file not found: ${configFile.absolutePath}")
                emptyList()
            }

            // 3. 根据状态文件设置模块的 includeBuild 状态
            _modules = loaded.map { module ->
                module.copy(includeBuild = _enabledModules.contains(module.name))
            }.toMutableList()

            ApplicationManager.getApplication().invokeLater {
                notifyListeners()
                onComplete?.invoke(_modules)
            }
        }
    }

    /**
     * 仅从状态文件刷新勾选状态，不重新加载 JSON5 配置。
     * 用于面板每次显示时从外部文件同步最新状态。
     */
    fun refreshFromStateFile() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val newEnabledModules = loadEnabledModulesFromStateFile()
            val changed = newEnabledModules != _enabledModules

            if (changed) {
                _enabledModules = newEnabledModules.toMutableSet()
                // 更新模块的勾选状态
                _modules = _modules.map { module ->
                    module.copy(includeBuild = _enabledModules.contains(module.name))
                }.toMutableList()
                // 从文件刷新后，已同步到最新状态
                _hasUnsavedChanges = false

                ApplicationManager.getApplication().invokeLater {
                    notifyListeners()
                }
            }
        }
    }

    /**
     * 切换指定模块的 includeBuild 状态：
     * 1. 更新内存状态和 _enabledModules
     * 2. 保存到状态文件 .cbm-include-build.json
     * 3. 通知 UI 更新
     *
     * 注意：不再修改 project-repos.json5
     */
    fun setIncludeBuild(moduleName: String, value: Boolean, onComplete: (() -> Unit)? = null) {
        val idx = _modules.indexOfFirst { it.name == moduleName }
        if (idx < 0) {
            LOG.warn("Module not found: $moduleName")
            return
        }

        // 更新内存状态
        _modules[idx] = _modules[idx].copy(includeBuild = value)
        // 标记有未同步的更改，需要等 Sync 后才生效
        _hasUnsavedChanges = true
        if (value) {
            _enabledModules.add(moduleName)
        } else {
            _enabledModules.remove(moduleName)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                saveEnabledModulesToStateFile()
                LOG.info("$moduleName.includeBuild = $value  [state file saved]")
                ApplicationManager.getApplication().invokeLater { notifyListeners() }
            } catch (e: Exception) {
                LOG.error("Failed to save state file", e)
                // 回滚内存状态
                _modules[idx] = _modules[idx].copy(includeBuild = !value)
                if (value) _enabledModules.remove(moduleName) else _enabledModules.add(moduleName)
                ApplicationManager.getApplication().invokeLater { notifyListeners() }
            }
            ApplicationManager.getApplication().invokeLater { onComplete?.invoke() }
        }
    }

    /**
     * 批量切换：将多个模块切换到相同的 includeBuild 值。
     * 注意：不再修改 project-repos.json5
     */
    fun setIncludeBuildBatch(moduleNames: List<String>, value: Boolean, onComplete: (() -> Unit)? = null) {
        val indices = moduleNames.mapNotNull { name ->
            val idx = _modules.indexOfFirst { it.name == name }
            if (idx >= 0) Pair(idx, name) else null
        }

        // 乐观更新内存
        indices.forEach { (idx, name) ->
            _modules[idx] = _modules[idx].copy(includeBuild = value)
            if (value) _enabledModules.add(name) else _enabledModules.remove(name)
        }
        // 标记有未同步的更改，需要等 Sync 后才生效
        _hasUnsavedChanges = true
        notifyListeners()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                saveEnabledModulesToStateFile()
                LOG.info("Batch set ${moduleNames.size} modules to includeBuild=$value")
            } catch (e: Exception) {
                LOG.error("Batch update failed", e)
                // 回滚
                indices.forEach { (idx, name) ->
                    _modules[idx] = _modules[idx].copy(includeBuild = !value)
                    if (value) _enabledModules.remove(name) else _enabledModules.add(name)
                }
                ApplicationManager.getApplication().invokeLater { notifyListeners() }
            }
            // 无论成功或失败都需要刷新 UI（成功时在上面的 notifyListeners() 已调用，这里确保也执行）
            ApplicationManager.getApplication().invokeLater { notifyListeners() }
            ApplicationManager.getApplication().invokeLater { onComplete?.invoke() }
        }
    }

    companion object {
        fun getInstance(project: Project): CbmProjectService =
            project.getService(CbmProjectService::class.java)
    }
}
