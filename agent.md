# AGENT.md — Cycling Trainer 工作区指引

## 0. 项目一句话

用 Kotlin + Jetpack Compose + Material3 从零自研的 GPL-3.0 安卓骑行台训练 App（包名 `io.github.cyclingtrainer.app`，应用名 Cycling Trainer），连接智骑 ThinkRider X2（BLE FTMS/ERG）+ 迈金心率带（HRS）+ iGPSPORT 踏频器（CSC），播放 .zwo 课程、控制 ERG 功率、1Hz 记录 CSV。
---

## 1. 重要路径速查

| 项 | 路径 |
|---|---|
| 工作区（一切都在此） | `D:\coding\cycling-trainer\` |
| **JDK 21（构建必需）** | `D:\coding\Android\jdk-21\jdk-21.0.12.1+1` |
| Android SDK | `D:\coding\Android\Sdk`（platform `android-37.0`+junction `android-37`、build-tools 36.0.0） |
| Android Studio JBR（JDK 25，**不能跑 Gradle 8.10.2**） | `D:\coding\Android\Android Studio\jbr` |
| 本地 Gradle 发行版 8.10.2（备用） | `C:\Users\11988\AppData\Local\GradleDist\gradle-8.10.2\bin\gradle.bat` |
| APK 产物 | `app\build\outputs\apk\debug\app-debug.apk` |
| 单测报告 | `app\build\reports\tests\testDebugUnitTest\index.html` |

## 2. 构建环境（每个新 shell 必须执行）

```powershell
$env:JAVA_HOME='D:\coding\Android\jdk-21\jdk-21.0.12.1+1'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='D:\coding\cycling-trainer\.gradle-home'
$env:ANDROID_USER_HOME='D:\coding\cycling-trainer\.android'   # debug keystore 所在
$env:TMP='D:\coding\cycling-trainer\.tmp'; $env:TEMP=$env:TMP  # 必须固定！沙箱 TEMP 随机会导致 native dll 失败
```

构建 / 测试：

```powershell
.\gradlew.bat :app:assembleDebug          # 出 APK
.\gradlew.bat :app:testDebugUnitTest      # JVM 单测（12 个）
```

## 3. 环境事实与坑（详细见 ENV_FIXES.md）

- **代理**：FlClash 7890。**Gradle 不要配代理**——本机直连 dl.google.com 正常（Java TLS 可用），走代理反而握手失败。GitHub 下载才需要代理（用 JBR 的 Java HttpClient 或 Git 自带 OpenSSL curl；PowerShell/curl schannel 在本机 TLS 全挂）。
- **SDK platform**：目录名 `android-37.0`；已建 junction `android-37`，且已把 `source.properties` 与 `package.xml` 里的 `ApiLevel=37.0`/`<api-level>37.0</api-level>` 修为 `37`。**别删 junction、别还原这两个文件**。
- **buildToolsVersion = "36.0.0"** 已在 `app/build.gradle.kts` 显式指定（AGP 默认要 34 且 `~/.android` 不可写会下载失败）。
- AGP 每次构建会尝试写 `C:\Users\11988\.android\cache` 拉 SDK 清单并报 `NoSuchFileException` 噪音——**无害，忽略**。
- 技术栈锁定：AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01 / Gradle 8.10.2 / minSdk 33 / target 37。JDK 25(JBR) 不兼容 Gradle 8.10.2，勿用。

## 4. 代码结构（已实现）

```
app/src/main/java/io/github/cyclingtrainer/app/
├── MainActivity.kt          # 入口：权限申请 + 底部导航（训练/课程/历史/设置）
├── AppViewModel.kt          # AndroidViewModel：课程库加载、连接编排、Session 控制、
│                            #   HR 参考/主题等持久化；onCleared 时才 release BLE
├── ble/
│   ├── GattUuids.kt         # FTMS/HRS/CSC/CPS/FE-C UUID + opcode/result/status 常量
│   ├── BluetoothLeManager.kt# 官方 BluetoothGatt 封装：mutex 串行读写、pending-slot 回调确认、扫描
│   ├── FtmsTrainer.kt       # RequestControl→Start→SetTargetPower(0x05,int16LE)，读 Feature/PowerRange
│   ├── FecOverBle.kt        # ThinkRider FE-C 隧道（FEC2 notify / FEC3 write，页25功率踏频/页0x31控功）
│   ├── TrainerDriver.kt     # FTMS 与 FE-C 统一接口（powerFlow/cadenceFlow/startErg/stopControl）
│   ├── HeartRateSensor.kt / CadenceSensor.kt（带日志+无帧自动重订阅）
│   ├── DeviceManager.kt     # 聚合三设备：增量 connectRole（不再全断全连）、
│   │                        #   CSC 连接后锁定优先于骑行台踏频、断连监听清角色、设备名缓存
│   └── BtParsers.kt         # Indoor Bike(0x2AD2)/HRS(0x2A37)/CSC(0x2A5B) 解析 + LE 编解码
├── workout/
│   ├── Workout.kt           # 模型：powerFractionAt(elapsed) 线性 ramp 求值
│   ├── ZwoParser.kt         # Warmup/SteadyState/IntervalsT(展开)/Cooldown；FreeRide 跳过
│   └── CourseSource.kt      # 从用户授权目录(Documents/CyclingTrainer)读取 .zwo（SAF tree）
├── session/
│   ├── SessionEngine.kt     # 1Hz tick → 报告目标功率（FTP 小数×FTP）；节拍驱动 recorder 采样
│   ├── RideRecorder.kt      # 无独立时钟：engine onTick 调 sample()；暂停天然跳过
│   ├── FitWriter.kt         # 手写 FIT 2.0 activity writer（FILE_ID→…→ACTIVITY，CRC，纯 JVM）
│   ├── FitExporter.kt       # CSV→FIT 字节/文件转换（纯 JVM，可单测）
│   ├── HrZones.kt           # LTHR/MaxHR 5 区间计算（纯 JVM）
│   └── PublicExport.kt      # MediaStore 写 Download/CyclingTrainer/（覆盖旧文件）
└── ui/
    ├── DevicesScreen.kt     # 扫描/连接；已连接角色行永远置顶并合入列表（无广播也显示）
    ├── WorkoutsScreen.kt    # 课程目录授权/刷新 + 课程卡片内嵌直方图
    ├── TrainScreen.kt       # 目标功率(%FTP)、实时曲线图、功率/心率(FTP%/Z区间)、ERG 开关
    ├── HistoryScreen.kt     # 骑行记录列表：导出 FIT(到下载目录)/删除(仅删 CSV)
    ├── SettingsScreen.kt    # FTP/LTHR/最大心率/心率参考/主题
    ├── charts/WorkoutChart.kt # Canvas 直方图(梯形 ramp)+功率/心率曲线叠加
    ├── Screen.kt / theme/Theme.kt
