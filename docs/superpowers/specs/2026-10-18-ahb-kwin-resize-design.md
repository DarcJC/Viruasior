# AHB 直接提交、KWin OpenGL 与运行时分辨率切换

日期：2026-10-18。状态：用户已批准，实施中。

## 目标

1. Venus/Zink 写入的可见 scanout 直接以同一块 AHardwareBuffer 提交给 Android 合成器；正常呈现不映射像素到 CPU、不做 CPU 像素复制，也不额外做 GPU blit。
2. Plasma 窗口合成使用 KWin OpenGL，实际 renderer 必须为 Zink/Venus/Mali，并且能持续呈现正确桌面与窗口动画。
3. VM 运行期间可以切换客体分辨率，无需重启；画面、Surface 几何和输入坐标采用同一已确认尺寸。
4. 保留现有 CPU 呈现路径作为可明确识别的诊断回退；回退不能计为上述目标通过。

## 已确认的现状与证据边界

- `native/venus-display/android_display.cpp` 的 `rgba_image && root_producer` 分支先执行 `AHardwareBuffer_lock`，再经 `presentSourceLocked`、`venus_copy_bgra`、`ScanoutBuffer.copyRgba`、`ANativeWindow_unlockAndPost` 呈现。root 路径主动跳过已有的直接 AHB 提交。
- `guest/plasma/venus.env` 设置 `KWIN_COMPOSE=N`；`plasma-session` 写入 `Enabled=false`，并且只在确认合成关闭后发送 ready。相关单元测试和设备验收也把关闭合成当作成功条件。
- 历史 `live-kwin-probe/logcat.txt` 在 04:58:20 记录 KWin OpenGL/Zink/Venus/Mali，04:58:21 的直接提交样本仍为 `colored=0`。这证明 renderer 信息与提交成功日志不足以证明实际可见内容正确；尚不能据此认定黑屏的唯一根因。
- `venus_gralloc_allocate_rgba_image` 未申请 `COMPOSER_OVERLAY`；两端校验只要求三个显示 usage 中任意一位。Java SurfaceControl 文档要求 composer 和 sampled usage。该契约缺口需要修复和实机验证。
- `RootOwnedSurface` 的 BLAST producer 与直接提交层分离，但初始显示、层级和可信覆盖设置主要面向 producer；直接提交需要完整、独立的显示配置。
- 直接 AHB 路径只调用 `setBuffer`；未回传 acquire/release fence，Android `flip_to` 始终返回已完成的 `Waitable`。保留 AHB 引用只能保护分配寿命，不能防止生产者过早改写像素。
- 每次 scanout flush 经 rutabaga 遍历所有活动 Venus context 并等待各自的 GPU fence。超时会返回成功语义，且 GPU fence 仍可能待执行。这需要区分“已完成”“稍后重试”“失败”，并检查 fence 的销毁时机。
- `RESIZE` 当前返回 `ERROR display-unavailable`。Android 视图和输入默认固定 1280×720。crosvm 本身已能在 guest modeset 后按新尺寸重建 display surface；native 的 `destroy_android_surface` 却同时断开 UI window/sink，尺寸切换会丢失接入。
- 本轮设备通过 ADB 可达，但检查时锁屏、熄屏，未启动 VM 或改变屏幕状态。黑屏根因与性能改善仍需解锁后的复现确认。
- 已运行全部 95 项 Node 测试、20 个 native 测试程序（ASan/UBSan）和 3 项 Rust 测试，全部通过；Gradle `:app:testDebugUnitTest :app:assembleDebug` 也成功（任务使用现有缓存）。初次 Gradle 启动缺少 JAVA_HOME，使用 Android Studio 自带 JBR 后通过，未修改系统环境。现有显示/KWin 测试保护的是 workaround，不能证明新目标完成。

## 方案比较

