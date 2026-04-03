package com.jdme.cbm.core

import com.intellij.openapi.diagnostic.logger
import com.jdme.cbm.model.ModuleConfig
import com.jdme.cbm.model.checkLocalDir
import java.io.File

/**
 * 解析和写回 project-repos.json5。
 *
 * 采用正则 + 行扫描策略：
 * - 不依赖第三方 JSON5 库，避免插件体积膨胀
 * - 保留所有注释、空行、原始格式
 * - 仅精确替换目标字段的值
 *
 * JSON5 文件结构（固定格式）：
 * ```
 * {
 *   "repositories": {
 *     "moduleName": {
 *       "url": "...",
 *       "includeBuild": false
 *     }, // comment
 *     ...
 *   }
 * }
 * ```
 */
object Json5ConfigManager {

    private val LOG = logger<Json5ConfigManager>()

    // 匹配模块名键：  "moduleName": {
    private val MODULE_KEY_RE = Regex("""^\s*"([\w-]+)"\s*:\s*\{""")
    // 匹配 url 字段
    private val URL_RE = Regex("""^\s*"url"\s*:\s*"([^"]+)"""")
    // 匹配 includeBuild 字段
    private val INCLUDE_BUILD_RE = Regex("""^\s*"includeBuild"\s*:\s*(true|false)""")
    // 匹配 flavorAware 字段
    private val FLAVOR_RE = Regex("""^\s*"flavorAware"\s*:\s*(true|false)""")
    // 匹配块结束行（考虑 trailing comma）
    private val BLOCK_END_RE = Regex("""^\s*},?\s*(//.+)?$""")
    // 匹配 "repositories": { 行
    private val REPOS_START_RE = Regex("""^\s*"repositories"\s*:\s*\{""")

    /**
     * 从 JSON5 文件加载所有模块配置。
     *
     * @param configFile project-repos.json5 文件
     * @param projectRoot 主工程根目录（用于检测本地目录）
     * @return 按文件顺序排列的模块配置列表
     */
    fun load(configFile: File, projectRoot: File): List<ModuleConfig> {
        if (!configFile.exists()) {
            LOG.warn("Config file not found: ${configFile.absolutePath}")
            return emptyList()
        }

        val lines = configFile.readLines()
        val modules = mutableListOf<ModuleConfig>()

        var inRepositories = false
        var currentName: String? = null
        var currentUrl = ""
        var currentIncludeBuild = false
        var currentFlavor = false

        for (line in lines) {
            // 跳过注释行
            val trimmed = line.trim()
            if (trimmed.startsWith("//")) continue

            // 检测进入 repositories 块
            if (!inRepositories && REPOS_START_RE.containsMatchIn(line)) {
                inRepositories = true
                continue
            }

            if (!inRepositories) continue

            // 检测模块键开始
            MODULE_KEY_RE.find(line)?.let { match ->
                currentName = match.groupValues[1]
                currentUrl = ""
                currentIncludeBuild = false
                currentFlavor = false
                return@let
            }

            // 检测 repositories 块结束（currentName == null 时遇到 } 说明是外层块关闭）
            if (currentName == null) {
                if (BLOCK_END_RE.matches(line)) inRepositories = false
                continue
            }

            // 解析字段
            URL_RE.find(line)?.let { currentUrl = it.groupValues[1] }
            INCLUDE_BUILD_RE.find(line)?.let { currentIncludeBuild = it.groupValues[1] == "true" }
            FLAVOR_RE.find(line)?.let { currentFlavor = it.groupValues[1] == "true" }

            // 检测模块块结束
            if (BLOCK_END_RE.matches(line)) {
                @Suppress("SENSELESS_COMPARISON")
                val name = currentName!! // currentName 已在前面检查非空
                val localExists = checkLocalDirExists(projectRoot, name)
                modules += ModuleConfig(
                    name = name,
                    url = currentUrl,
                    includeBuild = currentIncludeBuild,
                    localDirExists = localExists,
                    flavorAware = currentFlavor
                )
                currentName = null
            }
        }

        LOG.info("Loaded ${modules.size} modules from ${configFile.name}")
        return modules
    }

    /**
     * 将单个模块的 includeBuild 值写回 JSON5 文件。
     * 精确替换目标模块块内的 "includeBuild" 行，保留所有其他内容不变。
     *
     * @param configFile 目标 JSON5 文件
     * @param moduleName 要修改的模块名
     * @param value      新的 includeBuild 值
     */
    fun setIncludeBuild(configFile: File, moduleName: String, value: Boolean) {
        val lines = configFile.readLines().toMutableList()
        var inTargetModule = false
        var moduleFound = false
        val targetModuleRe = Regex("""^\s*"${Regex.escape(moduleName)}"\s*:\s*\{""")

        for (i in lines.indices) {
            val line = lines[i]

            if (!inTargetModule) {
                if (targetModuleRe.containsMatchIn(line)) {
                    inTargetModule = true
                    moduleFound = true
                }
                continue
            }

            // 在目标模块块内，找 includeBuild 行并替换
            if (INCLUDE_BUILD_RE.containsMatchIn(line)) {
                val newLine = line.replace(
                    Regex("""(includeBuild"\s*:\s*)(true|false)"""),
                    "\$1$value"
                )
                lines[i] = newLine
                LOG.info("Updated $moduleName.includeBuild = $value")
                break
            }

            // 遇到块结束，说明该模块没有 includeBuild 字段
            if (BLOCK_END_RE.matches(line)) {
                LOG.warn("Module $moduleName has no includeBuild field")
                break
            }
        }

        if (!moduleFound) {
            LOG.error("Module $moduleName not found in ${configFile.name}")
            return
        }

        configFile.writeText(lines.joinToString("\n"))
    }

    /**
     * 检测模块本地目录是否存在（目录约定：../moduleName_project）
     */
    private fun checkLocalDirExists(projectRoot: File, moduleName: String): Boolean {
        val parentDir = projectRoot.parentFile ?: return false
        return File(parentDir, "${moduleName}_project").exists()
    }
}
