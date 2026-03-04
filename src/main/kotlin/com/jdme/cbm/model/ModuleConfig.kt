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
    LOCAL("LOCAL", ""),
    MAVEN("MAVEN", ""),
    MISSING("未下载", "")
}

/** 根据主工程根目录检测模块本地目录是否存在 */
fun ModuleConfig.checkLocalDir(projectRoot: File): Boolean {
    val parentDir = projectRoot.parentFile ?: return false
    return File(parentDir, localDirName).exists()
}

/** 复制一份并更新 localDirExists（基于最新文件系统状态） */
fun ModuleConfig.withRefreshedDirStatus(projectRoot: File): ModuleConfig =
    copy(localDirExists = checkLocalDir(projectRoot))

/** 获取本地 Git 仓库的当前分支（如果本地目录存在） */
fun ModuleConfig.getLocalGitBranch(projectRoot: File): String? {
    if (!localDirExists) return null
    val parentDir = projectRoot.parentFile ?: return null
    val localDir = File(parentDir, localDirName)
    return getGitBranch(localDir)
}

/** 执行 git 命令获取当前分支 */
private fun getGitBranch(dir: File): String? {
    if (!dir.isDirectory) return null
    try {
        val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            .directory(dir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val result = process.inputStream.bufferedReader().readText().trim()
        return if (process.waitFor() == 0 && result.isNotEmpty()) result else null
    } catch (e: Exception) {
        return null
    }
}

/**
 * 获取本地仓库的所有分支（本地分支 + 远程分支）
 * 本地分支通过 git branch 获取，远程分支通过 git ls-remote 获取
 */
fun ModuleConfig.getAllBranches(projectRoot: File): List<String> {
    if (!localDirExists) return emptyList()
    val parentDir = projectRoot.parentFile ?: return emptyList()
    val localDir = File(parentDir, localDirName)
    if (!localDir.isDirectory) return emptyList()

    try {
        // 先执行 git fetch 更新远程引用（可选，不强制要求成功）
        ProcessBuilder("git", "fetch", "--all")
            .directory(localDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val allBranches = mutableSetOf<String>()

        // 获取本地分支
        val localProcess = ProcessBuilder("git", "branch")
            .directory(localDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val localOutput = localProcess.inputStream.bufferedReader().readText()
        if (localProcess.waitFor() == 0) {
            localOutput.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                // 去掉 * 前缀（当前分支标记）和 HEAD -> remote/branch 引用
                .map { if (it.startsWith("* ")) it.substring(2) else it }
                .filter { !it.startsWith("HEAD ->") }
                .forEach { allBranches.add(it) }
        }

        // 获取远程分支
        val remoteProcess = ProcessBuilder("git", "ls-remote", "--heads", url)
            .directory(localDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val remoteOutput = remoteProcess.inputStream.bufferedReader().readText()
        if (remoteProcess.waitFor() == 0) {
            // 解析格式：<commit> refs/heads/<branchName>
            remoteOutput.lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    val parts = line.split("\t")
                    if (parts.size == 2) parts[1].removePrefix("refs/heads/") else null
                }
                .filterNotNull()
                .forEach { allBranches.add(it) }
        }

        return allBranches.distinct().sorted().toList()
    } catch (e: Exception) {
        return emptyList()
    }
}

/**
 * 检查本地仓库是否有未提交的修改
 * @return true 表示有未提交修改，false 表示干净
 */
fun ModuleConfig.hasUncommittedChanges(projectRoot: File): Boolean {
    if (!localDirExists) return false
    val parentDir = projectRoot.parentFile ?: return false
    val localDir = File(parentDir, localDirName)
    if (!localDir.isDirectory) return false
    return try {
        val process = ProcessBuilder("git", "status", "--porcelain")
            .directory(localDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor() == 0 && output.isNotEmpty()
    } catch (e: Exception) {
        false
    }
}

/**
 * 切换到指定分支
 * @return 切换成功返回 null，失败返回错误信息字符串
 */
fun ModuleConfig.checkoutBranch(projectRoot: File, branchName: String): String? {
    if (!localDirExists) return "本地目录不存在"
    val parentDir = projectRoot.parentFile ?: return "无法获取父目录"
    val localDir = File(parentDir, localDirName)
    if (!localDir.isDirectory) return "本地目录不存在"

    return try {
        val process = ProcessBuilder("git", "checkout", branchName)
            .directory(localDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) null else output.ifEmpty { "未知错误" }
    } catch (e: Exception) {
        e.message ?: "未知错误"
    }
}
