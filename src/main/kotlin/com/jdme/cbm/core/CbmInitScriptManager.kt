package com.jdme.cbm.core

import com.intellij.openapi.diagnostic.logger
import java.io.File

/**
 * 管理 CBM 的 Gradle 基础设施文件：
 * - ~/.gradle/init.d/cbm.gradle：Gradle init script，自动处理 includeBuild 配置
 * - ~/.gradle/cbm/<hash>.json：各工程的状态文件（替代原来工程目录下的 .cbm-include-build.json）
 *
 * hash 由工程根目录绝对路径的 hashCode 计算，与 init script 中的算法一致（均使用 JVM Integer.toUnsignedString）。
 */
object CbmInitScriptManager {

    private val LOG = logger<CbmInitScriptManager>()

    private const val INIT_SCRIPT_RESOURCE = "/gradle/cbm.init.gradle"
    private const val INIT_SCRIPT_NAME = "cbm.gradle"

    /** 根据工程根目录计算对应的状态文件路径 */
    fun stateFileFor(projectRoot: File): File {
        val hash = Integer.toUnsignedString(projectRoot.absolutePath.hashCode(), 16)
        val dir = File(System.getProperty("user.home"), ".gradle/cbm")
        dir.mkdirs()
        return File(dir, "$hash.json")
    }

    /**
     * 将插件 JAR 内的 cbm.init.gradle 部署到 ~/.gradle/init.d/cbm.gradle。
     * 仅在内容变化时写入，避免触发不必要的 Gradle 缓存失效。
     */
    fun deployInitScript() {
        try {
            val resource = CbmInitScriptManager::class.java
                .getResourceAsStream(INIT_SCRIPT_RESOURCE)
                ?: run {
                    LOG.warn("Init script resource not found: $INIT_SCRIPT_RESOURCE")
                    return
                }
            val content = resource.use { it.readBytes() }
            val targetDir = File(System.getProperty("user.home"), ".gradle/init.d")
            targetDir.mkdirs()
            val targetFile = File(targetDir, INIT_SCRIPT_NAME)
            if (!targetFile.exists() || !targetFile.readBytes().contentEquals(content)) {
                targetFile.writeBytes(content)
                LOG.info("Deployed CBM init script to: ${targetFile.absolutePath}")
            }
        } catch (e: Exception) {
            LOG.error("Failed to deploy CBM init script", e)
        }
    }

    /**
     * 将旧版状态文件（工程根目录下的 .cbm-include-build.json）迁移到新路径，并删除旧文件。
     */
    fun migrateOldStateFile(projectRoot: File) {
        val oldFile = File(projectRoot, ".cbm-include-build.json")
        if (!oldFile.exists()) return
        val newFile = stateFileFor(projectRoot)
        try {
            if (!newFile.exists()) {
                oldFile.copyTo(newFile)
                LOG.info("Migrated state file: ${oldFile.absolutePath} -> ${newFile.absolutePath}")
            }
            oldFile.delete()
            LOG.info("Deleted old state file: ${oldFile.absolutePath}")
        } catch (e: Exception) {
            LOG.error("Failed to migrate state file", e)
        }
    }
}
