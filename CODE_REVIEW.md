# 代码审查 — Cycling Trainer v0.3.0（2026-09-12）

> **修复状态（2026-09-12 同日）**：A1、A3–A10、B2–B8、B12 及报告中列出的测试/死代码问题均已修复
> 并提交（`git log`：`9a6c78f` → `33a0012`，6 个提交；基线 tag `v0.3.0-worktree`）。
> 单测从 30 个增至 **46 个，全绿**；`:app:assembleDebug` 通过，APK badging 为 v0.3.1 + 启动图标。
> **未做**：C1–C5 架构项与 B9（自动重连）、B10（前台服务）—— 它们是重构/新功能而非缺陷，
> 详见本文末尾"剩余工作"。下面是审查当时的原始结论，保留作为背景与依据。

审查范围：`app/src` 全量（33 个 main 源文件 + 6 个测试文件，约 4300 行），
以及对构建环境的实测（JDK 21 + Gradle 8.10.2，实跑 `:app:testDebugUnitTest`）。

结论分三部分：**A. 立即要修的 bug**、**B. 次级 bug/健壮性**、**C. 架构改动建议**。
每条都给了文件行号、触发条件和修法，可以直接照单开工。

---

## A. 立即修（按影响排序）

### A1. `testDebugUnitTest` 无限挂起 = 测试代码自己的死循环（已实测定位）

`agent.md` 第 198 行把这条记为"Gradle 任务会无限挂起……需另找时间排查构建环境"。
**不是环境问题**。我用 `jstack` 抓了运行中的 test worker：

```
"Test worker" prio=5 ... runnable   cpu=1015515.62ms elapsed=1015.83s
  at io.github.cyclingtrainer.app.session.FitWriterTest$MiniFit.definitions(FitWriterTest.kt:77)
  at ...FitWriterTest.message order fileId deviceInfo event records lap session activity(FitWriterTest.kt:107)
```

`FitWriterTest.kt:76-78`：

```kotlin
} else {
    i += 0 // data length unknown without definition; skip walk
}
```

MiniFit 的走查器只处理 Definition 消息（`0x40` 头）。遇到第一个 **Data 消息**（头字节
`0x00 | localType`）就走 `else` 分支，而那里 `i += 0` —— 位置不动、`while` 条件永远成立。
这个测试线程烧满一个核，JVM 永不退出，Gradle 永久等待。
（同类真实配置：worker 跑了 1015 秒、消耗 1015 秒 CPU，确认是纯自旋，不是死锁。）

**连带后果**：JUnit 按类顺序执行，卡死在 `FitWriterTest` 后，它之后的类**一个都没跑**。
`app/build/test-results/` 里只有 5 个 XML，`ZoneCalibrationTest`（3 个用例）从未执行过；
所以"12 个单测全绿"这个说法目前没有任何证据支持。

**修法**（两处都要）：

1. 给 MiniFit 加"按 definition 计算 payload 长度并跳过 Data 消息"的逻辑：

```kotlin
private val fieldSizes = HashMap<Int, List<Int>>()  // localType -> sizes
// 在 isDef 分支里存下 sizes；
// else 分支：
} else {
    val sizes = fieldSizes[lt] ?: break   // 未知定义就停，绝不原地打转
    i += 1 + sizes.sum()
}
```

2. 无论改不改，都给测试任务上保险，避免以后再出现"静默永久挂起"：

```kotlin
// app/build.gradle.kts
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))   // 超时即失败，而不是永远等
}
```

**顺带**：`.gradle-home/daemon/8.10.2/daemon-*.out.log` 里有
`NativeException: Couldn't open current thread, error = 5`（file watcher 起不来）与
`Unable to initialize metrics ... C:\Users\11988\.android`（无害噪音）。
watcher 报错可以用 `--no-watch-fs` 规避。

### A2. `FitWriterTest` 曾有 4/5 失败（FIT 导出整体不可用）——已确认修复，但需要回归验证

`app/build/test-results/...FitWriterTest.xml`（2026-09-05 23:28）记录：

```
tests=5 failures=4
java.lang.IllegalArgumentException: fields not sorted for message 20
  at FitWriter.writeMessage(FitWriter.kt:232)
```

