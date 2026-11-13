# 高级设置（P145 车机自用 Hook 控制台）

吉利用 P145 车机（Flyme Auto / Android 9 / MTK）上自用的一个系统级小工具，
用 Frida 把两件事做成开关：**屏蔽「智能巡航已退出」语音播报**、**让音量键/方控/旋钮始终只调媒体音量**。

> 平台签名、直连本机 adb 拿 root、自带后台守护，**不依赖 EVCC 或任何第三方 App**。

- 包名：`com.p145.advancedsettings`
- 当前版本：**1.6**（versionCode 7，以 `AndroidManifest.xml` 为准）
- APK 体积：约 62 KB
- 目标设备：Android 9（API 28）、1920×1080、density 160、adbd 常驻 root

---

## 一、它能干什么

| 功能 | 说明 |
|---|---|
| 屏蔽巡航退出播报 | Hook DuerOS `SpeechManagerImpl.speakTts(String, boolean, int)`，命中「智能巡航已退出」直接 return：不合成、不播放、不抢音频焦点 |
| 音量锁媒体 | 三个入口分别 hook，车机音量键 / 方向盘按键 / 旋钮都只改「媒体组」(group 0) |
| 后台守护 | 前台服务每 N 秒巡检（默认 5s），目标进程重启后自动补挂 hook |
| 开机自启 | 监听 `BOOT_COMPLETED`，开机自动拉起守护并注入 |
| 广播 / 命令文件 | `am broadcast -a com.p145.adv.XXX`，或往 `/data/local/p145adv/cmd` 写一行命令 |
| 状态可视化 | ADB 通道 / Root / Frida 运行时 / 每个目标进程的注入状态与 PID |

**音量组编号**：`0` 媒体 · `1` 通话 · `2` 语音 · `3` 系统提示 · `4` 导航

**三个 hook 点**：

| 入口 | 目标进程 | Hook 的方法 |
|---|---|---|
| 车机音量键 | `com.android.car` | `CarAudioService.getSuggestedAudioUsage()` → 恒返回 1 |
| 方向盘按键 | `com.flyme.auto.keyserver` | `CarAudioManagerWrapper.getCurrentActiveStreamEntity(Context)` → 恒返回媒体实体 |
| 旋钮 | `com.flyme.auto.knobservice` | `CarAudioManager.setGroupVolume(int,int,int)` → 非 0 组改 0 |

---

## 二、它是怎么拿到 root 的（关键设计）

本机 adbd 常驻 root（`adb shell id` → `uid=0`），`ro.adb.secure=0`（免密钥认证），
且 adbd 监听 `:::5555`（含 loopback）。

所以 App 内置了一个**极简 ADB 协议客户端**（`core/AdbClient.java`，约 250 行）：
直接对 `127.0.0.1:5555` 说 ADB 协议（`CNXN → OPEN shell:... → WRTE`），拿到一个 root shell。
**所有操作（部署脚本、`pidof`、注入、kill 进程）都走这条通道**，App 自身不需要 root、不需要 su。

平台签名（`keys/platform.pk8` + `platform.x509.pem`，CN=Android）的意义：
拿到 signature 级权限、后续想扩展 `WRITE_SECURE_SETTINGS` 之类能力不用再折腾授权。

---

## 三、目录结构

