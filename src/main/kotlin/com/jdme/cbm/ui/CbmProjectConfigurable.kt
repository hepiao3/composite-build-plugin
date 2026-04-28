package com.jdme.cbm.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.jdme.cbm.core.CbmProjectService
import com.jdme.cbm.core.CbmProjectSettings
import javax.swing.JComponent
import javax.swing.JPanel

class CbmProjectConfigurable(private val project: Project) : Configurable {

    private lateinit var pathField: TextFieldWithBrowseButton

    override fun getDisplayName(): String = "Composite Build Manager"

    override fun createComponent(): JComponent {
        pathField = TextFieldWithBrowseButton()
        val descriptor = FileChooserDescriptor(
            /* chooseFiles = */ true,
            /* chooseFolders = */ false,
            /* chooseJars = */ false,
            /* chooseJarsAsFiles = */ false,
            /* chooseJarContents = */ false,
            /* chooseMultiple = */ false
        ).apply {
            title = "选择配置文件"
            description = "选择 project-repos.json5 或同格式的 JSON5 文件"
        }
        pathField.addBrowseFolderListener(project, descriptor)

        val defaultHint = JBLabel(
            "<html><font color='gray'>留空则使用默认路径：&lt;项目根目录&gt;/project-repos.json5</font></html>"
        )

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("配置文件路径：", pathField)
            .addComponent(defaultHint)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean =
        pathField.text.trim() != CbmProjectSettings.getInstance(project).configFilePath

    override fun apply() {
        CbmProjectSettings.getInstance(project).configFilePath = pathField.text.trim()
        refreshToolWindow()
    }

    private fun refreshToolWindow() {
        CbmProjectService.getInstance(project).loadModules()
    }

    override fun reset() {
        pathField.text = CbmProjectSettings.getInstance(project).configFilePath
    }
}
