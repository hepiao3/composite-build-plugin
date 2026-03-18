package com.jdme.cbm.core

import com.android.tools.idea.gradle.project.sync.GradleSyncListenerWithRoot
import com.android.tools.idea.gradle.project.sync.GradleSyncState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.jdme.cbm.model.ModuleConfig
import com.jdme.cbm.model.checkLocalDir
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

    /** 上次 Sync 后的勾选状态快照，用于判断是否有未同步的更改 */
    private var _syncedEnabledModules: Set<String> = emptySet()

    /** 是否有未同步到 Gradle 的更改（当前勾选状态与上次 Sync 状态不同） */
    val hasUnsavedChanges: Boolean get() = _enabledModules != _syncedEnabledModules

    /**
     * 标记已同步完成，更新快照。
     * 在用户点击 Sync Gradle 后调用。
     */
    fun markAsSynced() {
        _syncedEnabledModules = _enabledModules.toSet()
        notifyListeners()
    }

    // ========== 状态文件读写 ==========

    /**
     * 从 .cbm-include-build.json 加载启用状态。
     * 支持新格式（modules 数组）和旧格式（enabledModules 数组）兼容读取。
     */
    private fun loadEnabledModulesFromStateFile(): Set<String> {
        val file = stateFile
        if (!file.exists()) {
            LOG.info("State file not found, using empty enabled modules")
            return emptySet()
        }

        return try {
            val content = file.readText()

            // 新格式：从 modules 数组中提取 name 字段
            val modulesArrayRe = Regex(""""modules"\s*:\s*\[([\s\S]*?)\]""")
            val modulesMatch = modulesArrayRe.find(content)
            if (modulesMatch != null) {
                val nameRe = Regex(""""name"\s*:\s*"([^"]+)"""")
                return nameRe.findAll(modulesMatch.groupValues[1])
                    .map { it.groupValues[1] }
                    .toSet()
            }

            // 旧格式兼容：enabledModules 数组
            val legacyRe = Regex(""""enabledModules"\s*:\s*\[([\s\S]*?)\]""")
            val legacyMatch = legacyRe.find(content)
            if (legacyMatch != null) {
                val arrayContent = legacyMatch.groupValues[1]
                if (arrayContent.isBlank()) return emptySet()
                val moduleRe = Regex("\"([^\"]+)\"")
                return moduleRe.findAll(arrayContent).map { it.groupValues[1] }.toSet()
            }

            emptySet()
        } catch (e: Exception) {
            LOG.error("Failed to parse state file", e)
            emptySet()
        }
    }

    /**
     * 将启用状态保存到 .cbm-include-build.json（新格式）。
     *
     * 新格式：
     * {
     *   "groupId": "com.example",
     *   "productFlavors": ["me", "global"],
     *   "modules": [
     *     { "name": "jm_common", "path": "../jm_common_project" },
     *     { "name": "jm_web_impl", "path": "../jm_web_impl_project", "flavor": true }
     *   ],
     *   "updatedAt": "..."
     * }
     */
    private fun saveEnabledModulesToStateFile() {
        val file = stateFile
        LOG.info("Attempting to save state file to: ${file.absolutePath}")
        try {
            val groupId = ProjectInfoReader.readGroupId(projectRoot) ?: ""
            val activeFlavor = BuildVariantReader.getActiveFlavor(project)

            val enabledList = _modules.filter { _enabledModules.contains(it.name) }

            val sb = StringBuilder()
            sb.appendLine("{")
            sb.appendLine("  \"groupId\": \"$groupId\",")
            sb.appendLine("  \"activeFlavor\": \"${activeFlavor ?: ""}\",")

            // modules 数组
            sb.appendLine("  \"modules\": [")
            enabledList.forEachIndexed { index, module ->
                val comma = if (index < enabledList.size - 1) "," else ""
                if (module.flavorSubstitution) {
                    sb.appendLine("    { \"name\": \"${module.name}\", \"flavor\": true }$comma")
                } else {
                    sb.appendLine("    { \"name\": \"${module.name}\" }$comma")
                }
            }
            sb.appendLine("  ],")
            sb.appendLine("  \"updatedAt\": \"${java.time.LocalDateTime.now()}\"")
            sb.appendLine("}")

            file.writeText(sb.toString())
            LOG.info("Saved ${enabledList.size} modules to state file (groupId=$groupId, activeFlavor=$activeFlavor)")
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
            val enabledChanged = newEnabledModules != _enabledModules

            if (enabledChanged) {
                _enabledModules = newEnabledModules.toMutableSet()
                _syncedEnabledModules = _enabledModules.toSet()
            }

            // 无论勾选状态是否变化，都重新检测本地目录，确保删除目录后状态能及时更新
            val updatedModules = _modules.map { module ->
                module.copy(
                    includeBuild = _enabledModules.contains(module.name),
                    localDirExists = module.checkLocalDir(projectRoot)
                )
            }.toMutableList()
            val dirChanged = updatedModules.zip(_modules).any { (new, old) -> new.localDirExists != old.localDirExists }

            if (enabledChanged || dirChanged) {
                _modules = updatedModules

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

        val currentValue = _modules[idx].includeBuild
        // 如果新值与原值相同，不做处理
        if (currentValue == value) return

        // 更新内存状态
        _modules[idx] = _modules[idx].copy(includeBuild = value)
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

        // 过滤出实际需要变更的模块
        val toUpdate = indices.filter { (idx, _) -> _modules[idx].includeBuild != value }
        if (toUpdate.isEmpty()) return

        // 乐观更新内存
        toUpdate.forEach { (idx, name) ->
            _modules[idx] = _modules[idx].copy(includeBuild = value)
            if (value) _enabledModules.add(name) else _enabledModules.remove(name)
        }
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
