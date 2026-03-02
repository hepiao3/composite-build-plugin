package com.jdme.cbm.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.jdme.cbm.core.GradleSyncTrigger

/**
 * 右键菜单 Action：立即触发 Gradle Sync。
 */
class SyncGradleAction : AnAction("Sync Gradle Now") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        GradleSyncTrigger.sync(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