| 方案 | 优点 | 代价与适用范围 |
| --- | --- | --- |
| 同一 scanout AHB 直接提交，修复互操作和同步（推荐） | 满足真正零像素复制，避免 CPU 带宽和转换开销 | 必须贯通 guest/host 图像身份、布局、格式及 acquire/release 所有权 |
| GPU blit 到独立 Android 呈现池 | 易于隔离 guest 与 Android 的缓冲生命周期 | 仍有 GPU 像素复制，不满足本次零拷贝验收；仅作为定位对照 |
| 保留 CPU 映射复制，局部优化 | 保留已有可见画面基线 | 不能完成前两个目标，仅用于回归对照与明确报告的失败回退 |

## 实施设计

### 1. 先建立能捕获黑屏的验证闭环

分开验证 AHB 提交与 KWin，避免同时切换两项而无法定位。固定同一场景、分辨率和测试时长，依次记录：现有 CPU 基线、关闭 KWin 时的直接 AHB、开启 KWin 时的直接 AHB。

采用确定性的多色测试图和窗口移动/重绘场景，核对 guest 渲染图像、host 导出 FD 对应的 AHB、实际屏幕区域。关联 session、scanout/resource、DMA-BUF identity、图像和尺寸，判断黑帧首次出现在哪个边界。像素读回仅在显式诊断模式执行，正常呈现不做全帧扫描。

待验证假设按优先级为：导出的 AHB 与 KWin 写入图像/布局不一致；完成与复用同步缺失；Surface 层被遮挡或透明度处理错误。证据支持哪一项，修复哪一项；不把单纯打开开关作为修复。

### 2. AHB 与 Surface 呈现

- 为可显示图像申请并严格校验 `GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY`，同时保留渲染所需 usage；严格验证 format、layers、尺寸、offset 和资源身份。
- guest 导出、跨 context 导入和 host Vulkan image 必须引用相同像素存储，使用兼容的 format、tiling、flags、layout 与外部所有权转换。不得用伪造 stride/modifier 或无依据的格式替换掩盖不兼容。
- 通过现有同 UID frame sink 传递 AHB 与限定的帧元数据、acquire fence；root helper 只操作自身拥有的子层。直接层具有明确的可见性、z-order、裁剪、XRGB/ARGB 处理和触摸可信覆盖属性。
- 原生非 root 提交采用可返回 buffer release 的接口；root 路径采用带 release callback 的 `SurfaceControl.Transaction.setBuffer`。回调和 fence 关联 session/generation/frame，旧会话回调不得更新新会话。
- 将 Android consumer release 传播回实际 buffer 复用方；仅在 release fence 完成后允许再次写入。检查并贯通 crosvm/DRM/Xorg 的 page-flip/同步语义，不能只把现有 `Waitable` 改为异步对象而仍被上层忽略。
- 呈现线程不在持有 Binder/session/renderer 全局锁时等待 GPU 或 release。使用有界 in-flight 状态，尺寸切换、detach、停止和超时均能回收引用、FD、回调与等待对象。
- GPU 工作未完成时保持上一张合格画面并延后当前提交；不能返回“同步完成”。尽量将同步缩至生产当前 scanout 的 context/queue；如果跨 context 依赖尚未证明，先保留正确的同步范围，再据测量收窄。
- 正常路径记录直接提交帧数、CPU copy 次数、同步/提交耗时和丢弃/延后次数，不逐帧输出大量日志。诊断回退必须在状态与验收中明确标记。

### 3. KWin OpenGL

修复已复现的图像共享或同步问题后，移除强制关闭合成的环境和配置。启动条件改为 KWin compositing active、类型 OpenGL、renderer Zink/Venus/Mali，加上真实 GPU 绘制探针。

最终 ready/验收还必须包含本会话的合成后可见帧证据和持续更新。KWin 合成关闭、软件 renderer、只有 Vulkan compute 成功或只有 Surface attach 均拒绝作为成功。Qt Quick 的软件设置独立于 KWin 合成，本次先不扩展为全面更换 Qt 渲染后端。

### 4. 动态分辨率