也就是说当时**每一次 FIT 导出都会抛异常**（历史页点"导出 FIT"只能弹失败框）。
当前源码里 `recordFields` 已是升序（0,1,2,3,4,5,6,7,253），`FitWriter.kt` 的 mtime 是
2026-09-06 00:39，晚于那次测试 —— 排序校验已过，其余字段表（`lapFields`/`sessionFields`/
`activityFields`）我也逐条核对过均为严格升序。

**但要落地一条纪律**：这个回归能溜进去，是因为测试永久挂起、没人看见红灯。
A1 修完之前，任何"已修复"都是没有证据的。

### A3. SessionEngine 少记最后一秒（每个课程都短 1 个采样）

`SessionEngine.kt:81-92`：

```kotlin
val next = _elapsedSeconds.value + 1
if (total > 0 && next >= total) {
    _elapsedSeconds.value = total
    pushTarget(total - 1)
    _phase.value = Phase.FINISHED
    onFinished?.invoke()
    break                       // ← 这里 never 调用 onTick(total)
}
_elapsedSeconds.value = next
onTick?.invoke(next)
```

`onTick` 同时也是 `RideRecorder.sample()` 的驱动。走到终点那一秒直接 `break`，
于是 3600 秒的课程只写入 3599 行，CSV/FIT 时长都短 1 秒。

修法：`break` 之前补一次 `onTick?.invoke(total)`。

### A4. 极限竞态下整个训练记录丢失（stop 与 finish 抢 recorder）

- `onSessionFinished()`（`AppViewModel.kt:381-387`）：`recorder?.stop()` → 写 CSV → `recorder = null`
- `stopWorkout()`（`AppViewModel.kt:370-379`）：`recorder?.stop()` → `recorder = null`

两个都从主线程进入，正常不会交叉。但在**课程最后一秒正好按"停止"**这个窗口里，
`stopWorkout()` 可能先把 `recorder` 置空，随后引擎收尾走 `onSessionFinished()` 时
`recorder?.stop()` 已是 null —— **这一趟骑行的 CSV 从未落盘**。

修法：`RideRecorder.stop()` 做幂等（`if (!started) return existingFile` 或干脆
`synchronized` + 一次性标志），并且 `AppViewModel` 里只保留一个收尾入口
（`stopWorkout()` 与 `onSessionFinished()` 都调用同一个 `finishSession()`）。

### A5. 骑行只在结束时落盘 —— 崩溃/被杀 = 整趟记录蒸发

`RideRecorder` 全程把采样堆在内存 `_samples` 里，只有 `stop()` 才 `writeText`。
这是本轮最大的**数据安全**问题：长课程中按返回键杀掉 App、系统低内存回收、
或 A1 之外任何未捕获异常，都直接丢掉整趟骑行。

修法（也是 C 里前台服务的理由）：`sample()` 里按秒计数，每 30 秒把已有内容覆盖写一次
CSV（1 小时约 4KB，重写代价可忽略）；`start()` 时先写好表头，做到"任何时刻断电，
文件都是一个合法的、短一点点的 CSV"。

### A6. `sessionJob` 是死变量，防重入守卫从来没生效

`AppViewModel.kt:224` 声明、`309-310` 使用，**全工程没有任何一处赋值**
（`grep sessionJob` 只有这两处）。所以：

```kotlin
val job = sessionJob
if (job != null && job.isActive) return
```

恒等于 `if (false) return`。真正拦住重复启动的是 `SessionEngine.start()` 里的
`if (_phase.value == Phase.RUNNING) return` —— 但那个 early return 会让
`AppViewModel.startWorkout` 继续往下跑完：新建 recorder、`totalSeconds` 覆盖、
重新 `mgr.startWorkout()`。双重状态机各自为政，很容易出"按了两次开始，记录变成两截"。

修法：删掉 `sessionJob`，或者老老实实 `sessionJob = viewModelScope.launch { engine.run() }`；
更彻底的是把"会话是否活跃"收敛成**唯一**一个状态源（见 C4）。

### A7. `elapsedSeconds` 在停止时被清零 → 图表刚画完就没了

`stopWorkout()` 里 `elapsedSeconds.value = 0`，同时 `recorder = null`。
于是 `liveSamples` 变空、`elapsed` 归零，历史曲线在停止瞬间消失（CSV 里其实有数据，
用户看不到）。若想"结束页仍显示本次曲线"，需要保留最后一次 snapshot 直到下次开始。

