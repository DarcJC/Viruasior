# 高刷新率、触摸板与闪黑验证

2026-10-18，M367FC / Android 17 API 37，KWin 5.27.10 / Mesa 25.0.7 / Venus / Mali。

## 已实现与证据

- EDID 提供原生 3408×2272 @ 60 Hz 和 1920×1200 @ 120 Hz。显示预算为每边 4096、总计 8,388,608 像素；超过 4,194,304 像素只接受不高于约 60 Hz 的时序。全屏只修改本窗口的刷新率偏好，退出或暂停恢复原属性。
- 控制协议以整数 mHz 表达完整 mode，验证客体实际公布的 mode/rate，失败恢复旧尺寸和刷新率。只切刷新率无需等待新的尺寸回调；改变尺寸则等待实际画面到达。启动用原生尺寸生成 EDID 和固定 ABS 范围，不改写导入的 vm.json。
- 最终候选在同一 VM PID 22853 内通过 `1920×1200@120 → 3408×2272@60 → 1920×1200@59.88 → 1920×1200@120 → 800×500 → 1920×1200@120`。各步实际 Surface 像素通过。
- 截图中的黑块可复现；运动时客体合成前缓冲的对应像素也为黑色。改变 Android 圆角没有解决。KWin 的 buffer-age/局部重绘路径不能保留这条共享 scanout 所需的完整背景。仅配置 `GLPreferBufferSwap=p` 在当前版本不足以强制整个实际绘制区域。
- 随客体构建的 `viruaisor_full_repaint` KWin 效果在已有 paint pass 中扩展重绘区域，不通过定时器产生空闲重绘。仍运行 KWin OpenGL，Android 仍直接提交原 AHB，`cpuCopies=0`。原生和 1920×1200 各 10 张连续运动截图均通过角落、RGB 探针运动和自有 CLIENT 层检查。验收会拒绝静止图片、死亡 VM 的应用界面和其他应用层。
- GPU 同步改为先发出所有活动 Venus context 的完成请求，再收取全部回复。保留全部 ring/GPU 完成等待、消费者 release fence 和八个引用的上限。失败也必须排空已成功发出的请求，避免下次 socket 回复错位。实测平均同步等待约从 2 ms 降到 0.7–1.3 ms。资源销毁同时补齐仍存活的 blob 映射清理。
- 临时 Surface 脱离时保留原 AHB 等待重新接入，不因普通全屏切换计入 CPU copy 回退。

## 原生触摸板

本机默认无参数 `requestPointerCapture()` 实际请求 **RELATIVE**，实体键盘触摸板事件为 `SOURCE_MOUSE_RELATIVE`，即使启用 AVF trackpad 也不能得到多指。改为 API 37 的 `POINTER_CAPTURE_MODE_ABSOLUTE` 后，InputReader 和 InputDispatcher 均确认 ABSOLUTE，捕获窗口为本应用。

POSITION 类事件送入 AVF `sendTrackpadEvent`；鼠标保持独立相对鼠标通道。按本机 AVF 固定的 3000×3000 范围转换硬件触摸板坐标，并稳定映射十个接触 slot；历史样本、抬指、失焦、设备移除与切换模式的释放均保留。已检查安装的 AVF：使用 getX/getY，slot 编号内部加一，压力固定为 50；应用无法通过该 API 保留真实硬件压力。

Linux udev 报告 `ID_INPUT_TOUCHPAD=1`；libinput 列出 `Crosvm Virtio Multi-touch Trackpad 0`，能力为 pointer/gesture，支持双指滚动。注入的 Android TOUCHPAD 滑动经过应用进入该设备并产生 libinput POINTER_MOTION。实体双指手感、点击和手势仍需要用户后续实际使用验证；没有以单指注入代替这项结论。轻触点击沿用客体 libinput 设置，默认未开启。

## 性能边界

原先 48 fps 来自约 2.6 秒的 SurfaceFlinger 历史。新测量用截止时间驱动的六色 X11 窗口持续重绘/移动，轮询自有层实际呈现时间，去重并排除开始前的历史帧；只统计实际运动且同一 VM 存活的样本。

| 场景 | 测量窗口 | 实际呈现 fps | 帧间隔 p50 / p95 / p99 |
| --- | --- | --- | --- |
| 1920×1200 @ 120 Hz，最终候选 | 59.32 s / 2351 帧 | 39.61 | 25.00 / 33.35 / 41.67 ms |
| 3408×2272 @ 60 Hz，完整重绘 | 49.33 s | 15.24 | 66.67 / 100.00 / 116.67 ms |

**稳定 60 fps、1920×1200 的 120 fps 和原生 60 fps 均未通过。** 公布 120 Hz mode 和面板运行在 120 Hz 不代表客体达到 120 fps。已测出的开销包括 guest/renderer 往返与 DMA-BUF 缓存同步；完整重绘解决正确性问题，但高分辨率合成仍明显受限。没有删除同步或关闭 KWin 来换取帧率。取消 VSync、关闭严格纹理绑定没有收益；EGL 试验无法初始化，最终保持 GLX。

## 构建、复现与限制

KWin 效果 ABI 不稳定。`scripts/build-kwin-full-repaint.sh` 明确要求配套客体 5.27.10 的头文件和库，使用其 moc 生成元数据、交叉编译 arm64；`scripts/build-manjaro-bundle.sh` 安装效果并启用会话校验。单独安装 APK 不会更新旧根盘中的 agent、helper 和 KWin 效果；本机已有根盘和准备导入的 ZIP 都需成套更新。

- `scripts/test-display-resize.ps1` 接受 `宽x高@mHz`，同时验证客体确认、真实像素和 VM PID。
- `scripts/assert-display-motion.cjs <SurfaceFlinger dump> <至少3张探针截图>` 检查角落闪黑与真实运动；探针须覆盖明亮壁纸，不能对任意黑色桌面使用该门限。
- `scripts/assert-trackpad-capture.cjs <dumpsys input>` 验证实际绝对捕获及窗口归属。
- Android 单元测试、APK 构建及五个 arm64 ELF/source pin 校验通过；guest Python 9 项和 C 协议测试通过；native ASan/UBSan、原有 Rust 与新增 5 项批量同步测试通过；Node 验收测试通过。

已更新设备上的配套 APK、现有根盘中的工具与 KWin 效果；重新生成的 1,691,316,836 字节准备导入 ZIP 已通过传输后 SHA-256 校验并原子替换，摘要为 `8a8e03f8ec3ea55bc28634a999465ae60353098674d88ca71e58f6954685c2ce`，没有重新导入或覆盖现有 VM 数据。

原始记录位于忽略的 `.pi/device-debug/perf-60-20261018/`。失败/无运动/错误界面的试验没有计入通过结果。旧候选有一次 pKVM stage-2 EINVAL 退出；最终六次 modeset 未复现，不据此声称修复了宿主内核问题。SELinux 保持 Enforcing，AVF 授权与全局 hidden API 策略不变，未替换系统 APEX。
