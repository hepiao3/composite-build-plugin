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
 * - ~/.gradle/cbm/<hash>.json：插件状态文件，供 Gradle init script 读取
 * - ~/.gradle/init.d/cbm.gradle：Gradle init script，由插件自动部署和维护
 *
 * 工作原理：
 * - 加载时：解析 project-repos.json5 获取模块列表，从状态文件获取启用状态
 * - 切换时：只更新状态文件
 * - Gradle 构建时：init script 读取状态文件，动态生成 includeBuild 配置
 */
@Service(Service.Level.PROJECT)
class CbmProjectService(private val project: Project) {

    private val LOG = logger<CbmProjectService>()

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            CbmInitScriptManager.deployInitScript()
            CbmInitScriptManager.migrateOldStateFile(projectRoot)
        }
        GradleSyncState.subscribe(project, object : GradleSyncListenerWithRoot {
            override fun syncStarted(project: Project, rootProjectPath: String) {
                // 在 Gradle 进程启动前同步写入最新 flavor，确保 init script 读到正确值
                if (_enabledModules.isEmpty()) return
                try {
                    saveEnabledModulesToStateFile()
                    LOG.info("State file updated before Gradle sync started")
                } catch (e: Exception) {
                    LOG.error("Failed to update state file before sync", e)
                }
            }
        }, project)
    }

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

    /** 当前工程的状态文件，位于 ~/.gradle/cbm/<hash>.json */
    val stateFile: File get() = CbmInitScriptManager.stateFileFor(projectRoot)

    private var _modules: MutableList<ModuleConfig> = mutableListOf()
    val modules: List<ModuleConfig> get() = _modules.toList()

    /** 已启用复合构建的模块名集合 */
    private var _enabledModules: MutableSet<String> = mutableSetOf()

    /** 手动添加的自定义组件：name -> absolutePath */
    private var _customModuleEntries: MutableMap<String, String> = mutableMapOf()

    /** 手动添加的自定义组件依赖替换规则：name -> List<DepSubstitution> */
    private var _customModuleDeps: MutableMap<String, List<com.jdme.cbm.model.DepSubstitution>> = mutableMapOf()
    val enabledModules: Set<String> get() = _enabledModules.toSet()

    /** 上次 Sync 后的勾选状态快照，用于判断是否有未同步的更改 */
    private var _syncedEnabledModules: Set<String> = emptySet()

    /** 分支快照存储：branchName -> Set<moduleName>（LOCAL 状态模块名集合）*/
    private var _branchSnapshots: MutableMap<String, Set<String>> = mutableMapOf()

    /** 快照文件路径，与 stateFile 同目录 */
    val snapshotFile: File get() = CbmInitScriptManager.snapshotFileFor(projectRoot)

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

    // ========== 分支获取 ==========

    /**
     * 同步获取当前工程主仓库分支名（必须在后台线程调用）。
     * 返回 null 表示获取失败（非 git 仓库或命令执行出错）。
     */
    fun getProjectBranch(): String? {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(projectRoot)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val result = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && result.isNotEmpty()) result else null
        } catch (e: Exception) {
            LOG.warn("Failed to get project branch", e)
            null
        }
    }

    // ========== 快照文件读写 ==========

    /**
     * 从快照文件加载 _branchSnapshots（必须在后台线程调用）。
     * 文件格式：{ "master": ["module1", "module2"], "feature/xxx": ["module3"] }
     */
    private fun loadSnapshotsFromFile() {
        val file = snapshotFile
        if (!file.exists()) {
            _branchSnapshots = mutableMapOf()
            return
        }
        try {
            val content = file.readText()
            val result = mutableMapOf<String, Set<String>>()
            // 解析顶层每个 key（分支名）及其模块数组，支持转义字符
            val branchRe = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*\[([\s\S]*?)\]""")
            branchRe.findAll(content).forEach { match ->
                val branchName = match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                val arrayContent = match.groupValues[2]
                val moduleRe = Regex("\"([^\"]+)\"")
                val modules = moduleRe.findAll(arrayContent).map { it.groupValues[1] }.toSet()
                result[branchName] = modules
            }
            _branchSnapshots = result
            LOG.info("Loaded ${result.size} branch snapshots from ${file.absolutePath}")
        } catch (e: Exception) {
            LOG.error("Failed to load snapshots file", e)
            _branchSnapshots = mutableMapOf()
        }
    }

    /**
     * 将 _branchSnapshots 写入快照文件（必须在后台线程调用）。
     */
    private fun saveSnapshotsToFile() {
        val file = snapshotFile
        try {
            val sb = StringBuilder()
            sb.appendLine("{")
            val entries = _branchSnapshots.entries.toList()
            entries.forEachIndexed { index, (branch, modules) ->
                val escapedBranch = branch.replace("\\", "\\\\").replace("\"", "\\\"")
                val modulesJson = modules.joinToString(", ") { "\"$it\"" }
                val comma = if (index < entries.size - 1) "," else ""
                sb.appendLine("  \"$escapedBranch\": [$modulesJson]$comma")
            }
            sb.appendLine("}")
            file.writeText(sb.toString())
            LOG.info("Saved ${_branchSnapshots.size} branch snapshots to ${file.absolutePath}")
        } catch (e: Exception) {
            LOG.error("Failed to save snapshots file", e)
            throw e
        }
    }

    // ========== 快照对外 API ==========

    /** 判断当前是否有任何 LOCAL 状态模块（用于保存按钮 enabled 状态） */
    fun hasLocalModules(): Boolean = _enabledModules.isNotEmpty()

    /**
     * 异步检查当前分支是否已有快照（用于恢复按钮 enabled 状态）。
     * [onResult] 在 EDT 执行。
     */
    fun hasSavedSnapshotForCurrentBranch(onResult: (Boolean) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val branch = getProjectBranch()
            val has = branch != null && _branchSnapshots.containsKey(branch)
            ApplicationManager.getApplication().invokeLater { onResult(has) }
        }
    }

    /**
     * 将当前 LOCAL 模块列表保存为当前工程主分支的快照。
     * [onComplete] 在 EDT 执行，参数为是否成功。
     */
    fun saveLocalSnapshot(onComplete: ((Boolean) -> Unit)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val branch = getProjectBranch()
            if (branch == null) {
                LOG.warn("saveLocalSnapshot: cannot determine current branch")
                ApplicationManager.getApplication().invokeLater { onComplete?.invoke(false) }
                return@executeOnPooledThread
            }
            val snapshot = _enabledModules.toSet()
            if (snapshot.isEmpty()) {
                LOG.info("saveLocalSnapshot: no LOCAL modules, skip saving for branch '$branch'")
                ApplicationManager.getApplication().invokeLater { onComplete?.invoke(false) }
                return@executeOnPooledThread
            }
            try {
                _branchSnapshots[branch] = snapshot
                saveSnapshotsToFile()
                LOG.info("Saved snapshot for branch '$branch': ${snapshot.size} modules")
                ApplicationManager.getApplication().invokeLater { onComplete?.invoke(true) }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater { onComplete?.invoke(false) }
            }
        }
    }

    /**
     * 恢复当前分支的快照：快照中的模块设为 LOCAL，其余设为 MAVEN。
     * [onComplete] 在 EDT 执行，参数为是否成功（无快照时为 false）。
     */
    fun restoreLocalSnapshot(onComplete: ((Boolean) -> Unit)? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val branch = getProjectBranch()
            if (branch == null) {
                LOG.warn("restoreLocalSnapshot: cannot determine current branch")
                ApplicationManager.getApplication().invokeLater { onComplete?.invoke(false) }
                return@executeOnPooledThread
            }
            val snapshot = _branchSnapshots[branch]
            if (snapshot == null) {
                LOG.info("restoreLocalSnapshot: no snapshot for branch '$branch'")
                ApplicationManager.getApplication().invokeLater { onComplete?.invoke(false) }
                return@executeOnPooledThread
            }

            // 记录变更前状态，用于回滚
            val prevEnabledModules = _enabledModules.toSet()
            val prevModules = _modules.toList()

            // 乐观更新内存：快照中的 → enable，其余 → disable
            _modules.forEachIndexed { i, m ->
                val newVal = snapshot.contains(m.name)
                if (m.includeBuild != newVal) {
                    _modules[i] = m.copy(includeBuild = newVal)
                }
            }
            _enabledModules = snapshot.intersect(_modules.map { it.name }.toSet()).toMutableSet()

            try {
                saveEnabledModulesToStateFile()
                LOG.info("Restored snapshot for branch '$branch': ${_enabledModules.size} LOCAL modules")
                ApplicationManager.getApplication().invokeLater {
                    notifyListeners()
                    onComplete?.invoke(true)
                }
            } catch (e: Exception) {
                LOG.error("Failed to save state after restore", e)
                // 回滚内存
                _modules.clear()
                _modules.addAll(prevModules)
                _enabledModules = prevEnabledModules.toMutableSet()
                ApplicationManager.getApplication().invokeLater {
                    notifyListeners()
                    onComplete?.invoke(false)
                }
            }
        }
    }

    // ========== 状态文件读写 ==========

    /**
     * 从 .cbm-include-build.json 加载启用状态。
     * 支持新格式（modules 数组）和旧格式（enabledModules 数组）兼容读取。
     * 同时加载 customModules 数组到 _customModuleEntries。
     */
    private fun loadEnabledModulesFromStateFile(): Set<String> {
        val file = stateFile
        if (!file.exists()) {
            LOG.info("State file not found, using empty enabled modules")
            return emptySet()
        }

        return try {
            val content = file.readText()

            // 加载自定义组件：customModules 数组
            val customModulesRe = Regex(""""customModules"\s*:\s*\[([\s\S]*?)\]""")
            val customMatch = customModulesRe.find(content)
            if (customMatch != null) {
                val entryRe = Regex(""""name"\s*:\s*"([^"]+)"[^}]*"path"\s*:\s*"([^"]+)"""")
                _customModuleEntries = entryRe.findAll(customMatch.groupValues[1])
                    .associate { it.groupValues[1] to it.groupValues[2] }
                    .toMutableMap()
                val depsRe = Regex(""""name"\s*:\s*"([^"]+)"[^}]*"deps"\s*:\s*"([^"]+)"""")
                _customModuleDeps = depsRe.findAll(customMatch.groupValues[1])
                    .associate { m ->
                        m.groupValues[1] to com.jdme.cbm.model.DepSubstitution.parseList(m.groupValues[2])
                    }
                    .toMutableMap()
                LOG.info("Loaded ${_customModuleEntries.size} custom modules from state file")
            }

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
     *     { "name": "jm_web_impl", "path": "../jm_web_impl_project", "flavorAware": true }
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

            // 每次保存前从 JSON5 重新读取 flavorAware，避免用户修改配置后内存未刷新
            val latestFlavorMap = if (configFile.exists()) {
                Json5ConfigManager.load(configFile, projectRoot).associate { it.name to it.flavorAware }
            } else emptyMap()

            val enabledList = _modules.filter { _enabledModules.contains(it.name) }

            val sb = StringBuilder()
            sb.appendLine("{")
            sb.appendLine("  \"groupId\": \"$groupId\",")
            sb.appendLine("  \"activeFlavor\": \"${activeFlavor ?: ""}\",")

            // modules 数组
            sb.appendLine("  \"modules\": [")
            enabledList.forEachIndexed { index, module ->
                val comma = if (index < enabledList.size - 1) "," else ""
                val flavorAware = latestFlavorMap[module.name] ?: module.flavorAware
                val parts = mutableListOf("\"name\": \"${module.name}\"")
                if (module.isCustom && module.customPath != null) {
                    parts.add("\"path\": \"${module.customPath}\"")
                }
                if (module.isCustom && module.customDeps.isNotEmpty()) {
                    parts.add("\"deps\": \"${com.jdme.cbm.model.DepSubstitution.toCompactList(module.customDeps)}\"")
                }
                if (flavorAware) {
                    parts.add("\"flavorAware\": true")
                }
                sb.appendLine("    { ${parts.joinToString(", ")} }$comma")
            }
            sb.appendLine("  ],")

            // customModules 数组（手动添加的组件）
            if (_customModuleEntries.isNotEmpty()) {
                sb.appendLine("  \"customModules\": [")
                val customEntries = _customModuleEntries.entries.toList()
                customEntries.forEachIndexed { index, (name, path) ->
                    val comma = if (index < customEntries.size - 1) "," else ""
                    val deps = _customModuleDeps[name]
                    val depsPart = if (!deps.isNullOrEmpty())
                        ", \"deps\": \"${com.jdme.cbm.model.DepSubstitution.toCompactList(deps)}\""
                    else ""
                    sb.appendLine("    { \"name\": \"$name\", \"path\": \"$path\"$depsPart }$comma")
                }
                sb.appendLine("  ],")
            }

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
            // 同步加载分支快照
            loadSnapshotsFromFile()
            // 状态文件即为上次 Sync 后的结果，初始快照与之保持一致，避免重启后按钮误报红
            _syncedEnabledModules = _enabledModules.toSet()

            // 2. 加载 JSON5 配置
            val loaded = if (configFile.exists()) {
                Json5ConfigManager.load(configFile, projectRoot)
            } else {
                LOG.warn("Config file not found: ${configFile.absolutePath}")
                emptyList()
            }

            // 3. 根据状态文件设置模块的 includeBuild 状态
            val jsonModules = loaded.map { module ->
                module.copy(includeBuild = _enabledModules.contains(module.name))
            }

            // 4. 合并自定义组件（跳过与 json5 同名的条目）
            val jsonModuleNames = jsonModules.map { it.name }.toSet()
            val customModules = _customModuleEntries
                .filterKeys { it !in jsonModuleNames }
                .map { (name, path) ->
                    ModuleConfig(
                        name = name,
                        url = "",
                        includeBuild = _enabledModules.contains(name),
                        localDirExists = java.io.File(path).exists(),
                        isCustom = true,
                        customPath = path,
                        customDeps = _customModuleDeps[name] ?: emptyList()
                    )
                }

            _modules = (jsonModules + customModules).toMutableList()

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
     * 手动添加一个本地文件夹作为自定义组件。
     * 组件名取文件夹名，路径为绝对路径，默认 includeBuild=false（MAVEN 状态）。
     * 若同名组件已存在则忽略。
     */
    fun addCustomModule(name: String, path: String, deps: List<com.jdme.cbm.model.DepSubstitution> = emptyList()) {
        if (_modules.any { it.name == name }) {
            LOG.info("addCustomModule: module '$name' already exists, skip")
            return
        }
        _customModuleEntries[name] = path
        if (deps.isNotEmpty()) _customModuleDeps[name] = deps
        // 新添加的自定义组件默认为 MAVEN 模式（includeBuild=false），用户可在侧边面板手动启用
        val newModule = ModuleConfig(
            name = name,
            url = "",
            includeBuild = false,
            localDirExists = java.io.File(path).exists(),
            isCustom = true,
            customPath = path,
            customDeps = deps
        )
        _modules.add(newModule)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                saveEnabledModulesToStateFile()
                // 生成 include_build.gradle 文件
                val includeFile = File(projectRoot, "include_build.gradle")
                IncludeBuildWriter.generate(includeFile, _modules, projectRoot)
                LOG.info("Custom module '$name' added and persisted (path=$path, includeBuild=false)")
            } catch (e: Exception) {
                LOG.error("Failed to save state file or generate include_build.gradle after adding custom module", e)
            }
            ApplicationManager.getApplication().invokeLater { notifyListeners() }
        }
    }

    /**
     * 删除一个手动添加的自定义组件。
     * 从内存和状态文件中移除，并通知 UI 更新。
     */
    fun removeCustomModule(name: String) {
        if (!_customModuleEntries.containsKey(name)) {
            LOG.info("removeCustomModule: '$name' not found in custom entries, skip")
            return
        }
        _customModuleEntries.remove(name)
        _customModuleDeps.remove(name)
        _modules.removeIf { it.name == name && it.isCustom }
        _enabledModules.remove(name)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 1. 保存状态文件
                saveEnabledModulesToStateFile()

                // 2. 生成 include_build.gradle 文件
                val includeFile = File(projectRoot, "include_build.gradle")
                IncludeBuildWriter.generate(includeFile, _modules, projectRoot)
                LOG.info("Custom module '$name' removed and state file saved [include_build.gradle generated]")

                // 3. 重新加载所有模块（包含 JSON5 中的组件）
                loadModules()

                // 4. 触发 Gradle Sync
                ApplicationManager.getApplication().invokeLater {
                    GradleSyncTrigger.sync(project)
                }
            } catch (e: Exception) {
                LOG.error("Failed to remove custom module", e)
                ApplicationManager.getApplication().invokeLater { notifyListeners() }
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
                // 生成 include_build.gradle 文件
                val includeFile = File(projectRoot, "include_build.gradle")
                IncludeBuildWriter.generate(includeFile, _modules, projectRoot)
                LOG.info("$moduleName.includeBuild = $value  [state file saved, include_build.gradle generated]")
                ApplicationManager.getApplication().invokeLater { notifyListeners() }
            } catch (e: Exception) {
                LOG.error("Failed to save state file or generate include_build.gradle", e)
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
                // 生成 include_build.gradle 文件
                val includeFile = File(projectRoot, "include_build.gradle")
                IncludeBuildWriter.generate(includeFile, _modules, projectRoot)
                LOG.info("Batch set ${moduleNames.size} modules to includeBuild=$value [include_build.gradle generated]")
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