### A8. `commandAndWait` 每个目标功率都等 indication（8 秒超时）—— ERG 可能整体滞后

`BluetoothLeManager.kt:420-440`：每次 `SetTargetPower` 都写特性 **并等待**
Control Point indication，超时 8000ms；整个窗口持有 `opMutex`。
`AppViewModel` 的 tick 回调是 `mgr.setTargetPower(watts)`，**每个 tick 都发**。

对"每条命令都回 indication"的骑行台没问题；对只在关键命令回 indication 的设备，
命令会排队，ERG 目标滞后数秒，且 tick 持续堆积。而且 `SessionEngine.start()` 里
`onTargetPower` 是 `suspend`，被 `runBlocking` 式的等待拖住会连带影响计时精度。

修法：目标功率改成**有界等待**（比如 500ms）或直接 fire-and-forget 写入，
不把目标功率当成必须应答的事务；只有 handshake（RequestControl/Start/Reset）才等 indication。

### A9. FTMS Indoor Bike Data 解析用了错的 flag 和错的字段顺序 ⚠️ 高危

**当前代码（`BtParsers.kt:21-46`）**：

```kotlin
if (flags and 0x0001 != 0) pos += 1      // 当作"跳过 1 字节"
if (flags and 0x0002 != 0) { power = u16(pos); pos += 2 }
if (flags and 0x0004 != 0) { cadence = u16(pos); pos += 2 }
if (flags and 0x0010 != 0) { speed = u16(pos) }
```

**规范（Bluetooth SIG FTMS 1.0 §4.9.1，`org.bluetooth.characteristic.indoor_bike_data`）**：

| bit | 含义 | 0 | 1 |
|---|---|---|---|
| 0 | More Data | **Instantaneous Speed 存在** | 分片，本包不含 speed |
| 1 | Instantaneous **Cadence** | 无 | 有 |
| 2 | Average Speed | 无 | 有 |
| 3 | Average Cadence | 无 | 有 |
| 4 | Total Distance (u24) | 无 | 有 |
| 5 | Resistance Level (s16) | 无 | 有 |
| 6 | Instantaneous **Power** (s16) | 无 | 有 |
| 7 | Average Power | 无 | 有 |

字段出现顺序 = 规范表顺序（speed → avg speed → cadence → avg cadence → distance →
resistance → power → …），且 **bit 0 = 0 时 speed 才在**。

拿一条真实骑行台的抓包验证（Stack Overflow #64002583，nRF Connect 实测）：

```
44 02 52 03 5A 00 08 00 00
flags = 0x0244 → bit0=0(有 speed) bit2=1(avg speed) bit6=1(power) bit9=1(HR)
speed     = u16@2 /100 = 8.5 km/h
cadence   = u16@4 *0.5 = 45 rpm
power     = i16@6      = 8 W
HR        = u8 @8      = 0
```

**用现在的解析器读同一串字节**，会得到：

| 字段 | 现在解析器 | 实际 |
|---|---|---|
| power | `u16@2` = 850 | 8 |
| cadence | `u16@4` = 90 | 45 |
| speed | 读 `u16@6`=8，再被 `data.size < pos+2` 判为 null | 8.5 |

也就是说 **FTMS 路径的功率/踏频/速度全是错的**。为什么至今没暴露：
X2 走 FE-C 隧道（`FecOverBle`），FTMS 分支从未在真机上跑过；
而 `BtParsersTest` 的测试帧（`flags=0x06, [FA 00][5A 00]`）是**照着这个错误实现写的**，
测试和实现一起错，所以永远绿灯。

**修法**：按上表重写解析（字段按规范顺序、bit0 取反判 speed），并补两条测试：
① 真实抓包 `44 02 52 03 5A 00 08 00 00` → 8W/45rpm/8.5km/h；
② `flags=0x0041`（bit0=1 → 无 speed、带 power）→ power 正确。

**需要留意的一处规范/实践分歧**：规范写 cadence 分辨率 0.5 rpm（原始值要 ÷2），
但 KBikeBLE、ESP32-FTMS-Bike 等大量实现直接传整数 rpm。建议默认按 **1:1（原始值即 rpm）**
实现，把 0.5 规则写成注释，或者做成可切换项 —— 别单方面 ÷2，否则对多数设备反而错。

