package com.jdme.cbm.core

import com.android.tools.idea.gradle.project.model.GradleAndroidModel
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

/**
 * 读取 Android Studio 中 :app 模块当前选中的 Build Variant，
 * 并从中提取 productFlavor 名称。
 *
 * 依赖：org.jetbrains.android（Android Studio 内置插件）
 * 核心 API：GradleAndroidModel（AGP 7.4+ / Android Studio Giraffe+）
 */
object BuildVariantReader {

    private val LOG = logger<BuildVariantReader>()

    /**
     * 获取 :app 模块当前激活的第一个 flavor 名称（如 "me"、"global"）。
     *
     * 例：selectedVariant = "meOfficialDebug"
     *     productFlavors  = ["me", "official"]
     *     返回 "me"（第一个 flavor 维度，即 artifact 中的标识）
     *
     * @param project 当前 IntelliJ 项目
     * @return flavor 名称，读取失败时返回 null
     */
    fun getActiveFlavor(project: Project): String? {
        val moduleManager = ModuleManager.getInstance(project)

        // 找 app 模块（名称可能是 "app" 或 "projectName.app"）
        val appModule = moduleManager.modules.firstOrNull { module ->
            module.name == "app" || module.name.endsWith(".app")
        }
        if (appModule == null) {
            LOG.warn("BuildVariantReader: 未找到 app 模块")
            return null
        }

        val model = GradleAndroidModel.get(appModule)
        if (model == null) {
            LOG.warn("BuildVariantReader: GradleAndroidModel 未就绪（Gradle Sync 未完成？）")
            return null
        }

        val variant = model.selectedVariant
        // productFlavors 来自 IdeVariantHeader，返回当前变体的所有 flavor 名称列表
        val flavor = variant.productFlavors.firstOrNull()
        LOG.info("BuildVariantReader: variant=${variant.name}, productFlavors=${variant.productFlavors}, activeFlavor=$flavor")
        return flavor
    }
}
