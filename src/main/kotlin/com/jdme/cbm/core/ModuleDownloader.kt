package com.jdme.cbm.core

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.jdme.cbm.model.ModuleConfig
import java.io.File
import java.io.InputStream

/**
 * 下载（克隆）缺失的子模块到 ../moduleName_project 目录。
 *
 * 直接调用 `git clone` 完整克隆指定分支，保留所有远程追踪分支引用。
 * 输出流实时打印到 IDE Run 面板（ConsoleView）。
 */
object ModuleDownloader {

    private val LOG = logger<ModuleDownloader>()

    /**
     * 异步下载模块。下载过程的输出会实时显示在提供的 ConsoleView 中。
     *
     * @param module      要下载的模块配置
     * @param project     当前 IntelliJ 项目
     * @param projectRoot 主工程根目录
     * @param console     用于输出的 ConsoleView（可为 null，此时输出到 LOG）
     * @param onComplete  下载完成（成功或失败）的回调，参数为是否成功
     */
    fun download(
        module: ModuleConfig,
        project: Project,
        projectRoot: File,
        console: ConsoleView?,
        onComplete: (success: Boolean) -> Unit
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val success = doDownload(module, projectRoot, console)
            ApplicationManager.getApplication().invokeLater {
                onComplete(success)
            }
        }
    }

    private fun doDownload(
        module: ModuleConfig,
        projectRoot: File,
        console: ConsoleView?
    ): Boolean {
        val parentDir = projectRoot.parentFile ?: run {
            log(console, "❌ 无法确定父目录", error = true)
            return false
        }
        val targetDir = File(parentDir, module.localDirName)
        if (targetDir.exists()) {
            log(console, "✅ ${module.name} 目录已存在，跳过下载")
            return true
        }

        log(console, "📥 开始下载模块: ${module.name}")
        log(console, "   目标目录: ${targetDir.absolutePath}")

        return runProcess(
            cmd = listOf("git", "clone", module.url, targetDir.absolutePath),
            workDir = parentDir,
            console = console
        )
    }

    private fun runProcess(
        cmd: List<String>,
        workDir: File,
        console: ConsoleView?
    ): Boolean {
        return try {
            log(console, "   执行: ${cmd.joinToString(" ")}")
            val proc = ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            // 实时读取并输出进程输出
            streamOutput(proc.inputStream, console)

            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                log(console, "✅ 下载成功（exit code: $exitCode）")
                true
            } else {
                log(console, "❌ 下载失败（exit code: $exitCode）", error = true)
                false
            }
        } catch (e: Exception) {
            log(console, "❌ 执行异常: ${e.message}", error = true)
            LOG.error("Process execution failed", e)
            false
        }
    }

    private fun streamOutput(inputStream: InputStream, console: ConsoleView?) {
        try {
            inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    log(console, line)
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error reading process output", e)
        }
    }

    private fun log(console: ConsoleView?, message: String, error: Boolean = false) {
        if (console != null) {
            val type = if (error) ConsoleViewContentType.ERROR_OUTPUT
                       else ConsoleViewContentType.NORMAL_OUTPUT
            ApplicationManager.getApplication().invokeLater {
                console.print("$message\n", type)
            }
        } else {
            if (error) LOG.warn(message) else LOG.info(message)
        }
    }
}
