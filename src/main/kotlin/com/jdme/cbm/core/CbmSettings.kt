package com.jdme.cbm.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 插件应用级设置，持久化存储用户自定义的配置文件路径。
 * 当项目根目录找不到 project-repos.json5 时，用户可手动指定路径，
 * 该路径会被记住跨会话使用。
 */
@State(
    name = "CompositeBuildManagerSettings",
    storages = [Storage("composite-build-manager.xml")]
)
class CbmSettings : PersistentStateComponent<CbmSettings> {

    /** 用户手动指定的 project-repos.json5 路径（空串表示使用自动探测） */
    var customConfigPath: String = ""

    companion object {
        fun getInstance(): CbmSettings =
            ApplicationManager.getApplication().getService(CbmSettings::class.java)
    }

    override fun getState(): CbmSettings = this

    override fun loadState(state: CbmSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
}