### A10. ZwoParser 直接丢弃 `<FreeRide>`，课程时间轴被压缩

`ZwoParser.kt:58` 把 FreeRide 当"未知元素"忽略，`SegmentType` 里也没有对应类型。
后果不是"少画几根柱子"，而是**后移**：`Workout.powerFractionAt()` 按 `segments` 累计时间求值，
丢掉一段 FreeRide 后，它之后所有段的目标功率都会提前出现，且
`totalDurationSeconds` 总和缩短 —— 课程与骑行台时间轴错位。

修法：加 `SegmentType.FREE_RIDE`，FreeRide 生成一个 `powerStart=powerEnd=0.0` 的段占住时间；
`SessionEngine` 遇到该段时**不推目标**（等价于自由骑行的间隙），图表画成灰色低柱。

---

## B. 次级问题 / 健壮性

### B1. 传感器掉线后读数无限冻结常驻（脏数据入 CSV）

`DeviceManager.kt:157-201` 明确"只更新非 null 值"（v0.2.4 修闪烁，方向对），
但**没有任何过期淘汰**。骑行台中途断电/心率带滑脱后，`powerWatts` 永远停在最后一个值，
UI 当成实时读数显示，`RideRecorder` 还会把它写进 CSV —— 整段"幽灵数据"。

修法：给每个读数加时间戳，超过 ~3 秒没有新帧就置 null（或标记 stale）；
另外 `watchSession` 检测到角色断连时应**立即**清掉该角色的读数
（现在只有 `disconnectDevice` 里"三个角色全空才清"，见 `DeviceManager.kt:236-241`）。

### B2. `targetChannel` 首发丢失 → CSV 第一行 target 为空

`AppViewModel.kt:222` 用 `MutableSharedFlow(extraBufferCapacity = 8)`，
`SessionEngine.start()` 的第一件事就是 `pushTarget(0)` → `tryEmit`，
而 recorder 的收集协程是 `recorder.start()` 里才 `launch` 的。
SharedFlow 无重放，第一发大概率没有订阅者 → 丢掉。
改用 `MutableStateFlow`（UI 本来就是读最新值语义）即可，还省了 buffer 的猜测。

### B3. 主线程 I/O

- `RideRecorder.stop()` 的 `file.writeText(toCsv())` 从 `stopWorkout()` 同步调用（主线程）。
  一小时骑行约 15 万字符 + 文件写入。建议 `Dispatchers.IO`，或至少移到 `viewModelScope`。
- `HistoryScreen.kt:66-71` 的 `dir.listFiles{}` 在 `LaunchedEffect` 里（= 主线程），
  `f.delete()`、`f.length()` 同理。文件多时会有可感知卡顿。

### B4. `HistoryScreen` 没有刷新触发 —— 刚骑完的记录不出现

`LaunchedEffect(refreshed)` 只在删除后 `refreshed++`。骑完一串 tab 切到"历史"，
列表还是旧快照（因为该 composable 之前已组合过、`remember` 的值还在）。
修法：`LaunchedEffect(vm.sessionPhase.value == FINISHED)` 或监听会话结束事件后刷新；
更简洁的是把"记录目录"抽成一个 `StateFlow<List<File>>` 放在 repository 里，会话结束主动 invalidate。

### B5. 课程以 `name` 作为身份 —— 同名课程互相串台

`MainActivity.kt:90-94` 用 `workouts.firstOrNull { it.name == selectedWorkoutId }` 反查；
`WorkoutsScreen.kt:85-86` 用 `name + totalDurationSeconds` 当选中判定。
两个不同文件同名的 .zwo（很常见：`threshold.zwo` 复制两份不同内容）会：
选中态同时高亮、`LazyColumn` 的 `key` 冲突（Compose 会抛 duplicate key 异常）。

修法：`Workout` 加 `id`（文件 docId 或相对路径），选中的是 id 而不是名字。

### B6. 连接失败后 GATT 会话残留在 manager 里

