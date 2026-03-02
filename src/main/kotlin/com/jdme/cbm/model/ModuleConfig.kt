package com.jdme.cbm.model

import java.io.File

/**
 * 表示 project-repos.json5 中单个子模块的配置。
 *
 * @param name          模块名称（如 "jm_common"），对应 JSON5 的键名
 * @param url           Git 仓库地址
 * @param branch        目标分支
 * @param includeBuild  是否启用复合构建（对应 JSON5 中的 includeBuild 字段）
 * @param localDirExists 运行时检测：本地目录（../name_project）是否存在
 */
data class ModuleConfig(
    val name: String,
    val url: String,
    val branch: String,
    var includeBuild: Boolean,
    val localDirExists: Boolean
) {
    /** 模块本地目录名：约定为 moduleName_project，位于主工程父目录 */
    val localDirName: String get() = "${name}_project"

    /** 根据 includeBuild 和本地目录是否存在，计算当前构建状态 */
    val status: ModuleStatus
        get() = when {
            includeBuild && localDirExists -> ModuleStatus.LOCAL
            includeBuild && !localDirExists -> ModuleStatus.MISSING
            else -> ModuleStatus.MAVEN
        }
}

/**
 * 模块构建状态枚举。
 *
 * - [LOCAL]   : includeBuild=true 且本地目录存在，使用源码复合构建
 * - [MAVEN]   : includeBuild=false，从 Maven 仓库拉取 AAR
 * - [MISSING] : includeBuild=true 但本地目录不存在，需要先下载
 */
enum class ModuleStatus(val displayName: String, val icon: String) {
    LOCAL("LOCAL", "🔗"),
    MAVEN("MAVEN", "📦"),
    MISSING("未下载", "⚠")
}

/** 根据主工程根目录检测模块本地目录是否存在 */
fun ModuleConfig.checkLocalDir(projectRoot: File): Boolean {
    val parentDir = projectRoot.parentFile ?: return false
    return File(parentDir, localDirName).exists()
}

/** 复制一份并更新 localDirExists（基于最新文件系统状态） */
fun ModuleConfig.withRefreshedDirStatus(projectRoot: File): ModuleConfig =
    copy(localDirExists = checkLocalDir(projectRoot))
