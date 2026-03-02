package com.jdme.cbm.core

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants

/**
 * 触发 Gradle Sync（重新加载项目）。
 *
 * 通过 IntelliJ Platform 的 ExternalSystemUtil API 触发，
 * 效果等同于点击 Android Studio 工具栏上的 "Sync Project with Gradle Files"。
 *
 * ImportSpecBuilder 是 ExternalSystemUtil 的静态内部类，
 * refreshProjects 可直接接受 builder 实例（无需调用 .build()）。
 */
object GradleSyncTrigger {

    private val LOG = logger<GradleSyncTrigger>()

    /**
     * 触发 Gradle 同步。在调用前，应确保 include_build.gradle 已更新。
     *
     * @param project 当前 IntelliJ 项目
     */
    fun sync(project: Project) {
        LOG.info("Triggering Gradle sync for project: ${project.name}")
        try {
            ExternalSystemUtil.refreshProjects(
                ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                    .forceWhenUptodate(true)
            )
        } catch (e: Exception) {
            LOG.error("Failed to trigger Gradle sync", e)
        }
    }
}