```
P145-AdvancedSettings/
├── README.md                 本文件
├── .gitignore                忽略规则（私钥 / 产物 / 临时 / 历史资料）
├── AndroidManifest.xml       清单（targetSdk 25，见下方说明）
├── build.ps1                 一键构建（aapt2 + javac + d8 + apksigner，不用 gradle）
├── src/                      源码（唯一参与编译的代码目录）
│   └── com/p145/advancedsettings/
│       ├── App.java
│       ├── MainActivity.java         界面 + 菜单 + 折叠详情
│       ├── GuardService.java         前台守护服务
│       ├── BootReceiver.java         开机自启
│       ├── CmdReceiver.java          广播入口
│       ├── core/
│       │   ├── AdbClient.java        ADB 协议客户端
│       │   ├── Root.java             root shell 封装
│       │   ├── HookManager.java      部署 / 注入 / 巡检 / 状态
│       │   ├── Commands.java         命令分发（广播与命令文件共用）
│       │   ├── Targets.java          目标进程与脚本定义
│       │   └── Prefs.java            配置
│       └── ui/PillSwitch.java        自绘胶囊开关
├── res/                      布局、颜色、圆角卡片等资源
├── assets/                   注入用的 Frida 脚本（会部署到车机）
│   ├── hook_icc.js
│   └── hook_volume_all.js
├── docs/
│   ├── screenshots/          界面截图
│   └── P145车机改造记录.md   改造全过程技术记录（踩坑、失败方案、日志证据）
├── tools/
│   └── keep-awake.ps1        调试保活（防息屏 / 深睡巡检）
├── reference/                ⚠️ 历史资料（反编译产物/工具链，约 900 MB）
│   ├── README.md             各子目录说明
│   ├── tools/                jadx / apktool / uber-apk-signer
│   ├── icc/ volume/ voice/   三大改造方向的调研产物
│   ├── magisk/ avm/ launcher/ lingshi/ audio/
├── .dsh/skills/              项目需求技能（需求与约束的真相源）
├── keys/                     平台签名材料（⚠️ 私钥，不入库、勿外传）
├── build/                    构建中间产物（不入库，可随手删）
└── dist/advanced-settings.apk  发布副本（不入库）
```

> **`reference/` 不参与构建**：`build.ps1` 只读 `src/`、`res/`、`assets/`、`AndroidManifest.xml`，
> 打出来的 APK 里不会有任何历史资料（实测 28 个条目，零命中）。
> 该目录整体被 `.gitignore` 忽略（体积大 + 多为反编译产物），详见 `reference/README.md`。

> 仓库用本地 git（`main` 分支）。`keys/`、`build/`、`dist/`、`reference/`、`tmp/`、`logs/` 已被 `.gitignore` 排除；
> 需求与不可动的约束记在 `.dsh/skills/project-requirements/SKILL.md`，改动前先读。

> 仓库用本地 git（`main` 分支）。`keys/`、`build/`、`dist/`、`tmp/`、`logs/` 已被 `.gitignore` 排除；
> 需求与不可动的约束记在 `.dsh/skills/project-requirements/SKILL.md`，改动前先读。

---

## 四、构建

### 依赖

| 组件 | 版本 / 路径 |
|---|---|
| JDK | 17 |
| Android SDK build-tools | `D:\ARM\Android\Sdk\build-tools\36.0.0` |
| Android platform | `D:\ARM\Android\Sdk\platforms\android-34\android.jar` |

`build.ps1` 里这两个路径是写死的，换机器要改开头几个变量。

### 构建

```powershell
cd D:\project\P145-AdvancedSettings
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
# 产物: build\advanced-settings.apk
```

流程：`aapt2 compile → aapt2 link（-A assets）→ javac → d8 → 追加 classes.dex → zipalign → apksigner（平台签名）`。

### 安装

```powershell
$adb = "D:\ARM\Android\Sdk\platform-tools\adb.exe"
& $adb connect 10.0.0.1:5555
& $adb -s 10.0.0.1:5555 install -r -g .\build\advanced-settings.apk
```

装完在车机**应用列表**里能找到「高级设置」。

---

## 五、使用

### 界面

左侧菜单四类：**概览 / 语音 / 媒体 / 关于**

- **概览**：ADB / Root / Frida 三张状态卡、功能摘要（点击跳转）、后台守护开关与巡检间隔、操作（立即注入 / 重启目标 / 日志）
- **语音 / 媒体**：一张功能卡，短标题 + 开关，点「展开详情」看技术细节（展开状态会记住）
- **关于**：版本信息、广播命令速查（可复制）、最近事件

