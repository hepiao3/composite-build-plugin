---
name: release
description: Use when the user wants to release a new version of the composite-build-plugin — auto-increments version, reads git log since last release, and updates README changelog.
---

# Release — Composite Build Plugin 版本发布

## 概述

自动递增版本号、读取 git 改动、更新 README 历史版本表格，合并为一个 commit 提交。

## 执行步骤

### 1. 读取当前版本

```bash
grep "^pluginVersion=" gradle.properties
```

例如当前为 `pluginVersion=1.0.7`。

### 2. 确认新版本号

默认将最后一位 +1（`1.0.7` → `1.0.8`）。告知用户默认版本，询问是否确认，或由用户指定其他版本。

### 3. 读取本次改动（git log）

先找上一个版本 tag：

```bash
git describe --tags --abbrev=0 2>/dev/null
```

如果有 tag，读取自上次 tag 以来的提交：

```bash
git log <上个tag>..HEAD --oneline --no-merges
```

如果没有 tag，找到上一次版本变更的 commit（含 `chore(config): 更新插件版本至` 的那条），读取其之后的提交：

```bash
git log --oneline --no-merges
# 找到上次版本 commit 的 hash，然后
git log <hash>..HEAD --oneline --no-merges
```

按 commit message 前缀分类（Conventional Commits）：

| 前缀 | 分类 |
|------|------|
| `feat` | 新增功能 |
| `fix` | Bug 修复 |
| `perf` / `refactor` / `style` | 优化 |
| `docs` / `chore` / `build` / `revert` | 忽略（不显示在 changelog） |

### 4. 更新 gradle.properties

将 `pluginVersion=旧版本` 改为 `pluginVersion=新版本`。

### 5. 更新 README.md 历史版本表格

在 `## 历史版本` 表格**最顶部**（表头下方第一行）插入新版本行：

```markdown
| X.Y.Z | 新增功能描述 | Bug 修复描述 |
```

- 新增功能栏：将 feat 类 commit 整合成简洁中文描述，多条用 `<br>` 分隔
- Bug 修复栏：将 fix 类 commit 整合成简洁中文描述，多条用 `<br>` 分隔
- 若某栏无内容，填写 `—`

### 6. 提交改动（一个 commit）

```bash
git add gradle.properties README.md
git commit -m "release: 发布版本 X.Y.Z"
```

### 7. 询问是否打 tag 并推送

**必须得到用户明确确认后才执行：**

```bash
git tag vX.Y.Z
git push origin master
git push origin vX.Y.Z
```

## 注意事项

- 推送操作必须得到用户明确确认
- tag 格式以 `v` 开头（如 `v1.0.8`）
- 若 tag 已存在，提示用户先删除