`DeviceManager.connectRole()`（`DeviceManager.kt:101-147`）：`ble.connect()` 成功后，
若 `driver.connect()` / 特征订阅抛异常（比如 A9 那类设备、或 CCCD 写失败），
catch 里只设 `errorMessage`，**没有关掉那个 session**，也没从 `sessions` map 里移除。
设备行依然显示"连接"按钮，再点会拿到缓存 session（`ble.connect` 直接返回已连的），
用户体验上是"连不上也断不掉"。

修法：catch 分支里 `ble.disconnect(address)`，保证"要么成为某角色，要么释放"。

### B7. CSC 重订阅的竞态

`DeviceManager.kt:210-216` 延迟 2.5s 判断 `cscCadence.value == null` 才重订阅。
若设备正常但骑手刚好那 2.5 秒没踩（CSC 只在有事件时发帧），会误判为"无帧"再订阅一次。
影响小（重复写 CCCD 无害），但日志会误导后续排查。建议改成"是否收到过帧"的标志位，
而不是"cadence 是否非 null"。

### B8. 缺速度/距离 → FIT 里这两个字段永远是 invalid

`FitWriter.kt:336-347` 注释说"indoor ride without GPS"，所以 distance/speed 全部填 invalid。
但**骑行台其实给得出速度**：`FtmsTrainer.speedFlow`、`FecOverBle.speedFlow`
（page 16 已实现并测试）都写好了 —— 只是没人消费（见 D1）。
补上后：CSV 加 speed 列、FIT 填 `speed`(field 6) 与 `distance`(field 5，积分求和)，
导出到 Strava/Garmin 才有距离和速度。这是目前导出文件"看起来缺东西"的主因。

### B9. 没有自动重连

`watchSession` 只清角色状态，不重试。心率带滑落一次就得手动回设备页重连。
建议：已知设备地址持久化（`knownNames` 现在只在内存里），断连后按
指数退避重试 N 次，UI 显示"重连中"。

### B10. 前台服务权限声明了但没有服务

`AndroidManifest.xml:14-16` 声明 `FOREGROUND_SERVICE` +
`FOREGROUND_SERVICE_CONNECTED_DEVICE` + `POST_NOTIFICATIONS`，但**没有任何 `<service>`**。
这块权限完全没用上，而骑行场景恰恰最需要：手机熄屏/切后台时，
BLE 链路与 1Hz 计时都不受保护；进程被回收则 A5 的数据丢失直接发生。
→ 见 C4。

### B11. `MainActivity` 里权限逻辑第三份拷贝

`Permissions.required`（AppViewModel.kt:37-49）、`MainActivity.kt:54-61`、
`hasBlePermissions`（DevicesScreen.kt:253-263）三处各写一遍同样的 SDK 分支。
抽成一个 `isBleGranted(context)` + `rememberBlePermissionRequest()` 即可。

### B12. 其它零碎

- `gradle.properties` 建议加 `android.suppressUnsupportedCompileSdk=37`
  （AGP 8.7.3 只测到 35，每次构建都刷警告）。
- `versionCode=1 / versionName="0.1.0"`（`app/build.gradle.kts:16-17`）与
  设置页"v0.3.0"、agent.md 的 v0.3.0 不一致；设置页的版本号应读
  `BuildConfig.VERSION_NAME`，避免手写字符串再漂移。
- release 构建 `isMinifyEnabled = false`：一旦要发版，R8 没跑过 = 没验证过。
- 仓库根**没有 `.git`**（`git status` → not a git repository），但文档声称 GPL-3.0、
  且有 `.gitignore`。没有版本历史是这次"修了又坏、坏了又修"的根因之一 —— 建议第一件事就 `git init`。
- 预设课程要从 `preset-workouts/` 手动拷到 `Documents/CyclingTrainer`，没有"导入预设"入口。

---

## C. 架构改动建议

### C1. `AppViewModel` 是上帝对象，且 UI 直接依赖 BLE 层

`AppViewModel.kt` 一个人负责：SharedPreferences 持久化（FTP/LTHR/MaxHR/主题/课程目录）、
课程库加载、BLE 连接编排、会话生命周期（engine+recorder）、导出协调。
同时 `TrainScreen`/`DevicesScreen` 通过 `vm.deviceManager.powerWatts` **直接**读 BLE 层
的 `StateFlow`、直接调 `vm.ble.stopScan()` / `vm.ble.sweepStaleSessions()`。