功能开关关掉时会**自动重启对应目标进程**——因为 hook 一旦注入就常驻，只能靠重启进程卸载。

### 广播命令

```powershell
& $adb -s $d shell "am broadcast -a com.p145.adv.STATUS"     # 查状态
& $adb -s $d shell "am broadcast -a com.p145.adv.INJECT"     # 立即注入
& $adb -s $d shell "am broadcast -a com.p145.adv.START"      # 开守护
& $adb -s $d shell "am broadcast -a com.p145.adv.STOP"       # 停守护
& $adb -s $d shell "am broadcast -a com.p145.adv.ICC_OFF"    # 关语音屏蔽
& $adb -s $d shell "am broadcast -a com.p145.adv.VOL_ON"     # 开音量锁媒体
& $adb -s $d shell "am broadcast -a com.p145.adv.CMD --es cmd status"
```

不受后台限制的显式写法：

```powershell
& $adb -s $d shell "am broadcast -n com.p145.advancedsettings/.CmdReceiver -a com.p145.adv.CMD --es cmd inject"
```

### 命令文件通道（最稳，任何脚本都能用）

```powershell
& $adb -s $d shell "echo vol_off > /data/local/p145adv/cmd"
& $adb -s $d shell "cat /data/local/p145adv/cmd.log"     # 执行历史
```

支持的 cmd：`status` / `inject` / `start` / `stop` / `detach` / `icc_on` / `icc_off` / `vol_on` / `vol_off`

### 车机上的运行时文件

```
/data/local/p145adv/
├── frida-inject16      16.7.19 注入器（53 MB，首启从别处复制或手动放）
├── hook_icc.js         ICC 播报屏蔽
├── hook_volume_all.js  音量锁媒体（按进程名分支）
├── hits.log            hook 命中记录
├── status.json         最新状态（脚本/排障读这个）
├── cmd / cmd.log       命令文件与历史
└── logs/               每个目标的注入器原始输出
```

---

### 开机自启与外部唤醒

**App 自己会尝试自启**（`BootReceiver`）：

除了标准 `BOOT_COMPLETED`，还监听了这台车机可能用的厂商广播 ——
`com.geely.intent.action.SYSTEM_READY / WAKEUP`、`com.ecarx.intent.action.SYSTEM_READY / ACC_ON / IGN_ON / WAKEUP / STR_RESUME / POWER_ON`、
`com.lynkco.intent.action.SYSTEM_READY / WAKEUP / STR_RESUME`、`ecarx.intent.action.power.STRMODE`、
`MY_PACKAGE_REPLACED`、`QUICKBOOT_POWERON` 等。

实测：2026-11-07 20:26 那次车机重启，自启动成功（守护起来、4 个 hook 自动注入）；
但 **11-08 11:36 那次开机没起来**（App 进程 11:39 才由界面启动拉起），说明车机对第三方 App 的开机广播不总是可靠。

**所以推荐加一条外部兜底**（不依赖 App 自己）：

```powershell
am start-foreground-service -n com.p145.advancedsettings/.GuardService
```

**三种拉起方式的区别**（App 关于页里也写了）：

| 方式 | 是不是广播 | 正常状态 | 被强行停止(`stopped=true`) | 弹 UI |
|---|---|---|---|---|
| `am broadcast -a com.p145.adv.START` / `INJECT` | 是 | ✅ | ❌ 广播被丢弃 | 否 |
| `am start-foreground-service -n .../.GuardService` | 不是（直接启动服务） | ✅ | ✅ 还能清掉 stopped | 否 |
| `am start -n .../.MainActivity` | 不是（启动界面） | ✅ | ✅ | 会弹界面 |

这条命令**实测可以突破 `stopped=true`**（被强行停止过的状态）：

