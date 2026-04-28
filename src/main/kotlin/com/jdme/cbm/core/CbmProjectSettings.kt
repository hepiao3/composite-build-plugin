package com.jdme.cbm.core

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "CbmProjectSettings",
    storages = [Storage("composite-build-project.xml")]
)
@Service(Service.Level.PROJECT)
class CbmProjectSettings : PersistentStateComponent<CbmProjectSettings> {

    /** 用户指定的配置文件路径（空串表示使用默认路径） */
    var configFilePath: String = ""

    companion object {
        fun getInstance(project: Project): CbmProjectSettings =
            project.getService(CbmProjectSettings::class.java)
    }

    override fun getState(): CbmProjectSettings = this

    override fun loadState(state: CbmProjectSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
}
