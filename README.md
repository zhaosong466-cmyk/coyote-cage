# 画地为牢

用手机摄像头圈一块区域，人站在里面。身体判定点离开区域时，按设定的节奏向已配对的外设下发输出。
强度、间隔、时长、递增节奏、波形全部可调。

Android（Kotlin + CameraX + MediaPipe Pose）/ Web（单文件）/ 外设联动走 DG-LAB V4 Socket。

---

## 1. 工作原理

```
┌──────────────┐   CameraX 640×480    ┌────────────────────┐
│  手机摄像头   │ ───────────────────> │ Pose Landmarker    │
└──────────────┘                      │ (lite, 33 关键点)   │
                                      └─────────┬──────────┘
                                                │ 归一化坐标
                                      ┌─────────▼──────────┐
                                      │ 判定：点是否在框内   │
                                      │ 越界累计 → 输出循环  │
                                      └─────────┬──────────┘
                                                │ device.op
                                      ┌─────────▼──────────┐
                                      │ 本机 WebSocket 控制端│
                                      └─────────┬──────────┘
                                                │ wss://trex.dungeon-lab.cn/v4
                                      ┌─────────▼──────────┐
                                      │    外设 App        │ ──蓝牙──> 外设
                                      └────────────────────┘
```

本机是 **V4 协议里的「控制方」**：连上中继拿到 `targetId`，生成二维码；
外设 App 扫码后成为「被控方」接进来；之后所有强度/波形指令都走 `device.op` RPC。

## 2. 为什么选这个模型

| 方案 | 体积 | 说明 |
|---|---|---|
| **MediaPipe Pose Landmarker lite**（本工程） | ~5.7 MB | 33 个关键点，CPU 上单帧 15–30ms，完全离线，不依赖 Google Play 服务 |
| ML Kit Pose Detection（bundled） | ~10 MB+ | 更省事，但 API 更粗，且部分能力依赖 Play 服务 |
| YOLO-pose nano | ~12 MB | 精度好，但需要自己转 TFLite + NMS，工程量翻倍 |

lite 版本对「站着不动 / 慢慢挪」这种场景足够，且手机背面不烫。

模型由 Gradle 任务 `fetchPoseModel` 在首次构建时下载；离线环境可手动放到
`app/src/main/assets/pose_landmarker_lite.task`（下载地址见 `app/build.gradle.kts`）。

## 3. 构建

### 方式 A：Android Studio（推荐）

1. 用 Android Studio 打开本目录。
2. 首次 Sync 时如果没有 Gradle wrapper，IDE 会提示使用自带 Gradle；或本机装了 Gradle 后执行 `gradle wrapper --gradle-version 8.9` 生成 wrapper。
3. 直接 `Run` 到手机（Android 8.0 / API 26 以上，arm64）。

### 方式 B：命令行

```bash
gradle assembleDebug        # 产物在 app/build/outputs/apk/debug/
```

### 方式 C：没有本地环境，用 GitHub Actions

推到 GitHub 后 `.github/workflows/build.yml` 会自动构建，在 Actions 页面下载
`coyote-cage-debug-apk` 产物即可。

## 4. 使用

1. 手机架好（建议横放或三脚架，能拍到全身），打开 App，允许相机权限。
2. 点右上角 **连接**，等状态变成「等待 DG-LAB APP 扫码」。
3. 另一台手机（跑外设 App 的那台）打开对应 App → 设置 → **Socket 控制** → 扫本机弹出的二维码。
   连接成功后状态变成「已连接 · 设备…」并显示电量。
4. 点 **框选**，在画面里拖出一个矩形（脚下那块地板），再点 **完成**。
   也可以在设置里切到 **随机框**，每 N 秒自动换一个位置。
5. 先去 **设置** 里把「基础强度」调到 3–5，点「用当前基础强度试一次」确认体感。
6. 点 **开始**。人走出框 → 越界计时 → 超过容忍时间开始输出；回到框内立刻停并归零。
7. 任何时候点红色 **急停**：清空波形队列 + 两个通道强度归零。

> **一台手机也能试**：点「复制二维码内容」，把那条 `https://dungeon-lab.cn/s/...` 链接丢进浏览器打开，
> 会直接拉起外设 App。但本 App 在前台占着相机，外设 App 退到后台后蓝牙/WebSocket 可能被系统掐掉，
> 稳一点还是用两台手机：一台当摄像头 + 控制端，一台只跑外设 App。

