package com.jdme.cbm.actions

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.jdme.cbm.core.CbmProjectService
import com.jdme.cbm.core.LocalBuildScanner
import com.jdme.cbm.ui.DepSelectionDialog
import java.io.File
import java.util.function.Supplier

/**
 * 在 .gradle / .gradle.kts 文件的依赖声明行左侧 gutter 显示「+」按钮。
 * 点击后：选择本地组件目录 → 扫描模块 → DepSelectionDialog 填写 Group ID + 勾选模块 → addCustomModule。
 */
class CbmLineMarkerProvider : LineMarkerProvider {

    private val depKeywords = setOf(
        "implementation", "api", "compileOnly", "runtimeOnly",
        "testImplementation", "testCompileOnly", "testRuntimeOnly",
        "debugImplementation", "releaseImplementation",
        "annotationProcessor", "kapt", "ksp"
    )

    // 匹配 'group:artifact[:version]' 格式，只提取 group:artifact（不含版本）
    private val stringDepPattern = Regex("""['"]([A-Za-z][\w.\-]*):([A-Za-z][\w.\-]*)(?::[\w.\-]+)?['"]""")

    // 匹配 libs.xxx 格式（Version Catalog 引用）
    private val libsDepPattern = Regex("""(?<![.\w])libs\.([A-Za-z][\w.]*)""")

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 只处理叶子节点（token），保证每行只出现一个 marker
        if (element.firstChild != null) return null
        if (element.text !in depKeywords) return null

        val file = element.containingFile ?: return null
        val fileName = file.name
        if (!fileName.endsWith(".gradle") && !fileName.endsWith(".gradle.kts")) return null

        // 从当前行解析 groupId，用于预填到 DepSelectionDialog
        val doc = PsiDocumentManager.getInstance(element.project).getDocument(file)
        val lineText = doc?.let {
            val lineNum = it.getLineNumber(element.textOffset)
            it.getText(TextRange(it.getLineStartOffset(lineNum), it.getLineEndOffset(lineNum)))
        }
        val depMatch = lineText?.let { stringDepPattern.find(it) }
        val suggestedDep = if (depMatch != null) {
            "${depMatch.groupValues[1]}:${depMatch.groupValues[2]}"
        } else {
            // 尝试解析 Version Catalog 格式：libs.xxx
            lineText?.let { libsDepPattern.find(it) }?.let { libsMatch ->
                val alias = libsMatch.groupValues[1]
                val projectRoot = File(element.project.basePath ?: "")
                resolveFromVersionCatalog(projectRoot, alias)
            }
        }

        val tooltip = if (suggestedDep != null)
            "Add $suggestedDep to CBM Composite Build…"
        else
            "Add to CBM Composite Build…"

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.General.Add,
            { _: PsiElement -> tooltip },
            { _, elem ->
                val project = elem.project
                val service = CbmProjectService.getInstance(project)
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
                    title = "选择本地组件文件夹"
                }
                FileChooser.chooseFile(descriptor, project, null) { vFile ->
                    val buildDir = File(vFile.path)
                    val error = LocalBuildScanner.validate(buildDir)
                    if (error != null) {
                        Messages.showErrorDialog(project, error, "无效的 Gradle 项目")
                        return@chooseFile
                    }
                    val scanResult = LocalBuildScanner.scan(buildDir)
                    if (scanResult.allProjects.isEmpty()) {
                        Messages.showErrorDialog(project, "在 settings.gradle 中未检测到任何可用模块", "扫描失败")
                        return@chooseFile
                    }
                    val dialog = DepSelectionDialog(project, vFile.name, scanResult, service, suggestedDep)
                    if (dialog.showAndGet()) {
                        service.addCustomModule(vFile.name, vFile.path, dialog.getSelectedDeps())
                    }
                }
            },
            GutterIconRenderer.Alignment.LEFT,
            Supplier { "Add to CBM Composite Build…" }
        )
    }

    /**
     * 从 gradle/libs.versions.toml 中解析 Version Catalog 别名对应的 group:artifact。
     *
     * 别名转 TOML key 规则：点替换为连字符（libs.greendao → greendao，libs.androidx.core.ktx → androidx-core-ktx）。
     * TOML 中 key 的点、连字符、下划线均视为等价分隔符。
     *
     * 支持两种格式：
     * - 字符串格式：`greendao = "org.greenrobot:greendao:3.3.0"`
     * - 对象格式：`greendao = { module = "org.greenrobot:greendao", version.ref = "..." }`
     */
    private fun resolveFromVersionCatalog(projectRoot: File, alias: String): String? {
        val tomlFile = File(projectRoot, "gradle/libs.versions.toml")
        if (!tomlFile.exists()) return null

        val content = tomlFile.readText()

        // 找到 [libraries] 段落
        val librariesSection = Regex("""\[libraries\]([\s\S]*?)(?=\[|\z)""").find(content)
            ?.groupValues?.get(1) ?: return null

        // 将 libs.xxx 中的点转为连字符，与 TOML key 规范对齐
        val normalizedAlias = alias.replace('.', '-').lowercase()

        for (line in librariesSection.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue

            val rawKey = trimmed.substring(0, eqIdx).trim()
            // TOML key 中 ./-/_ 均视为等价
            val normalizedKey = rawKey.replace('.', '-').replace('_', '-').lowercase()
            if (normalizedKey != normalizedAlias) continue

            val value = trimmed.substring(eqIdx + 1).trim()

            // 对象格式：{ module = "group:artifact", ... }
            val moduleMatch = Regex("""module\s*=\s*"([^"]+)"""").find(value)
            if (moduleMatch != null) return moduleMatch.groupValues[1]

            // 字符串格式："group:artifact:version" 或 "group:artifact"
            val stringMatch = Regex(""""([^"]+)"""").find(value)
            if (stringMatch != null) {
                val dep = stringMatch.groupValues[1]
                val parts = dep.split(":")
                if (parts.size >= 2) return "${parts[0]}:${parts[1]}"
            }
        }

        return null
    }
}