- 普通 app 增加运行时分辨率选择，列出 guest XRandR 实际支持且在预算内的 mode。默认保持 bundle 初始分辨率；切换由用户操作触发。支持从 guest 显示设置发起的 mode 变化。不默认把每次窗口缩放都变为 guest modeset。
- 通过现有 vsock agent 查询 mode 并执行严格解析的 `RESIZE width height`；只接受正整数、范围与像素预算内且已列出的 mode。使用固定程序和参数数组，不接受 shell 文本。XRandR 操作在固定桌面用户/X 会话权限下执行，root 不承担 UI 或输入逻辑。
- 建议 mode 查询回复为有界单行：`OK MODES currentWidth currentHeight width1 height1 ...`；`RESIZE` 在 XRandR 确认实际尺寸后返回 `OK SIZE width height`。未支持的 mode、桌面未就绪和超时返回明确错误。现有 START/STOP/READY 协议保持兼容。
- guest modeset 后，crosvm 按实际新 scanout 尺寸重建 native 资源；资源销毁与 Android UI 接入寿命分离，保持当前有效 window/sink。新尺寸首帧附带 session/generation 信息传到 app。
- helper 的 source bounds 采用实际 AHB 尺寸，destination 始终落在当前 SurfaceView parent 的坐标范围内；避免旧 crop 截断新帧或重复缩放。必要的 queue update 只更新自有 BLAST queue。
- app 在确认新尺寸/首帧后统一更新显示比例、holder 尺寸和触摸/绝对指针映射。切换期间释放活动触点和按键；输入暂停到新几何生效，避免旧尺寸坐标送入新桌面。
- 快速连续请求只保留最新目标；失败保持或恢复已确认 mode，并报告实际尺寸。detach/recreate 使用新 generation，拒绝旧请求、旧帧和迟到的回调。
- 沿用现有上限：每边不超过 4096、总像素不超过 4,194,304；新旧分辨率合计 in-flight 分配也受有界预算控制。实际可选 mode 以 guest 查询结果为准，不承诺任意 modeline。

## 回归与实机验收

先增加能失败的行为测试，再修改对应实现。重点覆盖 usage/geometry 校验、ring/GPU deferred 状态、fence 释放和复用、buffer 身份、mode 协议、切换失败、旧 generation、输入坐标和 VM restart。现有以源码正则断言 workaround 的测试需要改为新契约，但不以字符串测试替代同步行为或屏幕验收。

运行 native runtime 测试、相关 Node/guest 测试及 Gradle `:app:testDebugUnitTest :app:assembleDebug`；重新生成 native display、renderer/crosvm 和 guest agent/桌面产物，核对 APK 内打包版本。已有 VM 数据保留，离线更新镜像前先停止 VM，并保留可恢复备份。

解锁后的同设备验收要求：

1. AHB 直接提交持续显示测试图和 Plasma，正常呈现 CPU lock/copy 计数为零；单次诊断读回不计为正常路径能力。
2. KWin OpenGL/Zink/Venus/Mali 持续运行，移动、缩放、遮挡、重绘窗口后画面更新正确；采样实际屏幕，不能只看提交日志。
3. 在至少三个实际支持的 mode 间往返切换，包含宽高比变化，画面无持续黑屏/截断，点击四角与拖动位置正确；失败请求不改变已确认状态。
4. 反复切换、Surface detach/reattach、前后台切换和 VM start/stop 后无 FD/AHB/回调泄漏与旧会话帧。
5. 与 CPU 基线比较相同场景的帧间隔、p50/p95/p99、长帧数、同步等待和 CPU 用量，报告实际数值，不预先承诺帧率。

设备锁屏时继续构建和本地回归，但不将“未实机验收”报告为完成。不唤醒/解锁设备，不修改 SELinux、全局 hidden API、系统镜像或已有权限策略。

## 主要接口依据

- [Android SurfaceControl.Transaction.setBuffer](https://developer.android.com/reference/android/view/SurfaceControl.Transaction)：composer/sampled usage、acquire fence、release callback 和安全复用。
- [Android NDK Surface transaction](https://developer.android.com/ndk/reference/group/native-activity#asurfacetransaction_setbufferwithrelease)：API 36 起的 release callback 与 acquire FD 所有权。
- [VK_ANDROID_external_memory_android_hardware_buffer](https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html)：AHB 图像导入/导出及布局约束。
- 本地 `docs/root-owned-surface-research.md`：parent 坐标空间、BLAST resize 和子层生命周期。