## 5. 参数

| 参数 | 说明 |
|---|---|
| 基础强度 | 刚开始越界时的强度（1–40） |
| 强度上限 | 递增的封顶值，也受 APP 内「被控设置」上限约束（1–100） |
| 每级递增 | 每次涨多少 |
| 随越界时间递增 | 开关；打开后每 N 秒涨一级，回到框内立即重置 |
| 每 N 秒涨一级 | 递增节奏（1–30 秒） |
| 单次输出时长 | 一次输出多长（100–3000ms） |
| 两次间隔 | 两次输出之间的静默时间（200–10000ms） |
| 越界容忍 | 出框多久才算越界，防抖用（0–1500ms）；随机框模式固定为 0 |
| 检测部位 | 躯干（双肩+双髋）/ 全身 / 双脚 |
| 输出通道 | A 或 B |
| 框模式 | 固定框（手画）/ 随机框（每 N 秒跳一次，上半屏约 10%）/ 姿势锁定（倒计时结束锁住当前姿势，偏差超容差即罚） |
| 准备倒计时 | 按「开始」后的准备时间，0 = 关闭（0–10 秒） |
| 姿势容差 | 姿势锁定模式允许的抖动幅度，按画面高度百分比算（1–12%） |
| 中继地址 | 默认官方 `wss://trex.dungeon-lab.cn/v4`，可换成自建服务端 |

## 6. 波形格式

一行 = 一帧 = 8 字节 = 16 个十六进制字符 = 100ms，其中 4 组 25ms：

```
字节 1-4：脉冲频率（10–240，对应实际 10–1000Hz 的压缩区间）
字节 5-8：脉冲强度（0–100），0 表示这 25ms 不输出
```

例：`0A0A0A0A64646464` = 四段 10Hz / 满强度；`0A0A0A0A32323232` = 四段 10Hz / 50% 强度。
输出时长会自动把模板循环铺满（例如 600ms = 6 帧）。

任何一帧里出现越界数值（如强度 >100），该通道这一帧的四组数据会被整帧丢弃——
这也是「只让一个通道输出」的技巧。

## 7. 安全

本项目只负责「判定 + 下发指令」，实际输出由所连接的外设决定，
**具体禁忌部位、禁忌人群与使用时长限制，请严格以设备自身的说明书和安全须知为准**。

几条通用原则：

- 第一次使用从最小强度开始，确认体感后再调高。
- 身体不适、饮酒后、独自无人照看时不要使用。
- 始终保留物理急停手段：App 内的急停按钮 + 设备本身的开关。
- 不要在驾驶、操作机械等需要保持注意力的场景使用。
- 切换电极/贴片位置前，先停止输出。

## 8. 代码结构

```
app/src/main/java/dev/coyotecage/
├── MainActivity.kt          入口，常亮屏
├── Settings.kt              CageSettings / 波形解析 / SharedPreferences
├── CageEngine.kt            判定循环 + 输出节奏 + 递增
├── camera/CameraAnalyzer.kt CameraX ImageProxy → Bitmap → 推理
├── pose/PoseEngine.kt       MediaPipe Pose Landmarker 封装（GPU→CPU 回落）
├── pose/PoseSkeleton.kt     33 点骨架连线表（连线可视化用）
├── dglab/DglabV4Client.kt   V4 Socket 控制端（JSON-RPC / device.op）
├── util/Qr.kt               二维码生成
└── ui/
    ├── CageScreen.kt        主界面、相机层、ROI 覆盖层、骨架绘制、坐标映射
    ├── SettingsSheet.kt     参数面板 + 扫码弹窗
    └── Colors.kt

web/index.html               网页版（单文件，MediaPipe Tasks JS）
```

## 9. 已知限制

- 单目标（`setNumPoses(1)`），画面里多人时只跟最靠前/最大的那个。
- 判定点是 2D 归一化坐标，不做深度与遮挡推理；人背对镜头时关键点可能抖。
- 相机画面与覆盖层用 FIT_CENTER 对齐，预览与分析都锁 4:3；如果某机型预览裁切方式不同，框选位置可能有偏移。
- 没有做 `targetSdk 35` 的后台前台服务，App 切到后台相机就停，判定也停（这是有意的安全设计）。

## 10. 网页版（免安装）

`web/index.html` 是同一套逻辑的浏览器实现：MediaPipe Tasks JS + getUserMedia + 同一个 V4 Socket 客户端。
骨架连线、三种判定模式（固定框 / 随机框 / 姿势锁定）、准备倒计时、输出节奏、波形配置都和安卓版一致，
参数存 localStorage。