| 场景 | 结果 |
|---|---|
| `force-stop` 后 → `am broadcast -a com.p145.adv.START` | ❌ 广播被丢弃，进程不起 |
| `force-stop` 后 → `am start-foreground-service -n .../.GuardService` | ✅ 进程拉起、`stopped` 变 false、守护启动、4/4 注入，**不弹 UI** |

放在 **EVCC** 里（它的 Lua 支持 `shell.exec`）：

```lua
-- 放在 /sdcard/Download/EVCC/scripts/lua/adv_watchdog.lua
--@autostart true
--@name ADV-Watchdog
-- 幂等：服务已在跑时重复执行无副作用
shell.exec("am start-foreground-service -n com.p145.advancedsettings/.GuardService", 15000)
```

**已落地**：EVCC（v3.4.25）自动化模块里建了一条规则 `ADV-Boot` ——
触发器「开机启动」→ 动作「执行Shell/ADB命令」→ 上面那条 `am start-foreground-service`。
2026-11-08 13:25 实测：`force-stop`（stopped=true）后执行该规则，进程被拉起、`stopped` 变 false、4/4 hook 恢复。
想更稳可以再给它加一个「屏幕亮起」触发器，命令幂等，亮屏兜底一次开销可忽略。
这样即使 App 被系统清理、被强行停止，也会在一个周期内自动恢复。

> 反过来：如果你更喜欢"一条命令启动全部"，用 `am broadcast -a com.p145.adv.INJECT` 也可以——
> 它现在会先把守护拉起来（并把守护开关置开），再立刻同步注入所有已开启的功能。

### 性能监控（资源排行）

左侧菜单「性能」页，Windows 任务管理器风格的表格 —— 一屏同时看到四个维度：

| 汇总卡 | 口径 |
|---|---|
| CPU 总占用 | 所有进程 CPU% 之和 ÷ 核数 |
| 内存 合计 | 所有进程 RSS 之和 |
| 磁盘 累计 I/O | `/proc/<pid>/io` 的 `read_bytes + write_bytes` |
| 网络 累计流量 | `/proc/net/xt_qtaguid/stats` 按 uid 汇总（已去重） |

表格列：**应用/进程**（图标 + 应用名）· CPU · 内存 · 磁盘 · 网络。
点任意**表头**按该列排序，再点一次切升/降序（表头显示 `↓` / `↑`）。

数据来源：`assets/perf_collect.sh` 在设备端一条命令采完，App 只做解析 ——
两次 `/proc/*/stat` 采样（间隔 1s）算瞬时 CPU%，绕开 `dumpsys meminfo`（实测单次 7.5s 太慢）。

**口径说明**：

- 磁盘 / 网络是**累计值**不是速率，只增不减；
- 网络按 uid 统计，同一 uid 下的多个进程会显示同一个值；
- 内存用 RSS 累加，共享库被重复计算，合计会大于物理内存；
- 应用列约占一半宽度，其余四列等分；字号按车机屏放大（名称 18sp / 数值 17sp / 表头 16sp），一屏约 8 行；
- 显示前 24 个进程（可滚动），点「刷新」重新采集（约 3~5 秒）。

### 调试时防息屏

车机**不吃** Android 的 `screen_off_timeout`（实测设成 `0` 照样会息屏），熄灭 / 深睡由**车辆电源策略**控制：

- **着车 / ACC ON**：普通息屏，`svc power stayon true` 就能顶住（实测 150 秒不睡）；
- **熄火停放**：进入 **GarageMode**（`GarageModeTimeWindow 600000 ms`，10 分钟窗口），窗口结束直接 **deep sleep**
  —— 实测停一晚，`mLastSleepTime` 到 `mLastWakeTime` 相隔 8 小时，logcat 整个断档。

三种做法，按需选：

```powershell
# ① 最轻：scrcpy 自带参数（等价于 svc power stayon true）
D:\ARM\Android\scrcpy-win64-v5.0.1\scrcpy.exe -s 10.0.0.1:5555 --stay-awake

# ② 手动设一次
adb -s 10.0.0.1:5555 shell "svc power stayon true"

# ③ 长期停放调试：stayon + root 内核 wake_lock（挡住 suspend）
tools\keep-awake.ps1 -WakeLock

# 收工还原
tools\keep-awake.ps1 -Restore
```