后果：任何 UI 都跑不了不接真蓝牙的测试；状态跨越 4 层任意读写，改一处要考虑四处。
（本轮 v0.3.0 的"设备行丢失 / 读数不刷新"就是这类结构病：`TrainScreen` 一开始用
`.value` 直读而不是 `collectAsState`，注释里也承认了。）

**建议的切分**（不引入 DI 框架、不引入 navigation 库，保持项目现有取舍）：

```
AppContainer (Application 持有，手工构造)
├── SettingsRepository      prefs 读写 + StateFlow，唯一持久化入口
├── CourseRepository        SAF 目录、.zwo 加载、Workout.id
├── ConnectionController    扫描/连接/断连/重连，输出 ConnectionState
├── SessionController       SessionEngine + RideRecorder + 导出，输出 SessionState
└── RideRepository          rides 目录列表、删除、FIT 导出
```

每个 `*Screen(vm)` 改成 `*Screen(state, onEvent)`：屏幕只收**一个不可变 UiState**，
只发**一个事件 sealed class**。UI 里不再出现任何 `deviceManager.*`。

### C2. 每个屏幕一个 `UiState`，别让屏幕自己 collect 十来个 flow

典型样本：`TrainScreen.kt:60-81` 收集了 13 个 flow，还在 composable 里做派生
（`hrRefBpm = if (hrRef == LTHR) lthr else maxHr`）。派生逻辑散在 UI 里，
就是"同一个 bug 修三遍"的来源。

```kotlin
data class TrainUiState(
    val phase: Phase, val elapsed: Int, val total: Int,
    val power: Int?, val cadence: Double?, val hr: Int?,
    val targetWatts: Int?, val ergEnabled: Boolean,
    val zones: ZoneSnapshot, val samples: List<RideSample>,
    val connection: ConnectionSummary,
)
```

在 ViewModel 里用 `combine(...).stateIn(...)` 产出**一个** StateFlow，
`TrainScreen(state = state, onEvent = ...)`。顺带解决 A7/A8 类"读数与图表不同步"的问题。

### C3. 消除 `HrZones` / `PowerZones` 的整段重复

两个 object 的表结构、`zoneOf`、`zoneLabel`、`zoneText` 几乎逐行相同，
只有区间表和单位文案不同；`TrainScreen` 里因此出现了 `if (hrRef == LTHR) ... else ...` 的分支。
抽成 `data class ZoneTable(unit, zones)` + 一个求值函数，两个实例（`HrZones.LTHR/MaxHR`、`PowerZones`）。
收益：以后调区间只改一处；`ZoneCalibrationTest` 也能参数化跑全套表。

### C4. 会话需要一个"家" —— 前台服务 / 长生命周期容器

现在会话寄生在 `MainActivity` 的 `AppViewModel` 上（`onCleared` 才 release，`AppViewModel.kt:131-138`），
这解释了旋转屏问题修得很费劲，也决定了熄屏/后台无保障、崩溃丢数据（A5）。

建议：`RideSessionService`（`foregroundServiceType="connectedDevice"`）承载
`SessionController` + `RideRecorder`，用常驻通知显示 目标功率/计时/暂停 按钮。
Activity 变成纯展示层，随时可死可重建；BLE 与记录在服务里独立于 UI 生命周期。
这同时把已经声明但闲置的三个权限（B10）用起来。

### C5. BLE 层：拆类、去魔法字符串、补重连

- `BluetoothLeManager.GattSession`（inner class，约 230 行）应提成顶层 `GattSession`：
  `BluetoothLeManager` 管扫描/会话表，`GattSession`（连接+串行化 I/O+通知流）单独可测。
- `DeviceManager.connectRole(role: String)` 用 **字符串** 当角色名（`"trainer"`/`"hr"`/`"csc"`），
  且 `roleLabel()` 与 `DevicesScreen.deviceKind()/kindLabel()` 各自维护一份映射 —— 改一处漏一处。
  换 `enum class Role { TRAINER, HR, CSC }` + 一个 `BleDevice.role()` 判定函数，两边共用。
- 返回值语义要一致：既然 `connectRole` 已经拿到了 `name`，就让 `ble.connect(address, name)`
  自己记名字，别让调用方再补一刀（`BleTypes.kt:32-36` 的 `DeviceHandle` 接口是死代码）。
