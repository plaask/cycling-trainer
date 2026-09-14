# Cycling Trainer

一个轻量的安卓骑行台训练 App：连上骑行台和心率带，选一门 `.zwo` 课程，按目标功率跟着骑。

**没有账号，没有云同步，没有订阅，不联网。** 所有数据都在你自己的手机上。

自研项目（Kotlin + Jetpack Compose + Material 3），没有用第三方 BLE 库，也没有用 navigation 库。

---

## 功能

- **ERG 目标功率**：读 `.zwo` 课程的 `%FTP`，结合你的 FTP 算出目标瓦数，直接推给骑行台
- **实时数据**：功率（含 %FTP 与功率区间）、心率（含心率区间）、踏频、速度
- **曲线图**：课程功率直方图 + 实际功率/心率曲线叠加；warmup/cooldown 的 ramp 画成梯形
- **个人配置**：FTP、LTHR、最大心率，以及心率区间用哪个作参考（LTHR 7 区 / 最大心率 5 区）
- **记录与导出**：1 Hz 存 CSV；导出为 `.FIT`，放到 `下载/CyclingTrainer/`，可直接导入 Garmin Connect / Strava 等
- **自由骑行**：不选课程也能骑，只记录不控阻
- 深色/浅色主题，横竖屏切换不掉连接

## 支持的设备

| 设备 | 协议 | 说明 |
|---|---|---|
| 骑行台 | **FE-C over BLE**（`6e40fec1`） | ThinkRider 全系等；功率/踏频/速度 + ERG 控阻 |
| 骑行台 | **FTMS**（`0x1826`） | 标准协议；控制点缺失时降级为"仅数据"模式 |
| 心率带 | HRS（`0x180D`） | 任意标准 BLE 心率带 |
| 踏频器 | CSC（`0x1816`） | 连上后优先于骑行台自带踏频 |

> 开发与验证主要在 **ThinkRider X2**（走 FE-C）+ 迈金心率带 + iGPSPORT 踏频器上完成。
> FTMS 路径按蓝牙规范实现并有真实抓包测试用例，但**尚未在标准 FTMS 骑行台真机上验证过**。

## 安装

从 [Releases](../../releases) 下载 APK（arm64，Android 13+）直接安装。

> 若自行构建，注意 release 包装配了 R8（代码压缩），这部分尚未经真机充分验证；
> 需要稳妥可用的话建议先用 `assembleDebug`。

## 课程文件从哪来

App **不内置任何课程**，需要你自己准备 `.zwo` 文件：

1. 在手机的"文档 / Documents"下建一个文件夹（例如 `CyclingTrainer`）
2. 把 `.zwo` 文件放进去（自己写的、或从任何支持 `.zwo` 的平台导出的都行）
3. 打开 App → **课程**页 → **选择课程文件夹** → 选中它

授权一次即可，之后每次启动自动读取；新增课程后点"刷新"。

## 构建

需要 **JDK 21**（Gradle 8.10.2 不支持 JDK 25）和 Android SDK（platform 37、build-tools 36.0.0）。

```powershell
$env:JAVA_HOME='<你的 JDK 21 路径>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat :app:assembleDebug        # 产出 app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:testDebugUnitTest    # 46 个 JVM 单测
```

- `arm64-v8a` 之外用 `-Pandroid.injected.build.abi` 或改 `app/build.gradle.kts` 里的 `abiFilters`
- 首次构建要联网拉依赖；离线环境请预先准备 Gradle 缓存

## 技术栈

AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.12.01 · Gradle 8.10.2 · minSdk 33 / targetSdk 37

BLE 用官方 `BluetoothGatt`（自己封装了串行化读写与超时）；FIT 写入是手写的，不依赖 Garmin SDK。

## 参与

欢迎提 Issue 反馈问题，尤其是**真实设备的兼容性**（附上失败信息里的"实际发现服务"列表会很有帮助）。

## 许可证

**GNU General Public License v3.0** — 见 [LICENSE](LICENSE)。

简单说：可以自由使用、修改、分发，但**衍生作品必须同样以 GPL-3.0 开源**，不能拿去做闭源版本。

```
Cycling Trainer — Android 骑行台训练 App
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

| 组件 | 许可证 |
|---|---|
| AndroidX / Jetpack Compose / Material 3 | Apache-2.0 |
| kotlinx.coroutines | Apache-2.0 |
| [Material Symbols](https://fonts.google.com/icons) 图标（`ic_bluetooth` / `ic_fitness_center` / `ic_history`） | Apache-2.0 |
| kxml2（仅测试） | BSD 风格 |
| JUnit 4（仅测试） | EPL-1.0 |

以上均与本项目的 GPL-3.0 兼容。

本项目与 ThinkRider、Zwift、Garmin、Wahoo、Tacx、Elite 等厂商**无隶属关系**；
文中提及的商标仅用于说明协议兼容性。
