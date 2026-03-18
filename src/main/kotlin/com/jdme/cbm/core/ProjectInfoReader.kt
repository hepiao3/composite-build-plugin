package com.jdme.cbm.core

import com.intellij.openapi.diagnostic.logger
import java.io.File

/**
 * 从主工程的 app/build.gradle 或 app/build.gradle.kts 中读取：
 * - applicationId / namespace（作为 Maven groupId）
 */
object ProjectInfoReader {

    private val LOG = logger<ProjectInfoReader>()

    // Groovy DSL:  applicationId "com.example" 或 applicationId 'com.example'
    // KTS DSL:     applicationId = "com.example"
    private val APP_ID_RE = Regex("""applicationId\s*=?\s*['"]([^'"]+)['"]""")

    // Groovy DSL:  namespace "com.example" 或 namespace 'com.example'
    // KTS DSL:     namespace = "com.example"
    private val NAMESPACE_RE = Regex("""namespace\s*=?\s*['"]([^'"]+)['"]""")

    /**
     * 读取主工程的 applicationId 或 namespace 作为 groupId。
     * 优先读 applicationId，若不存在则回退到 namespace。
     */
    fun readGroupId(projectRoot: File): String? {
        val buildFile = findAppBuildFile(projectRoot) ?: return null
        val content = buildFile.readText()
        return APP_ID_RE.find(content)?.groupValues?.get(1)
            ?: NAMESPACE_RE.find(content)?.groupValues?.get(1)
    }

    private fun findAppBuildFile(projectRoot: File): File? {
        val kts = File(projectRoot, "app/build.gradle.kts")
        if (kts.exists()) return kts
        val groovy = File(projectRoot, "app/build.gradle")
        if (groovy.exists()) return groovy
        LOG.warn("app/build.gradle not found under ${projectRoot.absolutePath}")
        return null
    }
}
