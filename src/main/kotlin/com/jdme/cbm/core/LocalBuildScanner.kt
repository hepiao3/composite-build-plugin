package com.jdme.cbm.core

import java.io.File

object LocalBuildScanner {

    data class ProjectEntry(val name: String, val gradlePath: String)

    data class ScanResult(
        val groupId: String?,
        val subProjects: List<String>
    ) {
        val allProjects: List<ProjectEntry> by lazy {
            subProjects.map { path ->
                // name = 路径，gradlePath = 不需要冒号
                ProjectEntry(path, path)
            }
        }
    }

    /** 校验是否为有效 Gradle 项目。null=有效，非 null=错误信息 */
    fun validate(buildDir: File): String? {
        if (!buildDir.exists() || !buildDir.isDirectory) return "目录不存在：${buildDir.absolutePath}"
        val hasSettings = listOf("settings.gradle", "settings.gradle.kts").any { File(buildDir, it).exists() }
        return if (hasSettings) null else "所选目录不是有效的 Gradle 项目\n（未找到 settings.gradle 或 settings.gradle.kts）"
    }

    fun scan(buildDir: File): ScanResult {
        val settingsFile = listOf("settings.gradle", "settings.gradle.kts")
            .map { File(buildDir, it) }.firstOrNull { it.exists() }

        val subProjects = mutableSetOf<String>()

        settingsFile?.forEachLine { line ->
            // 匹配 include(":path") 或 include ":path"
            Regex("""include\s*\(?\s*['"]([^'"]+)['"]""").find(line)?.let { m ->
                // 去掉前导冒号
                val path = m.groupValues[1].removePrefix(":")
                if (path.isNotBlank()) subProjects.add(path)
            }
        }

        // 过滤掉 app 级别的 module（根目录有 AndroidManifest.xml）
        val filtered = subProjects.filter { path ->
            !hasApplicationTag(buildDir, path)
        }

        return ScanResult(
            groupId = readGroupId(buildDir),
            subProjects = filtered.toList().sorted()
        )
    }

    /** 检查指定 module 的 AndroidManifest.xml 中是否含有 <application 标签（app module 特征） */
    private fun hasApplicationTag(buildDir: File, modulePath: String): Boolean {
        val parts = modulePath.split(":")
        var dir = buildDir
        for (part in parts) {
            dir = File(dir, part)
        }
        val manifest = File(dir, "src/main/AndroidManifest.xml")
        if (!manifest.exists()) return false
        val content = manifest.readText()
        return "<application" in content && "android.intent.category.LAUNCHER" in content
    }

    private fun readGroupId(buildDir: File): String? {
        File(buildDir, "gradle.properties").takeIf { it.exists() }?.readLines()
            ?.firstNotNullOfOrNull { line ->
                Regex("""^\s*[Gg][Rr][Oo][Uu][Pp]\s*=\s*(\S+)""").find(line)?.groupValues?.get(1)?.trim()
            }?.let { return it }
        return listOf("build.gradle.kts", "build.gradle").map { File(buildDir, it) }
            .firstOrNull { it.exists() }?.readLines()
            ?.firstNotNullOfOrNull { line ->
                Regex("""^\s*group\s*=\s*["']([^"']+)["']""").find(line)?.groupValues?.get(1)?.trim()
            }
    }
}