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
        val suggestedDep = depMatch?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }

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
}