> `tools\keep-awake.ps1` 会每 60 秒巡检一次，发现已 Asleep 就 `keyevent WAKEUP`、stayon 掉了就重设，
> `-WakeLock` 额外在 `/sys/power/wake_lock` 持锁。**持锁会耗车机电瓶，长时间停放记得 -Restore。**

## 六、排障

```powershell
# 一眼看状态
& $adb -s $d shell "cat /data/local/p145adv/status.json"

# hook 有没有命中
& $adb -s $d shell "tail -5 /data/local/p145adv/hits.log"
# 正常应看到: <时间戳> CAR usage=media  /  KEY group=0  /  ICC DROP

# 注入器输出
& $adb -s $d shell "cat /data/local/p145adv/logs/icc.log"

# App 日志
& $adb -s $d logcat -d -s P145ADV

# 音量组当前值
& $adb -s $d shell 'for g in 0 1 2 3 4; do echo "g$g=$(settings get global android.car.VOLUME_GROUP/$g)"; done'
```

| 现象 | 处理 |
|---|---|
| 状态卡 ADB 未连接 | 确认 adbd 在跑：`netstat -lntp \| grep 5555` |
| Frida 运行时「缺失」 | 把 `frida-inject16`（16.7.19）放到 `/data/local/p145adv/` 并 `chmod 755` |
| 某个目标「未注入」 | 点「立即注入」；看 `logs/<key>.log` |
| 被强行停止过 | 打开一次 App 即可恢复（`stopped=true` 时广播也收不到） |
| 关开关后 hook 还在 | 正常，关开关会重启目标进程；没重启就是因为进程没被杀掉 |

---

## 七、已知限制 / 注意

1. **依赖 `ro.adb.secure=0`**。如果哪版固件打开 adb 密钥认证，需要给 `AdbClient` 补 RSA 认证（公钥写 `/data/misc/adb/adb_keys`）。
2. **targetSdk 25** 是故意的：Android 8+ 对隐式广播有后台限制，targetSdk 25 才能让 `am broadcast -a ...` 直接送到清单注册的接收器。
3. **Frida 版本敏感**：DuerOS 只能用 **16.7.19 + attach**；17.23.3 注入会在 `bds-engine` 线程 SIGSEGV（表现为「语音助手一唤醒就没了」）。
4. **不要对 App 用「强行停止」**：会被标记 `stopped=true`，广播与开机自启都失效，打开一次 App 才能恢复。
5. 关掉开关后 hook 仍会存活到目标进程重启为止，这不是 bug，是 Frida 的机制。
6. `keys/` 里是**平台签名私钥**，只在本机使用，别提交到公开仓库。

---

## 八、版本历史

| 版本 | 内容 |
|---|---|
| 1.0 | 首版：状态卡、两个功能开关、前台守护、广播 + 命令文件、深色 UI |
| 1.1 | 左侧分类菜单（概览 / 语音播报 / 音量控制 / 工具·广播 / 关于） |
| 1.2 | 菜单收敛为四类（概览 / 语音 / 媒体 / 关于），广播与日志移入关于页 |
| 1.3 | 精简页面：删掉说明卡与占位卡，媒体页标题写完整 |
| 1.4 | 功能卡改成「短标题 + 可展开详情」，展开状态按页记忆 |

> 1.5 / 1.6 两次改动没有单独记档，以 `AndroidManifest.xml` 的版本号为准（当前 1.6 / versionCode 7）。

---

## 九、相关文档

完整的技术记录（踩坑、失败方案、日志证据）在：

```
D:\ARM\Auto\P145车机改造记录.md        第 15 章
```

> 该文档只在本地维护（2026-11-08 起不再往车机 `/sdcard/Download/` 推副本）。