- 重连（B9）属于这一层的职责，放进来后 UI 只显示状态。

### C6. 数据记录：自己的时钟 + 增量落盘 + 独立的记录模型

- **加 `flush()`**（A5）：每 30 秒覆盖写一次，`stop()` 收尾。
- `RideSample` 目前没有 speed/distance（B8）。建议一步到位：

```kotlin
data class RideSample(
    val elapsedSeconds: Int,
    val powerWatts: Int?, val cadenceRpm: Double?, val heartRateBpm: Int?,
    val speedKmh: Double? = null,      // 新增
    val targetWatts: Int? = null,
)
```

`RideRecorder` 稍后会自然长出"平均/最大功率、NP、TSS"这类统计 —— 现在就把它
从 `SessionEngine` 的 tick 里松绑（engine 只发事件，recorder 决定何时采样），
否则每次加指标都要动引擎。

### C7. 课程身份与来源

- `Workout` 加 `id`（B5）。
- `CourseSource.kt:44-57` 有两处 Kotlin 优先级陷阱（`?: continue` / `?: ""` 与 `let` 混写），
  可读性差且极易改错；重写成直白的 `while (c.moveToNext())` 取值 + 显式 null 判断。
- 课程库现在只能"手动刷新"。可加 `ContentObserver` 或回到 App 时自动重扫，
  配合 C1 的 `CourseRepository` 很自然。

### C8. 测试策略：先让测试能跑，再让它有意义

顺序很重要：

1. **修 A1**（否则一切免谈）→ 跑一次全绿，把结果写进 `agent.md`。
2. **补协议测试**：A9 给出的真实 FTMS 抓包字节、FE-C page 25/16 的
   "完整 13 字节帧 + 校验和"用例（现在 `FecParsersTest` 的帧尾字节是随手填的，
   校验和没被验证过）。
3. **让实现和测试分别来自不同来源**：现在的 `BtParsersTest` 是实现的自画像。
   凡是解析协议，测试数据一律引用外部抓包/规范，不自己造。
4. `ZoneCalibrationTest` 目前**从未执行过**，修完 A1 要确认它进了报告。
5. 给 `Test` 任务加 `timeout`（A1 第 2 点），让"挂起"永远表现为"失败"。

---

## D. 死代码 / 未接线（附清单）

| 位置 | 情况 |
|---|---|
| `FtmsTrainer.speedFlow`、`FecOverBle.speedFlow` | 已实现、有单测，**无人消费**（B8 的现成燃料） |
| `BtParsers.parseFtmsFeatures`、`Int.signed16()`、`LeReader` / `LeWriter` | 全无引用 |
| `BleTypes.SensorValues`、`DeviceState`、`DeviceHandle` | 全无引用（`DeviceState` 是个从没用过的连接状态机） |
| `DeviceManager.trainerReady` | 只在 DeviceManager 内部写，UI 从不读 |
| `FtmsTrainer.hasControl` / `requestWithTimeout` | 只写不读 |
| `AppViewModel.sessionJob` | 声明 + 读，从不赋值（A6） |
| `MainActivity` 权限数组 | 与 `Permissions.required` 重复（B11） |
| `preset-workouts/README.md` | 存在；但没有把预设导入 App 的入口 |

---

## E. 建议的动手顺序

| 优先级 | 事项 | 理由 |
|---|---|---|
| 1 | `git init` + 首次提交 | 目前无版本历史，任何重构都没有退路 |
| 2 | A1（测试死循环）+ 测试超时 | 恢复"红灯有意义"这个前提 |
| 3 | A9（FTMS 解析）+ 真实抓包测试 | 唯一会**静默产生错误数据**的 bug |
| 4 | A3 / A4 / A5（少一秒 / 竞态丢文件 / 崩溃丢整趟） | 数据完整性，都是一行~几十行的小改 |
| 5 | A6 / A7 / A8 / B1 / B2 | 会话与读数的正确性收尾 |
| 6 | B8 + D（接线 speed，清死代码） | 低成本、高感知（导出文件变"完整"） |
| 7 | C1 / C2（UiState 收敛） | 为 C4 铺路；此后每次改动都更省力 |
| 8 | C4（前台服务）+ B9（重连） | 真机长距离骑行体验的关键 |
| 9 | A10 / B5 / B6 / B7 / B12、C3、C5~C8 | 逐步清理 |

