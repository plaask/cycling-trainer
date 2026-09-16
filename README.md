# Cycling Trainer

面向 Android 的轻量级骑行台训练应用。通过蓝牙连接智能骑行台与心率传感器，加载 `.zwo` 结构化课程，
依据运动员 FTP 计算目标功率并驱动骑行台进入 ERG 模式，同时以 1 Hz 频率记录训练数据。

应用完全离线运行：无需账号，不进行网络通信，训练数据仅存储于本机。

使用 Kotlin 与 Jetpack Compose 实现，不依赖第三方 BLE 库或导航库。

---

## 目录

- [主要特性](#主要特性)
- [支持设备](#支持设备)
- [安装](#安装)
- [课程文件](#课程文件)
- [构建](#构建)
- [技术实现](#技术实现)
- [贡献](#贡献)
- [许可证](#许可证)
- [第三方组件](#第三方组件)

## 主要特性

| 特性 | 说明 |
|---|---|
| ERG 目标功率 | 解析 `.zwo` 课程的 `%FTP` 值，结合配置的 FTP 换算为瓦数并下发给骑行台 |
| 实时数据 | 功率（含 %FTP 与功率区间）、心率（含心率区间）、踏频、速度 |
| 训练曲线 | 课程功率直方图与实际功率／心率曲线叠加显示；ramp 段以梯形呈现 |
| 运动员配置 | FTP、LTHR、最大心率；心率区间可选用 LTHR（7 区）或最大心率（5 区）作为基准 |
| 数据记录 | 1 Hz 采样写入 CSV；可导出为 `.FIT` 至 `Download/CyclingTrainer/`，兼容 Garmin Connect、Strava 等平台 |
| 自由骑行 | 不加载课程时仅记录数据，不进行阻力控制 |
| 界面 | 深色／浅色主题；屏幕旋转不中断连接 |
| 画中画 | 训练中回到桌面或切换到其他应用时自动进入画中画，继续显示目标功率／功率／心率／踏频，并可在系统菜单中暂停或继续 |
| 后台持续记录 | 训练期间由前台服务维持会话：屏幕熄灭仍以 1 Hz 记录并保持 ERG 控制，常驻通知可直接暂停／继续／停止 |

## 支持设备

| 设备类型 | 协议 | 说明 |
|---|---|---|
| 骑行台 | FE-C over BLE（服务 `6e40fec1`） | 支持功率／踏频／速度读取与 ERG 控阻；适用于 ThinkRider 等厂商 |
| 骑行台 | FTMS（服务 `0x1826`） | 标准协议；当设备未提供控制点（`0x2AD9`）时降级为仅数据模式 |
| 心率传感器 | HRS（服务 `0x180D`） | 兼容标准 BLE 心率带 |
| 踏频传感器 | CSC（服务 `0x1816`） | 连接后优先于骑行台内置踏频数据 |

开发与验证环境为 ThinkRider X2（FE-C）、迈金心率带与 iGPSPORT 踏频器。

FTMS 实现依据蓝牙规范编写，并包含基于真实抓包的测试用例，但尚未在标准 FTMS 骑行台上完成真机验证。

## 安装

从 [Releases](../../releases) 页面获取 APK 文件。

- 最低系统版本：Android 13（API 33）
- 架构：arm64-v8a
- 发行包使用项目专用密钥签名（APK Signature Scheme v3）

自行构建时请注意，release 构建启用了 R8 代码压缩，该路径尚未经过完整真机验证。

## 课程文件

应用不内置课程资源，课程文件由使用者自行提供：

1. 在设备存储的 `Documents` 目录下创建文件夹（例如 `CyclingTrainer`）
2. 将 `.zwo` 文件放入该文件夹
3. 在应用内进入**课程**页，点击**选择课程文件夹**并选中该文件夹

授权操作仅需执行一次，应用在后续启动时会自动读取该目录。新增课程文件后点击「刷新」即可重新载入。

## 构建

### 环境要求

- JDK 21（Gradle 8.10.2 不支持 JDK 25）
- Android SDK：platform 37、build-tools 36.0.0

### 命令

```powershell
$env:JAVA_HOME='<JDK 21 路径>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat :app:assembleDebug        # 输出 app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:assembleRelease      # 输出 app/build/outputs/apk/release/app-release.apk
.\gradlew.bat :app:testDebugUnitTest    # 运行 50 个 JVM 单元测试
```

首次构建需要网络连接以下载依赖；离线环境需预先准备 Gradle 缓存。

### 发布签名

release 构建的签名信息由 `app/key.properties` 提供，该文件已被 git 忽略：

```properties
storeFile=/absolute/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

该文件不存在时，release 构建将产生未签名产物，而不会回退到调试密钥。

### ABI

默认仅构建 `arm64-v8a`。如需其他架构，请修改 `app/build.gradle.kts` 中的 `abiFilters`。

## 技术实现

| 项目 | 值 |
|---|---|
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| Gradle | 8.10.2 |
| minSdk / targetSdk | 33 / 37 |

BLE 通信基于 Android 官方 `BluetoothGatt` API 自行封装，包含串行化读写与超时处理。
FIT 文件写入为自主实现，不依赖 Garmin FIT SDK。

## 贡献

问题反馈与代码贡献均可通过 Issue 与 Pull Request 提交。

提交设备兼容性问题时，请附上机型、Android 版本及错误信息中列出的「实际发现服务」UUID 列表，该信息对协议排查有直接帮助。

## 许可证

本项目采用 **GNU General Public License v3.0**，完整条款见 [LICENSE](LICENSE)。

该许可证允许使用、修改与再分发，但要求衍生作品同样以 GPL-3.0 条款开源，不得用于闭源衍生作品。

```
Cycling Trainer — Android 骑行台训练应用
Copyright (C) 2026  Cycling Trainer contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

## 第三方组件

| 组件 | 许可证 | 使用范围 |
|---|---|---|
| AndroidX / Jetpack Compose / Material 3 | Apache-2.0 | 运行时 |
| kotlinx.coroutines | Apache-2.0 | 运行时 |
| Material Symbols 图标 | Apache-2.0 | 运行时（`ic_bluetooth`、`ic_fitness_center`、`ic_history`） |
| kxml2 | BSD 风格 | 仅测试 |
| JUnit 4 | EPL-1.0 | 仅测试 |

上述许可证均与本项目的 GPL-3.0 兼容。

本项目与 ThinkRider、Zwift、Garmin、Wahoo、Tacx、Elite 等厂商无隶属或背书关系；
文中提及的商标仅用于说明协议兼容性。
