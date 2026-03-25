package com.jdme.cbm.core

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 项目级持久化服务，存储用户在面板中手动选择的分支信息。
 * 映射关系：moduleName -> branchName
 * 持久化到 .idea/cbm-branch-store.xml。
 */
@State(name = "CbmBranchStore", storages = [Storage("cbm-branch-store.xml")])
class CbmBranchStore : PersistentStateComponent<CbmBranchStore> {

    var branchMap: MutableMap<String, String> = HashMap()

    fun getBranch(moduleName: String): String? = branchMap[moduleName]

    fun setBranch(moduleName: String, branch: String) {
        branchMap[moduleName] = branch
    }

    companion object {
        fun getInstance(project: Project): CbmBranchStore =
            project.getService(CbmBranchStore::class.java)
    }

    override fun getState() = this

    override fun loadState(state: CbmBranchStore) {
        XmlSerializerUtil.copyBean(state, this)
    }
}