必须通过 `http://localhost` 或 `https://` 打开，否则浏览器禁用摄像头：

```bash
python -m http.server 8765 --bind 127.0.0.1 --directory coyote-cage/web
# 浏览器打开 http://127.0.0.1:8765
```

- 电脑上直接就能试；手机浏览器要 https，或者用 `chrome://flags` 里的
  "Insecure origins treated as secure" 把地址加进白名单。
- 首次打开要从 jsdelivr 拉 wasm（约 9MB）、从 googleapis 拉模型（5.7MB），之后走缓存。
- CDN 不通时，把模型下到 `web/models/pose_landmarker_lite.task`，页面会自动回退到本地这份。
- 二维码库加载失败时会退化成「复制配对链接」，在跑外设 App 的手机上打开那条链接也能直接拉起 APP。
- iOS Safari 的 wasm/WebGL 支持一般，建议 Chrome / Edge。

### 部署到 GitHub Pages（手机上用 https 访问）

仓库里已经带好 `.github/workflows/pages.yml`，不需要改任何代码：

1. 把 `coyote-cage/` 整个目录作为仓库根推到 GitHub（`.github/`、`web/`、`app/` 同级）。
2. 仓库 **Settings → Pages → Build and deployment → Source** 选 **GitHub Actions**（不是 Deploy from a branch）。
3. 推到 `main` 就会自动跑 `deploy-web`，完成后地址是 `https://<用户名>.github.io/<仓库名>/`。
4. 手机上打开这个 https 地址即可，摄像头权限能正常给。

免费账号的 Pages 要求仓库是 **public**（private 需要 Pro）。页面本身是纯静态，
`deploy-web` 只上传 `web/` 目录，和安卓的 `build-apk` 互不影响。

## 11. 构建踩坑记录

这份代码已经用 **Gradle 8.9 + AGP 8.7.3 + JDK 17 + compileSdk 35** 实际编译通过，
产物 `app-debug.apk` 约 31.6 MB（arm64-v8a）。实际遇到的两个坑：

1. **Windows 路径不能有中文**。AGP 会直接抛
   `Your project path contains non-ASCII characters`，把工程放到 `C:\dev\...` 这种纯英文路径下再构建。
   加 `android.overridePathCheck=true` 能绕过检查，但后面的 AAPT2 不保证正常，不推荐。
2. **`build.gradle.kts` 里不能写 `java.net.URI(...)`**。Kotlin DSL 里 `java` 是 Gradle 的
   `JavaPluginExtension`，会被解析成 `java.net` 属性。要 `import java.net.URI` 后用 `URI.create(...)`。

国内网络下 `repo1.maven.org` 只有 ~100KB/s，`settings.gradle.kts` 里已经把阿里云镜像放在前面；
如果海外使用可以删掉那三行 `maven("https://maven.aliyun.com/...")`。

模型下载失败的话，手动下载 `pose_landmarker_lite.task` 放进 `app/src/main/assets/` 即可。

协议部分依据：<https://github.com/DG-LAB-OPENSOURCE/DG-LAB-OPENSOURCE>（蓝牙协议）
与官方 `dglab-kit` 的 V4 Socket 文档。

## 12. 关于

本项目是 **DSH（DeepSeek Harness）与 Codex 的 vibe coding 协作产物**：
需求拆解、代码评审和排错都在对话里完成，代码由 AI 生成，人只负责提需求、验收和拍板。
真机验证、协议取舍和最终判断由人完成。

### 版本历史

| 版本 | 说明 |
|---|---|
| **v2.0.0** | 新增「姿势锁定」模式与全局准备倒计时（可关）；每个模式按「开始」都会先走倒计时 |
| v1.1.0 | 安全修复：零容忍下网页版误触发、急停归零延迟、停止后残留输出；补 8 项网页回归测试 + 7 项 Android 单元测试 |
| v1.0.0 | 首个公开版本：固定框 / 随机框、横屏布局、调试模式、网页版 |

两个版本的 APK 都放在对应的 Release 里。**旧版本一直保留**：
[v1.0.0 Release](https://github.com/zhaosong466-cmyk/coyote-cage/releases/tag/v1.0.0)。
仓库本身只放源码，APK 不进 git 历史（31 MB 的二进制会把仓库撑爆）。