```

- **课程来源**：不再内置 assets。预设 9 个 .zwo 放仓库根 `preset-workouts/`（含 README）；App 内由用户通过系统文件夹选择器授权一次（Documents/CyclingTrainer），URI 持久化，每次启动自动读取。test sourceSet 把 `../../preset-workouts` 挂为测试资源（见 app/build.gradle.kts）。
- 单测：`app/src/test/...`（ZwoParserTest / BtParsersTest / FecParsersTest / SessionEngineTest / FitWriterTest）。
- 骑行记录 CSV 名称格式 `yyyy-MM-dd_HH-mm-ss_<课程名>.csv`；FIT 导出到系统 `Download/CyclingTrainer/`。

## 5. 下一步待办（真机联调，需用户参与）

### ✅ v0.2.0（2026-09 会话，真机反馈后落地）

真机反馈：心率带点连接无反应；课程点不动；训练页点开始无响应。经排查为下列代码问题并已修复：

1. **课程卡片不可点击**：`WorkoutCard` 原来用无 onClick 的 `Card` 重载，点击参数被丢弃 → 现用可点击 `Card(onClick=)`，点击=选中高亮（边框+底色），到"训练"页再开始。
2. **训练页无 workout 时"开始"是空操作**：`TrainScreen` 的按钮 `workout?.let(vm::startWorkout)`，无课程=什么都不发生 → 重构为**训练首页**：`workout` 为空时按钮=“开始自由骑行”（SessionEngine/AppViewModel 支持 `workout=null`：仅 1Hz 记录、不推 ERG；自由骑行文件名 `自由骑行`，replacce regex 改 `\p{L}\p{N}` 保留中文）。
3. **连接点击无反馈 + 失败被吞**：`DevicesScreen` 的 connecting 状态点完立刻清空；`connectAll` 失败后 `disconnectAll()` 把 `errorMessage` 一起清掉 → connecting 现在由 `vm.connectDevice(dev){result}` 回调真正驱动，失败弹 **AlertDialog** 显示真实原因；`DeviceManager.disconnectAll(clearError=false)` 保留错误。
4. **FTMS Control Point 订阅写错 CCCD**：`enableNotifications` 对所有特性写 0x0001；indication 特性（FTMS CP 0x2AD9）须写 **0x0002** → 新增 `enableNotifications(ch, indicate=true)` 参数，FtmsTrainer 对 CP 用 indication。
5. **连接前不停扫描**：`BluetoothLeManager.connect()` 开头 `stopScan()`，规避部分 ROM 连接即断（status 133）。
6. **订阅返回值被丢弃**：HeartRateSensor/CadenceSensor/FtmsTrainer connect 现在校验 `enableNotifications` 返回值，失败抛出带特性名的错误。
7. **IA 重构**：底部四 tab 顺序 **训练/课程/历史/设置**（Screen.kt entries）。训练=首页，右上角蓝牙图标 → 全屏设备子页（DevicesScreen 改为 `onClose`/返回键关闭，含"返回训练"按钮）；课程页只选中不高亮跳转；新增 **SettingsScreen**（个人配置 FTP 50–600 默认200、主题模式 跟随设备/明亮/暗黑 存 SharedPreferences 即时生效、训练记录 CSV 列表、关于）；HistoryScreen 保留为独立 tab。`Theme.kt` 支持 `ThemeMode`，MainActivity 收集 `vm.themeMode` 传入。FTP/主题持久化在 AppViewModel（prefs key `ftp_watts`/`theme_mode`）。
8. 单测新增：SessionEngineTest 自由骑行用例（null workout 不推目标、只 tick）。

**真机验证清单**（下一轮联调用）：连接心率带应有 spinner→成功/失败对话框；失败时对话框给真实原因；课程卡片点击高亮"已选"；训练页选课点"开始训练"进入实时页并计时；不选课"开始自由骑行"仅记录；设置页改主题即时切换；FTP 保存后训练目标按新 FTP 计算。

### ✅ v0.2.1（2026-09 会话，ThinkRider X2 专项调研）

**调研结论（ThinkRider 连接）**：
- X2 支持 BLE 4.0（手机/平板）+ ANT+ FE-C（PC）；官方资料不公布广播 service UUID。
- 同厂 X7 在 TrainerDay 实锤：**智能骑行台常不广播 FTMS 服务，连接后 discovery 才暴露**（TrainerDay 2023-05）。
- 业界（Zwift/MyWhoosh/TrackMyIndoorWorkout/BikeControl）做法：扫描不过滤广播服务；允许对未知设备连接；连接后按实际 GATT 服务定性。
- "骑行台被三处读取（功率/踏频/控制）" = 标准 FTMS 三通道：Indoor Bike Data(0x2AD2)/Control Point(0x2AD9)/Status——FtmsTrainer 已按此实现。

**v0.2.1 代码变更**：
- `BleDevice` 增加 `discoveredServices`；`allServices` = 广播 ∪ 连接后发现的并集（BleTypes.kt）。
- `BluetoothLeManager`：GattSession 在 `onServicesDiscovered` 记录真实服务；`connect()` 成功后把发现服务折回设备列表 → 设备卡片类型随之刷新、重排。
- `AppViewModel.connectDevice`：未知类型设备不再拒绝——按 trainer 尝试连接（连上若含 FTMS 即成为骑行台）；角色状态按 `deviceManager.*Address` 实际结果逐角色更新。
- `DeviceManager.connectAll` 重构：逐角色独立容错（原单一 catch+disconnectAll 会让一个失败拖垮其它已连设备）。
- UI（DevicesScreen）：骑行相关设备（骑行台>心率>踏频）置顶排序；未识别类型行显示"未识别类型（可尝试连接）"+ 连接按钮。
- UI（TrainScreen idle）：未开始骑行时显示已连接设备实时读数（功率/心率/踏频）。
- 踏频无读数：确认是否踩踏（rpm 需两次事件差）；idle 页现也能看到读数。

**真机验证清单（v0.2.1）**：X2 出现在列表（可能在底部"未识别"）→ 点连接 → 成功后应在顶部显示为"骑行台"并出功率/踏频读数；心率带 idle 读数跳动；断开即时刷新。

### 🔧 v0.2.2（2026-09，连接 X2 报 "FTMS control point missing"）

现象：尝试连 X2 与连无关设备都报同样错。结论：**错误来自盲按骑行台连接后找不到 0x2AD9**，并非 X2 特有问题，但掩盖了 X2 真实暴露的服务。

变更：
- `FtmsTrainer.connect()`：**Indoor Bike Data(0x2AD2) 为必需**，Control Point(0x2AD9) 改为**可选**——缺控制点的设备（数据-only 骑行台）也能连上读功率/踏频；失败错误消息带该设备**实际发现的服务 UUID 列表**（取 4-8 位短值），便于诊断私有协议。
- 新增 `FtmsTrainer.controlPointAvailable`；DeviceManager 据此区分"获取控制权"与"仅数据模式"提示（后者不报误导性"控制权获取失败"）。
- 目的：X2 若标准 FTMS 但缺 CP → 至少数据模式可用；若服务列表显示 0x1818(功率计)/6E40FEC1(FE-C 私有) → 需另写驱动。

**下一步（需用户）**：装 v0.2.2 后连 X2，把**失败弹窗里的"实际发现服务"列表**发回——据此判断 X2 是标准 FTMS 还是私有协议（CPS/FE-C），再定实现方案。

### ✅ v0.2.3（2026-09，X2 真实协议确认 + FE-C 驱动实现）

用户反馈 X2 实际服务：**1800 1801 fff0 fec1 1818 180a fd00**。调研确认（Auuki 实测 ThinkRider X7 Pro 抓包 + pycycling Tacx FE-C 实现）：
- X2 **不是 FTMS**，而是 **Cycling Power Service(0x1818) + FE-C over BLE 隧道(6e40fec1)**——与同厂 ThinkRider 全系一致（TrainerRoad/GoldenCheetah 论坛实锤）。
- FE-C 隧道特征：`6e40fec2` notify(收), `6e40fec3` write(发)。
- 帧格式（13 字节）：`[0]=A4 [1]=09 [2]=4E(broadcast) [3]=05(ch) [4]=数据页 [5..11]载荷 [12]=XOR(0..11)`。
- 数据页 25(0x19)=功率/踏频：功率 12bit 在 byte9 + byte10 低 nibble（0xFFF=无效）；踏频 byte6(0xFF=无效)。
- 数据页 16(0x10)=速度：byte8-9 u16LE ×0.001 m/s。
- 控制页 0x31=Set Target Power：载荷 u16LE(瓦/0.25)。

代码变更：
- `GattUuids`：加 CPS_SERVICE/MEASUREMENT、FEC_SERVICE/FEC_TX/FEC_RX。
- 新增 `FecOverBle.kt`（FE-C 隧道驱动：订阅 FEC2、写 FEC3 发目标功率）+ `FecParsers` object（可测的帧构造/解析）。
- 新增 `TrainerDriver.kt`（抽象：FTMS 与 FE-C 两驱动统一接口，含 startErg/stopControl）。
- `DeviceManager`：连接后按实际服务**自动选驱动**（1826→FTMS，fec1→FE-C），统一收流/控功。
- `DevicesScreen.deviceKind`：含 fec1 也识别为"骑行台"。
- 新增 `FecParsersTest`（Auuki 实测帧 90W/300W/踏频15/校验和等），全绿。

**真机验证（v0.2.3）**：设备列表点 X2（若带"未识别类型"字样仍可点连接）→ 成功后应为"骑行台"，idle 页功率/踏频读数出现（踩踏时）；开始自由骑行/课程后阻力应随目标功率变化（ERG 走 FE-C 0x31 页）。若踩踏无读数：FE-C 页 25 未到，需抓 log 看 FEC2 原始帧。

### ✅ v0.2.4（2026-09，X2 功率读数闪烁 + ERG 开关）

真机：X2 连上、有功率，但读数与 "—" 交替闪。根因：FE-C 隧道交替发页 25（含功率）与页 16（速度页，无功率）——解析器对页 16 返回 null → 每帧把功率清空 → UI 闪烁。

变更：
- `DeviceManager`：trainer/heartRate/cadence 流改为**只更新非 null 值**（无功率的页不再清空上次读数）。
- **SessionEngine 暂停/恢复 bug 修复**：原实现 pause 后 delay 醒来 `break` 退出 ticker，resume 后再无人 tick（一直存在）→ 改为 pause 只翻 phase、协程 continue 等待，resume 续走，暂停期间 elapsed 不推进。
- **SessionEngine.ergEnabled**（@Volatile）：关闭时只计时记录、不推 ERG 目标。
- `AppViewModel.setErgEnabled()` + `ergEnabled` 状态：关时清目标并 release trainer；startWorkout 时同步开关（关则不调 FTMS start）。
- UI（TrainScreen）：训练页加 **ERG 开关**（Switch + 状态文案"ERG 已开启/已关闭"），训练中可随时切换。
- 单测新增：ERG 关闭不推目标；pause→resume 继续 tick 不重启（SessionEngineTest）。

**真机验证（v0.2.4）**：连接 X2 → idle 页功率/踏频读数**稳定不闪**（持续踩踏）；开始课程 → 训练页 ERG 开关置开，阻力应跟随目标（可关掉开关对比阻力是否释放）；中途暂停/继续，计时应正确续走。

### ✅ v0.3.0（2026-09 会话，功能优化 + 连接健壮性 + 真机诊断）

**用户原话需求**（功能 1–6 + 三个 bug + 后续补充）：

1. 历史记录导出为 .fit（存到用户可见的 `Download/CyclingTrainer/`，非 App 私有目录）；
2. 个人配置加 LTHR/最大心率，并选择以哪个作心率参考（默认 LTHR=170/最大=190，Z1–Z5 区间）；
3. 训练页功率旁显示 %FTP、心率旁显示区间（如 Z3 节奏 140–149）；
4. 目标功率下方曲线图：课程直方图（淡化）+ 实际功率/心率曲线叠加；自由骑行仅曲线；
5. ERG 开关不再影响目标功率显示（目标始终显示课程要求）；开关文案去掉括号说明；
6. 课程页卡片直接内嵌直方图；warmup/cooldown 等 ramp 段画**横置直角梯形**（不是矩形）。

**后续补充需求**：
- 课程库改为**外部文件夹**（Documents/CyclingTrainer 放 .zwo，App 内"选择课程文件夹"授权一次即每次启动自动读取），**不再内置 assets 课程**（预设文件挪到仓库根 `preset-workouts/` 供复制）；
- 历史页加**删除**（只删本机 CSV，不动已导出的 FIT）；导出失败弹真实原因、成功 Toast；
- 导出目标改为公共 `Download/CyclingTrainer/`（MediaStore，无需权限），重复导出覆盖旧文件。

**Bug 修复清单（v0.3.0）**：
- **连接互相踢下线（关键）**：`connectAll` 每次全断全连 → 改为 `connectRole(role,address)` **增量连接**，只动被点的角色；`connect()` 前先 `sweepStaleSessions()` 清理平台假活会话；
- **已连接设备在设备页丢失/垫底/名字丢失**：设备页把已连接角色行**恒置顶合入列表**（无广播也显示）；`DeviceManager` 缓存地址→设备名（`deviceName()/rememberName`）；角色断连自动清状态；
- **踏频优先级失效**：CSC 连接后**锁定只用 CSC 读数**（此前骑行台 cadence 每次到达会顶掉显示）；骑行台 cadence 仅在未连 CSC 时作后备；UI 标 `CSC` / `CSC(—)`；
- **ERG 多次开关失效**：关闭时释放控制权；重新开启时**重新执行 FTMS requestControl+Start 握手**；
- **旋转屏幕丢连接/丢课程**：`onDestroy` 不再 `release()` BLE（改 `AppViewModel.onCleared()`）；课程选择改为 `rememberSaveable`（保存课程名，重启库加载后按名恢复）；
- **暂停/耗时写入导致曲线终点超出屏幕**：RideRecorder 取消独立墙钟，改为 **engine tick 驱动 `recorder.sample(elapsed)`**，采样与引擎同源（暂停天然跳过、BLE 写耗时不再漂移）；图表 x 再 clamp 兜底；
- **扫描频繁启停失效**：`startScan` 幂等（已在扫直接返回）、不再每次清空列表，停止后保留已发现设备；新一轮扫描开始时才清列表；
- **CSV→FIT 静默失败**：历史导出改为 IO 线程 + 成功 Toast / 失败 AlertDialog；修掉 FIT dataSize 误设 16bit 上限（应为 u32，长骑行 >18 分钟会炸）。

**真机诊断记录（CAD70 踏频器 — 已搁置）**：CAD70 每秒发 **5 字节** CSC 帧（标准 crank-only 应 7 字节：flags+rev u32+event u16 2B），解析器判异常无读数。已加 hex 日志 + 无帧 2.5s 自动重订阅，仍无读数；用户要求**搁置**，待后续对照原始帧 hex 再定。

**已知未解决问题**：
- CAD70 5 字节帧无读数（搁置，见上）；
- ~~本机 `testDebugUnitTest` 会无限挂起~~ → **已定位（2026-09-12），不是环境问题**：
  `FitWriterTest.kt:76-78` 的 MiniFit 走查器只处理 Definition 消息，遇到第一个 Data
  消息时 `i += 0` 原地打转 → 测试线程纯自旋（jstack 实测 `elapsed=1015s cpu=1015s`，
  栈顶 `definitions(FitWriterTest.kt:77)`），JVM 不退出 → Gradle 永久等待。
  连带：JUnit 按类顺序执行，卡在 FitWriterTest 后其后的类一个没跑，
  `ZoneCalibrationTest` **从未执行过**。修法见 `CODE_REVIEW.md` A1（另建议给 Test 任务加 timeout）。
- 旋转/曲线/扫描等修复逻辑已编译打包，**真机验证待做**（期间 adb 掉线）。

### 📋 代码审查（2026-09-12）

全量审查结论见仓库根 **`CODE_REVIEW.md`**（含行号、触发条件、修法、动手顺序）。
最需要注意的三条：

1. **FTMS Indoor Bike Data 解析用了错的 flag 与错的字段顺序**（`BtParsers.kt:21-46`）：
   bit0 是 "More Data"（=0 时才有 Instantaneous Speed），且字段顺序为
   speed→cadence→power（现在按 power→cadence→speed 读）。
   用真实抓包 `44 02 52 03 5A 00 08 00 00`（8W/45rpm/8.5km/h）验证：现实现会读出
   850W/90rpm。X2 走 FE-C 所以从没暴露，但 `BtParsersTest` 是照着错误实现写的 → 永远绿灯。
2. **SessionEngine 少记最后一秒**（`SessionEngine.kt:83-88`）：到终点直接 break，不调 `onTick`。
3. **骑行只在 stop() 时落盘**（`RideRecorder`）：崩溃/被杀 = 整趟记录丢失；
   另在"最后一秒按停止"的窗口里 CSV 可能根本不写。

**验证清单（v0.3.0）**：连 X2+心率带后互不踢下线；设备页已连接项置顶且有名；连 CSC 后训练页踏频锁定 CSC；ERG 反复开关仍跟随；旋转不丢连接/课程；训练中曲线终点不越界；warmup/cooldown 梯形显示；历史导出→Download/CyclingTrainer/ 可见、可删除 CSV 不动 FIT；课程页选文件夹后 .zwo 自动列出（重启也自动加载）。

## 6. 协议速查（实现已内嵌，改 BLE 时对照）

- FTMS 握手：订阅 Control Point(0x2AD9,indicate)+Status(0x2ADA,notify) → 读 Feature(0x2ACC)/PowerRange(0x2AD8) → 写 Request Control(0x00) 等 response `[0x80,0x00,0x01]` → Start(0x07) → SetTargetPower(0x05)+int16LE W。
- result codes：success=1, notSupported=2, invalidParam=3, failed=4, notPermitted=5。
- Indoor Bike flags（**2026-09-12 更正，旧记录是错的**）：bit0=More Data（**0 表示本包含
  Instantaneous Speed**，1 表示分片/无 speed）；bit1=瞬时踏频；bit2=平均速度；bit3=平均踏频；
  bit4=总距离(u24)；bit5=阻力等级(s16)；bit6=**瞬时功率(s16)**；bit7=平均功率；bit8=能量；bit9=心率。
  字段在包内按规范表顺序排列：speed(u16,0.01km/h) → avg speed → cadence(u16) → avg cadence →
  distance(u24) → resistance(s16) → power(s16) → …。
  真实抓包 `44 02 52 03 5A 00 08 00 00` = flags 0x0244、8.5km/h、45rpm、8W。
  注意：规范写 cadence 分辨率 0.5rpm（原始值÷2），但多数实现直传整数 rpm —— 默认按 1:1 处理。
- CSC 踏频 rpm = Δ转数×60×1024/Δ事件时间（1/1024s tick，u16/u32 回绕已处理）。

## FIT 导出速查（session/FitWriter.kt，字段号已对照 Garmin 官方 Java SDK 21.x 校验）

- 文件头 14B：headerSize=14、protocol=0x20、profile version LE16=21.00、data size **u32**（offset 4–7，勿设 16bit）、".FIT"、header CRC(0..11)；文件尾 CRC-16/ARC（poly 0xA001 反射）覆盖全文件。
- 消息定义字节：def 头 `0x40|localType`；字段 base type **wire 字节**（多字节带 0x80 端序位）：enum=0x00、sint8=0x01、uint8=0x02、string=0x07、uint8z=0x0A、sint16=0x83、uint16=0x84、sint32=0x85、uint32=0x86、uint32z=0x8C、float32=0x88。字段须按 field number **升序**。
- 字段号要点：RECORD(20) hr=3 cadence=4 power=7 timestamp=253；LAP(19) total_elapsed/timer=7/8 avg_power=19 max=20 intensity=23 lap_trigger=24 sport=25；SESSION(18) sport=5 sub_sport=6 total_elapsed/timer=7/8 avg_hr=16 max_hr=17 avg_cad=18 max_cad=19 avg_power=20 max_power=21 first_lap=25 num_laps=26 trigger=28；ACTIVITY(34) total_timer=0 num_sessions=1 type=2 event=3 event_type=4 local_ts=5。
- 秒字段总时长以 **×1000 存 uint32**；枚举：file type activity=4、manufacturer dev=255、sport cycling=2、event timer=0、event_type start=0/stop=1、session trigger activity_end=0、lap_trigger time=1。FIT epoch = Unix − 631065600。

## 7. 代码风格约定

- 无第三方 BLE 库（官方 BluetoothGatt API），无 navigation 库（枚举 + rememberSaveable 切屏）。
- 新进度请在**本文件第 5 节下追加**或更新相关小节；环境变更记入 `ENV_FIXES.md`。
