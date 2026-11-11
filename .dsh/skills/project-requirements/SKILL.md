---
name: project-requirements
description: 本项目需求与约束的真相源。改动本项目前先读本文件；用户提出新需求或变更后必须同步更新。
---

# P145 高级设置 需求与约束

## 目标
- P145 车机（Flyme Auto / Android 9 / MTK）自用系统级 Hook 控制台：用 Frida 控制「屏蔽巡航退出播报」「音量锁媒体」两个开关。

## 需求
- [R1] 本地 git 仓库与忽略规则（`keys/` `build/` `dist/` `tmp/` 日志不入库）— 已完成
- [R2] 项目级需求技能为唯一真相源，随需求实时维护 — 已完成
- [R3] 截图归入 `docs/screenshots/`，成品只落 `build/`、`dist/` — 进行中
- [R4] README 与代码/目录对齐（版本以 AndroidManifest 为准）— 进行中

## 约束
- 不用 gradle：`build.ps1` = aapt2 → javac → d8 → zipalign → apksigner 平台签名。
- minSdk 28 / targetSdk 25 故意为之：否则 `am broadcast -a ...` 送不到清单接收器。
- 平台签名 `keys/`（CN=Android）只在本机，绝不入库不外传；包名 `com.p145.advancedsettings`。
- Frida 固定 16.7.19 + attach（17.x 在 DuerOS SIGSEGV）。
- root 走内置 ADB 客户端直连 `127.0.0.1:5555`（`ro.adb.secure=0`），不用 su，不依赖 EVCC。
- 车机 Android 9 / 1920×1080 / density 160 / 横向。

## 目录约定
- 源码 `src/com/p145/advancedsettings/`（core/ 逻辑、ui/ 控件）｜资源 `res/`｜注入脚本 `assets/*.js`。
- `build.ps1`、`AndroidManifest.xml` 留根目录：aapt2 直接读，别挪。
- 成品 `build/advanced-settings.apk`、发布副本 `dist/`；截图 `docs/screenshots/`；临时 `tmp/`、日志 `logs/`。

## 变更记录（最多 5 条）
- 2026-11-11 R1–R4：建仓、建需求技能、截图入 docs、README 对齐 1.6。
