# 环境修复记录（2026 会话，继续 HANDOFF_cycling-trainer.md）

以下修复已在本机落地，新会话/新工作区继续前请先读此文件 + 主 handoff。

## 1. JDK

- 本机无 JAVA_HOME/系统 JDK；Android Studio JBR 是 **JDK 25**，Gradle 8.10.2 无法在 JDK 25 上跑（native-platform.dll 加载失败）。
- **已安装 Temurin JDK 21** 到：`<JDK21>`
- 命令行构建必须设：
  ```powershell
  $env:JAVA_HOME='<JDK21>'
  $env:Path="$env:JAVA_HOME\bin;$env:Path"
  $env:GRADLE_USER_HOME='<repo>\.gradle-home'   # 沙箱内固定
  $env:ANDROID_USER_HOME='<repo>\.android'      # debug keystore 位置
  $env:TMP='<repo>\.tmp'; $env:TEMP=$env:TMP    # 关键！沙箱 TEMP 每次不同导致 native dll 解压失败
  ```

## 2. Gradle wrapper

- 已生成：`gradlew.bat` + `gradle/wrapper/gradle-wrapper.jar`（8.10.2）。
- 本地发行版 `<localGradle>\bin\gradle.bat` 可直接用。

## 3. Android SDK platform（关键坑）

- SDK 里 platform 目录叫 **`android-37.0`**（Google 2026 打包，Android 17），AGP 8.7.3 需要 `platforms;android-37`。
- 处理（两步）：
  1. 在 `<AndroidSDK>\platforms` 建 junction：`android-37 -> android-37.0`
  2. **改 `android-37.0\source.properties` 的 `AndroidVersion.ApiLevel=37.0` → `37`**（带小数点让 AGP parseInt 失败）
  3. **改 `android-37.0\package.xml` 的 `<api-level>37.0</api-level>` → `<api-level>37</api-level>`**（同样问题）
- Build Tools 只有 36.0.0 → `app/build.gradle.kts` 加 `buildToolsVersion = "36.0.0"`。

## 4. 代理与依赖下载

- 本机到 **dl.google.com 直连正常**（Java TLS 好使）→ **不要**给 Gradle 配代理（FlClash 7890 对 dl.google.com 反而 TLS 失败）。gradle.properties 里已移除代理。
- GitHub 下载走 7890 代理；PowerShell/curl(schannel) 在本机 TLS 全挂，用 JBR 的 Java HttpClient 或 Git 的 OpenSSL curl。
- AGP 每次尝试下载 SDK 清单到 `<userHome>\.android\cache` 会因权限失败——不影响构建，只是日志噪音。

## 5. 当前构建状态

- `:app:assembleDebug` ✅ BUILD SUCCESSFUL → `app\build\outputs\apk\debug\app-debug.apk`（约 55MB）
- 源码：workout 解析器/模型、BLE 层、session engine/recorder、Compose UI（设备/课程/训练/历史）、AppViewModel、MainActivity
- 单测：ZwoParserTest / BtParsersTest / SessionEngineTest

## 6. 其它

- `Dl.java` 是临时下载工具，可删。
- `.gradle-home`、`.tmp`、`.android` 在项目里是本地缓存（建议 .gitignore 加入）。
