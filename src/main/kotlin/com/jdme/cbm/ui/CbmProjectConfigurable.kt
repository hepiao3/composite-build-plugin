package com.jdme.cbm.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.jdme.cbm.CbmBundle
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
            title = CbmBundle.message("settings.config_file.chooser_title")
            description = CbmBundle.message("settings.config_file.chooser_description")
        }
        pathField.addBrowseFolderListener(project, descriptor)

        val defaultHint = JBLabel(CbmBundle.message("settings.config_file.hint"))

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(CbmBundle.message("settings.config_file.label"), pathField)
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
