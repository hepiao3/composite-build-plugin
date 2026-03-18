package com.jdme.cbm.core

import com.intellij.openapi.diagnostic.logger
import java.io.File

/**
 * 从主工程的 app/build.gradle 或 app/build.gradle.kts 中读取：
 * - applicationId / namespace（作为 Maven groupId）
 * - productFlavors 列表
 */
object ProjectInfoReader {

    private val LOG = logger<ProjectInfoReader>()

    // Groovy DSL:  applicationId "com.example"
    // KTS DSL:     applicationId = "com.example"
    private val APP_ID_RE = Regex("""applicationId\s*=?\s*"([^"]+)"""")

    // Groovy DSL:  namespace "com.example"
    // KTS DSL:     namespace = "com.example"
    private val NAMESPACE_RE = Regex("""namespace\s*=?\s*"([^"]+)"""")

    // Groovy: create("me") { } 或直接  me { }
    // 识别 productFlavors 块内的 flavor 名称
    private val FLAVOR_CREATE_RE = Regex("""create\s*\(\s*"(\w+)"\s*\)""")
    private val FLAVOR_BLOCK_RE = Regex("""^\s*(\w+)\s*\{""")

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

    /**
     * 读取主工程的 productFlavors 列表。
     * 支持 Groovy DSL（`me { }`）和 KTS DSL（`create("me") { }`）。
     */
    fun readProductFlavors(projectRoot: File): List<String> {
        val buildFile = findAppBuildFile(projectRoot) ?: return emptyList()
        val lines = buildFile.readLines()

        var inProductFlavors = false
        var depth = 0
        val flavors = mutableListOf<String>()

        // 跳过 productFlavors 块之外的内容
        val skipWords = setOf(
            "android", "defaultConfig", "buildTypes", "compileOptions",
            "kotlinOptions", "signingConfigs", "dependencies", "buildFeatures"
        )

        for (line in lines) {
            val trimmed = line.trim()

            if (!inProductFlavors) {
                if (trimmed.startsWith("productFlavors")) {
                    inProductFlavors = true
                    depth = 0
                }
                continue
            }

            // 统计花括号层级
            depth += line.count { it == '{' } - line.count { it == '}' }

            if (depth <= 0) {
                // productFlavors 块结束
                break
            }

            // 只在第一层内解析 flavor 名称
            if (depth == 1) {
                // KTS: create("me")
                FLAVOR_CREATE_RE.find(trimmed)?.let {
                    flavors += it.groupValues[1]
                    return@let
                }
                // Groovy: me {
                FLAVOR_BLOCK_RE.find(trimmed)?.let { match ->
                    val candidate = match.groupValues[1]
                    if (candidate !in skipWords) {
                        flavors += candidate
                    }
                }
            }
        }

        LOG.info("Read product flavors from ${buildFile.name}: $flavors")
        return flavors
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
