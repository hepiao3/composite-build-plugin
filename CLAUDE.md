# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

这是一个 JetBrains/IntelliJ 平台插件，用于管理 Android 多模块复合构建（Composite Build）配置。通过侧边面板可视化展示所有子模块的 LOCAL/MAVEN/MISSING 状态，支持一键切换 includeBuild 模式并自动触发 Gradle Sync。

## 常用命令

```bash
# 构建插件
./gradlew buildPlugin

# 运行测试
./gradlew test

# 清理构建
./gradlew clean

# 运行插件（验证）
./gradlew runIde
```

构建产物位于：`build/distributions/composite-build-plugin-*.zip`

## 项目结构

```
src/main/kotlin/com/jdme/cbm/
├── core/                    # 核心业务逻辑
│   ├── CbmProjectService.kt   # 项目级服务，协调各组件
│   ├── Json5ConfigManager.kt  # 解析/写回 project-repos.json5
│   ├── IncludeBuildWriter.kt  # 生成 include_build.gradle
│   ├── GradleSyncTrigger.kt   # 触发 Gradle Sync
│   └── CbmSettings.kt         # 插件级设置（持久化）
├── model/
│   └── ModuleConfig.kt         # 模块配置数据模型
├── ui/
│   ├── CompositeBuildToolWindow.kt  # 侧边面板入口
│   └── ModuleListPanel.kt            # 模块列表 UI
└── actions/
    ├── SwitchBuildModeAction.kt      # 右键菜单：切换构建模式
    └── SyncGradleAction.kt            # 右键菜单：同步 Gradle
```

## 核心概念

- **LOCAL**: `includeBuild=true` 且本地目录存在，使用源码复合构建
- **MAVEN**: `includeBuild=false`，从 Maven 仓库拉取 AAR
- **MISSING**: `includeBuild=true` 但本地目录不存在，需要先下载

## 文件关系

- `scripts/module_manager/project-repos.json5`: 模块配置中心文件
- `include_build.gradle`: 插件自动生成的复合构建配置（由 settings.gradle 引入）

## 技术栈

- Kotlin 1.9.25
- IntelliJ Platform SDK 2.2.1
- Gradle 8.10
- JVM 17
