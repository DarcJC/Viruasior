# AHB、KWin 与运行时分辨率验证

2026-10-18，M367FC / Android 17，Manjaro ARM / Plasma 5.27.10，私有 Mesa 25.0.7。

## 修复与证据边界

可见帧现在通过独立 SurfaceControl 层提交原始 AHardwareBuffer。正常路径不做 CPU lock、像素扫描、CPU copy 或额外的应用呈现 blit；`FRAME_POSTED path=root-surface-control cpuCopies=0` 区分该路径与保留的诊断回退。描述符必须包含 composer 和 sampled usage。release Binder 放在变长 AHB parcel 之前，两端顺序一致。

KWin 黑屏的实际原因是：禁用 modifier 扩展时，Zink 接受 INVALID，却提前拒绝了 Xorg 的显式 LINEAR pixmap。Kopper 随后创建空纹理，窗口边框能显示，窗口内容和壁纸却黑屏。修正仅允许指定 Venus/Mali 上的 LINEAR 和 INVALID；其他 modifier 和驱动仍走原能力检查。修正前截图仅 18/400 个采样非黑；修正后为 367/400，RGB 色块顺序正确，同时 KWin 报告 active / OpenGL / Zink / Venus / Mali。

另有一个截图无法检测的问题：实体屏幕整块 Surface 发绿，截图却正确。SurfaceFlinger dump 显示该层走 DEVICE；只让该层走 RenderEngine CLIENT 后，用户确认实体屏幕绿色消失、颜色正常。实现用 0.01 像素的子像素圆角让这一层保留在 GPU 合成，并在合成时交换 BGRA/RGBA 的 R/B 通道。仍采样同一 AHB，没有应用额外的 blit 缓冲。**AHB 直接提交不等于面板硬件 overlay**；该设备的 HWC 路径未通过验收，截图不能替代实体屏幕检查。

生产队列的 Vulkan fence 超时不再计为成功，也不再销毁未完成的 fence。每个队列保留一个待完成 fence；之后先退休旧 fence，再为当前工作创建完成点。Android release fence 返回普通 VM 进程，作为 DMA-BUF reader 依赖导入，并在 fence 完成后释放引用；最多保留八个在途 release 引用。后台队列为空时不轮询。上下文同步范围仍保守地覆盖活动 Venus context；未完成的 flush 保留上一帧，等待后续 flush。

显示导入缓存现在绑定 surface generation 和 scanout 元数据。资源释放会关闭显示导入，修正了 Android backend 的 import/surface 参数顺序，销毁旧 surface 后也不会为迟到释放重建空导入表。native surface modeset 保留 UI 接入。裁剪使用 AHB source 像素坐标，修正大分辨率时右侧和底部黑块的问题。

## 动态分辨率与输入

vsock 增加有界的 `DISPLAY_MODES` 和 `RESIZE width height`。固定桌面用户执行固定路径 XRandR 参数数组；只接受实际列出的 mode，每边最多 4096，总像素最多 4,194,304。失败尝试回滚；快速请求合并为最新目标，旧 generation 的回复和帧回调不更新新会话。

同一 VM PID 下完成 `1280×720 → 1280×800 → 1920×1200 → 800×500 → 1280×720`，每一步均通过实际 Surface 像素检查。另在 1280×720、1920×1200、800×500 下，各验证四个角落附近的触摸点：客体 X11 根窗口坐标与目标相差不超过 5 像素。1024×768 不在此客体 EDID 的 mode 列表中，拒绝该请求，不添加任意 modeline。

Android holder / virtio ABS 范围固定为启动尺寸；视口宽高比和相对鼠标比例跟随已提交的新尺寸。切换时释放输入并暂停发送，等新尺寸回调后恢复。

## 短时性能对比

同一客体、1280×720、KWin OpenGL 和六色 X11 窗口持续重绘/移动。CPU 基线为改动前 APK，新路径为直接 AHB / CLIENT 合成候选。读取对应 SurfaceFlinger 层的实际呈现时间；两者均取运动阶段最近约 128 帧历史。该测试不是长期帧率保证，也不代表所有 GPU 负载。

| 指标 | CPU copy 基线 | AHB 直接提交 |
| --- | ---: | ---: |
| 有效呈现帧数 | 125 | 127 |
| 样本时长 | 4.32 s | 2.62 s |
| 平均呈现频率 | 28.7 fps | 48.0 fps |
| 帧间隔 p50 | 33.33 ms | 24.98 ms |
| 帧间隔 p95 | 50.03 ms | 25.02 ms |
| 帧间隔 p99 | 66.66 ms | 25.03 ms |
| 超过 33.4 ms 的间隔 | 50 / 124 | 0 / 126 |

移除每次绘制、cache 操作和 ring submit 的默认追踪输出；`VIRUAISOR_GRAPHICS_TRACE=1` 可显式恢复。同步成功日志按 context 限为每秒一次，错误保留。设备验收从启动前持续记录日志，避免环形 logcat 缓冲淘汰租约/renderer 证据。

## 本地与设备验收入口

- `node --test scripts/*.test.cjs`：101 项通过。
- `bash scripts/test-venus-runtime.sh`：24 个 native 测试程序（ASan/UBSan）及 3 项 Rust 测试通过。
- `make -C guest/avf-agent test`、`python3 guest/plasma/display_control_test.py`：协议检查与 5 项 XRandR 行为测试通过。
- Gradle `:app:testDebugUnitTest :app:assembleDebug` 通过；`scripts/verify-native-packaging.ps1` 验证五个 arm64 ELF 及桌面运行时摘要与源码 pin 一致。
- `bash scripts/test-root-direct-surface.sh 180`：本会话租约、普通 UID/caps=0 执行、KWin OpenGL、GPU readback、同步、零 CPU copy、同会话 PNG 像素和该层 CLIENT 合成共同验收。
- `scripts/test-display-resize.ps1`：运行中切换并核对首帧尺寸、视口像素和 VM PID。需先打开已启动的 Viruaisor。
- `bash scripts/test-root-display-cleanup.sh <capture>`：停止 VM，验证 `ROLLBACK=PASS`、VM/helper/显示层退出和策略快照一致。

调试截图、时序、输入 JSON 和设备文件备份位于本地 `.pi/device-debug/direct-stage/`，不提交。客体测试窗口和启动钩子在最终验收前移除。保留设备原有数据，离线更新前停止 VM，并备份被替换文件。

本轮重建的准备包位于 `.pi/device-debug/manjaro-plasma/manjaro-plasma-venus.zip`，SHA-256 为 `bd06c85e4eb9871ef09a3028d9353d5996374f11c1f5b87fa8f7cae08782fb7c`；其中根盘为 `b442bd1b9ec463383f7ed4415e9023bc20cc79cf91c3d04440f77bd1f66d66da`。同一准备包已放入设备应用的外部文件目录并核对摘要，供后续导入。设备现有根盘采用保留数据的离线文件更新，没有重导入该新包；运行中根盘摘要会正常改变。

权限分别验证：root helper 为 UID 0；既有 AVF 授权保留；AVF 可用；VM 在普通应用 UID 下运行；Surface 接入；真实客体像素显示。SELinux 保持 Enforcing，全局 hidden API、APEX 字节、锁屏和电源设置不改动。普通非 root Surface 接入仍受该设备 SELinux 限制；本次资格限于已获授权的窄 root display bridge。