---

## F. 本次审查做过的实测（可复现）

```powershell
$env:JAVA_HOME='D:\coding\Android\jdk-21\jdk-21.0.12.1+1'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='D:\coding\cycling-trainer\.gradle-home'
$env:ANDROID_USER_HOME='D:\coding\cycling-trainer\.android'
$env:TMP='D:\coding\cycling-trainer\.tmp'; $env:TEMP=$env:TMP

.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon   # → 挂起（A1）
& "$env:JAVA_HOME\bin\jstack.exe" <test worker pid>                # → 定位到 FitWriterTest.kt:77
```

- 观察到的现象：`Test worker` 线程 `runnable`，`elapsed=1015s` 而 `cpu=1015s`（纯自旋），
  栈顶即 `MiniFit.definitions(FitWriterTest.kt:77)`。
- 历史测试报告：`app/build/test-results/testDebugUnitTest/`（2026-09-05）5 个类，
  `FitWriterTest` 4 处 `fields not sorted for message 20`；`ZoneCalibrationTest` 缺失。
- 编译产物是**旧的**：`app-debug.apk`（2026-09-09 01:00）早于最后一批源码改动
  （2026-09-09 01:00:26 等），当前 APK 不能代表工作区代码。

---

## G. 修复后的复现命令（2026-09-12）

```powershell
# 环境（每次新 shell 必设，见 agent.md §2）
$env:JAVA_HOME='D:\coding\Android\jdk-21\jdk-21.0.12.1+1'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='D:\coding\cycling-trainer\.gradle-home'
$env:ANDROID_USER_HOME='D:\coding\cycling-trainer\.android'
$env:TMP='D:\coding\cycling-trainer\.tmp'; $env:TEMP=$env:TMP

# 单测：46 个，约 30 秒跑完（--no-watch-fs 规避本机 file-watcher 原生报错）
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon --no-watch-fs

# 报告（8 个测试类）
# app\build\test-results\testDebugUnitTest\*.xml

# APK
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon --no-watch-fs
# → app\build\outputs\apk\debug\app-debug.apk  (v0.3.1, 约 54MB)
```

若测试再次"挂起"，现在会**10 分钟后失败**而不是永久阻塞（`app/build.gradle.kts` 里的
`tasks.withType<Test> { timeout }`）。真挂起时用 `jstack <test worker pid>` 定位栈顶。

---

## H. 剩余工作（未做，按建议顺序）

以下都是**重构或新功能**，不是已确认的缺陷。做之前请先在真机上跑一遍 v0.3.1。

| 顺序 | 项 | 说明 | 风险 |
|---|---|---|---|
| 1 | C2 UiState 收敛 | `TrainScreen` 现收 13 个 flow 并在 composable 里派生；改成 ViewModel 产出一个不可变 `TrainUiState` | 中：触碰全部 UI 组合，无 instrumentation 测试兜底 |
| 2 | C1 repository 分层 | settings / course / ride / connection 四个 repository，UI 不再直接读 `deviceManager.*` | 中：同上 |
| 3 | C4 前台服务 | 会话搬进 `RideSessionService`（`connectedDevice`），顺带用起闲置的 `FOREGROUND_SERVICE*` 权限 | 中高：涉及生命周期与通知，必须真机验 |
| 4 | B9 自动重连 | 已知设备地址持久化 + 指数退避重连 | 低 |
| 5 | C3 Zone 去重 | `HrZones` / `PowerZones` 抽成 `ZoneTable` | 低（有 `ZoneCalibrationTest` 兜底） |
| 6 | C5 BLE 层整理 | `GattSession` 提成顶层类；角色字符串换 `enum class Role`；`deviceKind`/`roleLabel` 合并 | 低-中 |
| 7 | B11 权限去重 | 三份 BLE 权限判断合成一处 | 低 |
| 8 | C7 课程库自动刷新 | `ContentObserver` 或回前台重扫 | 低 |

**真机验证仍是硬约束**：A9（FTMS 解析）只对抓包验证过，X2 走 FE-C；A5（增量落盘）、
A8（ERG 不等 indication）、B6/B7 都只有单测覆盖，需要真机确认。
