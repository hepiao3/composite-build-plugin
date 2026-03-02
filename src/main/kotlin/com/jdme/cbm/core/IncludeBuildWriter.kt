package com.jdme.cbm.core

import com.intellij.openapi.diagnostic.logger
import com.jdme.cbm.model.ModuleConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 根据模块配置列表生成 include_build.gradle 文件。
 *
 * 该文件由 settings.gradle 通过 `apply from: 'include_build.gradle'` 引入，
 * 包含所有启用了复合构建（includeBuild=true 且本地目录存在）的模块的 includeBuild 声明。
 */
object IncludeBuildWriter {

    private val LOG = logger<IncludeBuildWriter>()
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 根据配置列表重新生成 include_build.gradle。
     *
     * @param outputFile   目标文件（通常为 projectRoot/include_build.gradle）
     * @param modules      全量模块配置列表
     * @param projectRoot  主工程根目录（用于计算相对路径）
     */
    fun generate(outputFile: File, modules: List<ModuleConfig>, projectRoot: File) {
        val localModules = modules.filter { it.includeBuild && it.localDirExists }
        val timestamp = LocalDateTime.now().format(DATE_FMT)
        val projectParent = projectRoot.parentFile

        val sb = StringBuilder()
        sb.appendLine("// ============================================")
        sb.appendLine("// == Composite Build Configuration ==")
        sb.appendLine("// ============================================")
        sb.appendLine("// 此文件由 Composite Build Manager 插件自动生成")
        sb.appendLine("// 生成时间: $timestamp")
        sb.appendLine("// 请勿手动修改，通过插件面板或修改 project-repos.json5 后重新生成")
        sb.appendLine("// ============================================")
        sb.appendLine()

        if (localModules.isEmpty()) {
            sb.appendLine("// 当前没有配置任何复合构建项目")
            sb.appendLine("// 在 project-repos.json5 中设置 \"includeBuild\": true 来启用复合构建")
        } else {
            sb.appendLine("// 当前启用了 ${localModules.size} 个复合构建项目：")
            localModules.forEach { sb.appendLine("//   - ${it.name}") }
            sb.appendLine()

            for (module in localModules) {
                val localDir = if (projectParent != null) {
                    File(projectParent, module.localDirName)
                } else {
                    File("../${module.localDirName}")
                }
                // 使用相对于主工程根目录的路径
                val relativePath = try {
                    projectRoot.toPath().relativize(localDir.toPath()).toString()
                } catch (e: Exception) {
                    localDir.absolutePath
                }
                sb.appendLine("// ${module.name} - branch: ${module.branch}")
                sb.appendLine("includeBuild('$relativePath')")
                sb.appendLine()
            }
        }

        outputFile.writeText(sb.toString())
        LOG.info("Generated include_build.gradle with ${localModules.size} modules")
    }
}
