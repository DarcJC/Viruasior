# Manjaro Plasma / Venus 准备与实机验收

2026-10-18，目标设备 M367FC（Android 17 / API 37）。硬件加速是验收条件。

## 当前状态

AHB 直接提交、KWin OpenGL 和动态分辨率已经完成实机联调。正常路径 `cpuCopies=0`；实体屏幕绿色由该层的 HWC 输出引起，限定为 SurfaceFlinger CLIENT 合成后，用户已确认恢复正常。四种客体支持的分辨率往返切换与三个分辨率的角落输入已通过，短时动画约由 28.7 fps 提升至 48.0 fps。具体证据、权限边界和限制见 [本轮验收记录](ahb-kwin-resize-validation.md)。新镜像需要最新 agent、XRandR、私有 Zink LINEAR pixmap 修正和桌面会话；重建入口仍在下文。

## 2026-10-17 历史记录

- 最终 Debug APK 已通过普通 ADB 更新安装，原有 AVF 两项授权保留。
- 第五版最终候选 Manjaro 镜像已在 Debug 应用的普通 UID 10278 下完成 ZIP 与四个输入文件校验，并以同一文件系统内原子改名替换：当前 `files/vms/default/vm.json` 为 `osName=manjaro` / `gpuProfile=venus`，设备内根盘摘要 `d3f83489af045f0455be9f7e2d748bfad3e4a9952a4058c10f2bf131e6c95f6c` 与候选产物一致。上一版保存在 `default-before-mutable-import-20261017`，待最终硬件验收通过后连同更早诊断镜像一起删除。
- 当前设备处于 Asleep / 锁屏状态；VM、root helper 和私有绑定均已退出，应用仅为空闲状态。没有绕过锁屏或修改锁屏、电源设置。
- **实机已经通过 Manjaro 启动、Venus Vulkan 三点像素读回和 Direct Surface 接入。第一轮确认 Xorg 的 `SHARED|SCANOUT` 图像必须使用普通 linear 图像，并只在内存分配阶段导出 DMA-BUF；该修正已越过原来的首次 `vkCreateImage` 失败并启动 Xorg/Plasma 会话。第二轮确认后续 DRI3 无 modifier 导入会被上游能力检查提前拒绝。第三轮绕过该限定检查后命中 `VIRUAISOR_ZINK_LINEAR_DMABUF_IMPORT`，进一步证明 Mali 同样拒绝导入图像上的 `VkExternalMemoryImageCreateInfo`。第四版把 initial scanout 和 subsequent import 都限定为“普通 linear 图像 + DMA-BUF 内存导出/导入”，并修复宿主 blob 导出与客体释放之间的 FD 生命周期竞争；随后跨上下文导入仍因源图像 `flags=8`、目标图像 `flags=0` 返回 `VK_ERROR_INVALID_EXTERNAL_HANDLE`。第五版让目标 linear 导入图像同样带 `VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT`。最终根盘中的运行时标记与驱动摘要已离线核验并导入设备。Plasma/KWin 硬件合成、真实 Surface 桌面帧和物理输入仍待解锁后的最终验收，完整目标尚未完成。**
- 新的 Venus 桌面运行时需要把 APK 自带的 `libvenus_desktop_crosvm.so` 临时绑定到 **Viruaisor 私有挂载命名空间内**的 `/apex/com.android.virt/bin/crosvm` 视图；不写 APEX 字节，外部命名空间仍看到原文件。用户于 2026-10-16 明确答复：“允许仅本应用的临时绑定，退出后撤销”。在此之前未执行新桌面绑定。

设备检查分开记录：只读 root 检查已取得 UID 0；原有两项 AVF 授权有效；应用的 AVF 检测通过。SELinux 保持 Enforcing；原有 `hidden_api_policy=null`、`hidden_api_blacklist_exemptions=Landroid/system/virtualmachine/VirtualMachine` 未修改。外部 APEX 视图的 crosvm / virtmgr 摘要仍为原值。

最新收尾：第五版候选 ZIP `2ff29ecf27b1fd452ab1b44c852bc2cfb46ce32e24f1f6450177da8c4a494a7e` 已解包并切换到 `files/vms/default`，设备端四个输入文件再次匹配 `SHA256SUMS`。最近的失败会话中 crosvm 正常退出 0，随后取得 `DESKTOP_LEASE_END native_exit=0` 和 `ROLLBACK=PASS`。当前无 VM/helper/显示层，临时绑定已撤销，屏幕处于锁定息屏状态，`screen_off_timeout=120000` 和 Enforcing 均保留。最终 AVF/UI 验证等待用户再次解锁。

## 2026-10-17 实机联调结果

首次启动发现候选 crosvm 缺少 `composite-disk` 构建功能，把 AVF 的 95 字节组合磁盘描述文件当作原始盘，客体看到 0 个块并发生根盘挂载 panic。已补齐该功能和 Cargo 的本地 protobuf 生成模块接入；实际启动证实磁盘变为 8 GiB、`vda1` 成功挂载、Manjaro systemd 和 vsock agent 正常运行。去掉 `pci=nomsi` 的对照测试没有解决问题，现已恢复原参数，没有保留这一无效改动。

关键捕获为 `.pi/device-debug/root-direct-20261016-101631/`：

- 应用 UID 10278 的私有绑定通过外部原文件校验；实际 `crosvm_default` 仍为 UID 10278、caps=0。
- Venus capset 4 大小 160；客体 `vulkaninfo` 返回 `Virtio-GPU Venus (Mali-G2-Ultra-NX MC16)`、`DRIVER_ID_MESA_VENUS`、Mesa 25.0.7。
- Direct Surface / root-owned BLAST 队列接入成功，SELinux Enforcing；这不证明已经显示桌面。
- GLES 预检停在 `DRAW_STAGE=eglInitialize`，随后报 `stuck in ring seqno wait` / `expired ring alive status`；再次运行 `vulkaninfo` 也中止。Plasma 的硬件门槛未通过，没有改用软件渲染。
- 已通过普通应用的 vsock 请求关闭客体；本次租约结束为 `native_exit=0`、`ROLLBACK=PASS`。修复了客体先退出时 Surface 回收遇到 `DeadObjectException` 的处理，仍完整释放本地队列、显示层和父句柄。
- 平台仍缓存了空闲的应用子进程 `virtmgr_uaisor:venus_vm`，退出 Viruaisor 后已清空。正常停止后的再次启动与普通 UID 下的缓存回收仍须实测；单成员命名空间保护保持不变，没有为此放宽 root 校验。
- 用户要求完成后退出应用并让平板自行息屏。本轮退出后没有应用 VM、virtmgr、root 桥接或 native helper，也没有 `ViruaisorRootDisplay` 显示层；外部 crosvm / virtmgr 摘要保持原值。屏幕已锁定关闭，原 `screen_off_timeout=120000` 未修改。

当前桌面 crosvm 已实机运行，renderer 日志接入 `ViruaisorVenusRenderer`，并包含固定 blob 地址映射及 Venus-only 场景需要的 Rutabaga 2D scanout 组件。固定地址修复消除了复用同一 guest GPA 时的 Venus 进程映射冲突；2D 组件负责 Direct Surface 扫描输出，不引入 VirGL/VREND 渲染回退。当前候选摘要见下方。

## 锁屏后的普通应用诊断

未唤醒屏幕、未使用 root。通过 Debug `run-as` 在 UID 10278 的应用私有目录中运行独立原生程序，避开已被冻结的 Activity 进程；系统冻结与电源策略保持原值。

- `libvenus_host_probe.so --repeat-contexts` 在同一个 renderer 实例内依次创建、销毁上下文 1、2、1。每轮用不同输入验证 16 个真实 Mali GPU 计算结果，三轮和最终清理均退出 0。日志为产物目录的 `headless-contexts.log`，`assert-venus-contexts.cjs` 验收通过。这只验证独立宿主上下文和计算，不代表 AVF 图形/WSI/Surface。
- 使用现有 Manjaro glibc、Mesa 25.0.7 Venus ICD、Mesa 24.0.2 Zink，通过本应用私有 Unix socket 连接独立 Vtest renderer。Vulkan 枚举成功；此通道仅用于排错，不替代产品的 AVF / Direct Surface。
- 原始独立 renderer 没有 Android 分配器，创建图形 blob 失败。只接入现有 Android 分配器的对照版揭示：请求内存类型 8，而实际 AHB FD 返回 `memoryTypeBits=0x7e02`，不包含类型 8；之后发生内存对象不存在和 ring 错误。`ZINK_DESCRIPTORS=lazy` / `ZINK_DEBUG=compact` 对照不能解决该错误。
- 实验修正先用短生命周期普通 UID Vulkan device 查询实际 AHB FD 掩码，保守删去不可共享内存类型的 host-visible 属性，并把缓冲区内存要求与真实掩码取交集。仅过滤属性还不够；同时限制缓冲区要求后，实际选择兼容的类型 14，AHB 分配、显式 cache 同步、EGL 1.5、GLES 2.0、Zink/Mali shader 编译及绘制均能执行。
- **原始 `gl_FragCoord` 像素验收仍失败**：三个样本的 R 分量分别为 12、131、243，B=128、A=255，但 G 均为 0，预期为 12、131、243。保留完整三点失败记录，不降低像素检查门槛。去掉 descriptor/debug 参数，或禁用多 ring、异步分配/提交和 batching，都没有解决它。日志包括 `vtest-memory-default-client.log`、`vtest-memory-sync-client.log`，客户端退出 1。
- 独立的顶点 varying 渐变着色器对照通过：实际三个 RGBA 像素为 `[12,12,128,255]`、`[131,131,128,255]`、`[243,243,128,255]`，客户端退出 0。日志为 `vtest-memory-varying-client.log` / `vtest-memory-varying-server.log`。它确认了普通 UID 下真实 Zink/Mali 图形运算及读回，但不能替代原始 shader 的错误排查或 AVF 桌面验收；对照后已恢复原始诊断 shader。
- **Mesa 25.0.7 Zink 对照通过原始 `gl_FragCoord` shader**：同样三个 RGBA 样本全部正确，UID 10278、`GL_RENDERER=zink ... Mali ... MESA_VENUS`，客户端退出 0。日志为 `vtest-zink25-fragcoord-client.log` / `vtest-zink25-fragcoord-server.log` / `.exit`。没有使用 varying 替代原始验收 shader，也没有启用软件渲染；私有 GL 构建只包含 Zink。此结果仍属于独立 Vtest 图形检查，不是 AVF / Surface 桌面成功。

内存过滤补丁通过 `scripts/build-venus-vtest-diag.sh --android-memory-filter` 接入独立诊断构建，并已合入 AVF 桌面候选。AVF 中的原始 GPU shader 三点读回已经通过，客体 virtio 内存路径和实际帧提交均已运行；Plasma/KWin 的最终硬件合成仍待 modifier 修正后的解锁会话。AVF runtime 的严格 system linker namespace 检查没有放宽。

新版 AVF 构建由 `scripts/build-venus-crosvm-candidate.sh` 以显示、DMA-BUF 内存过滤、固定 blob 映射和 Rutabaga 2D 组件组合生成；APK 的运行时摘要 pin 已同步至 `585583f0...`。构建脚本拒绝在已过滤的 renderer 工作树上隐式构建旧 compute / 默认候选，避免混淆产物。

所有诊断程序仅访问本应用目录、GPU 和本应用 socket。外部 watchdog 验证 helper PID 与可执行文件路径后，以普通应用 UID 回收自身进程；Android `timeout` 在息屏期间没有按预期及时结束任务，不能单独作为墙钟时限保证。诊断的成功标记使用 `VTEST_DIAGNOSTIC_*`，不会匹配正式桌面的 `GPU_DRAW_READBACK_PASS` 验收。

## 已准备的内容

镜像使用 [Manjaro ARM 官方 rootfs 20261012](https://github.com/manjaro-arm/rootfs/releases/tag/20261012)，下载文件 SHA-256 为 `1ae540a80e21e1ca55e0576df9618fd2865cf4596b8204d20780f225aca5164a`。实际 ARM stable 包版本是 Plasma / KWin 5.27.10、Mesa 24.0.2、glibc 2.35，并非 Plasma 6。完整版本清单在产物目录的 `packages.txt`。

镜像为 ARM64、8 GiB ext4 根盘，VM 内存 6144 MiB，1280×720 / 60 Hz。保留已适配 AVF 的 Ferrochrome 内核 `6.12.92-android16-6-g4e585dd7f3b7-ab16266940-4k` 和对应模块；Linux 用户空间已替换为 Manjaro。内核和 initrd 是应用 VM 的输入文件，没有写入 Android 系统镜像。

私有 Venus ICD 基于 Mesa 25.0.7，重新针对 Manjaro 的 glibc 构建，最高需要 GLIBC_2.34；动态依赖检查通过。ICD SHA-256：`76bf2b253a8bd945a3477749e3f66e29cb395b20f9afa6a4b6f9b5798b7f5d18`。私有 Zink 25.0.7 安装在客体 `/opt/viruaisor/zink25`，其 ELF 依赖也通过客体 loader 检查；Manjaro 包管理器的 Mesa 24.0.2 保留。`/etc/ld.so.conf.d/00-viruaisor-zink25.conf` 让提权启动的 Xorg 与普通 Plasma 客户端使用同一套私有 EGL/GBM/GLX；KWin 与 Qt XCB 固定使用 GLX，Xorg modesetting / glamor 驱动 Plasma X11。`VIRUAISOR_ZINK_DISABLE_DRM_MODIFIERS=1` 关闭当前 Venus host 拒绝的显式 modifier plane-layout 路径；Xorg 初始 shared scanout 使用 Vulkan linear 图像和单一 DMA-BUF 内存导出，后续无 modifier 导入也使用普通 linear 图像和 DMA-BUF 内存导入，避免 Mali 拒绝图像级外部内存声明。运行时标记包括 `VIRUAISOR_ZINK_LINEAR_SCANOUT_ALLOCATION_ONLY`、`VIRUAISOR_ZINK_LINEAR_DMABUF_IMPORT` 和 `VIRUAISOR_ZINK_LINEAR_IMPORT_ALLOCATION_ONLY`。私有 `libgallium-25.0.7.so` SHA-256 为 `9fbbbf0d3e3b30df3fc90aa422a849b2503d3b2a2af1b8f3b0458194f79e2483`。这些修正仍要求 Zink/Venus Vulkan，不是软件渲染回退。启动前要求真实 Venus Vulkan 驱动、Mali 设备、GLES 着色器三点读回成功；KWin 必须报告 OpenGL / Zink / Mali 合成，之后才发送桌面 ready。软件渲染器或图形失败不能作为成功。

桌面用户 `droid`（UID 1000）的密码登录已锁定，桌面由 vsock 命令启动。该用户在 **Linux 客体内**可用 `sudo` 管理客体，不赋予 Android 权限。启动到 multi-user，Surface 就绪后才启动 Plasma。切到 Android 后台时保留 Linux 会话，重新接入 Surface 时呈现保留的真实客体帧。

应用接入 Android 17 crosvm Direct Surface Binder / native window。当前已将同一 AHB 直接提交到独立子层，保留 AHB 引用用于 Surface 分离与重接；CPU 映射/拷贝只作为显式诊断回退，不计为通过。该设备需要对这一层使用 GPU 合成来避开 HWC 的绿色输出，详见本轮验收记录。

点击内嵌视口进入全屏；Activity 捕获键盘，使用 evdev 扫描码及明确的 Android 键码映射，双击 ESC 的两组按下/释放均转发。全屏请求鼠标指针捕获，转发相对移动、按键、双轴滚动与触摸坐标。失焦/退出时释放已按下的键、按钮和触摸槽。`Ctrl+Shift+Alt+Q` 退出全屏与输入捕获；Android Back / Home 导航保留。连接设备的具体行为还需要实测。

## 权限边界与回收

VM 导入、生命周期、vsock、画面 UI 和输入均在普通应用 UID。新增 root 能力限定为固定摘要的候选可执行文件私有绑定及受保护 Surface 桥接，没有任意 shell / 路径入口。桌面租约带 nonce、root 回调 UID 校验、应用存活心跳和回收确认；停止 VM 后必须获得 `ROLLBACK=PASS`。无法确认回收时终止自身应用进程以销毁该私有命名空间。

锁屏下第一次导入停在 806221836 字节。导入任务增加普通 `WAKE_LOCK` 权限的 partial CPU 唤醒锁，最多 10 分钟，成功或异常均释放；重试仍停在 983670565 字节。`dumpsys power` 显示系统冻结应用，唤醒锁为 `DISABLED ... isFrozen=true`。保持系统策略不变，停止自身导入进程后，使用 Debug 应用的普通 `run-as` 身份在私有目录内准备、校验并原子替换镜像。这不唤醒显示、不解锁屏幕、不改变全局电源设置，也不需要 root。

候选桌面 crosvm 的 SHA-256 是 `585583f011fd94b878141104ea7847ff9034c020b81c50cd5d47537782bcfd11`，大小 14020352 字节，包含组合磁盘支持、renderer 诊断日志、DMA-BUF 内存兼容性过滤、固定 blob 映射和 Venus-only 2D scanout。原有 compute 候选没有替换。对外部命名空间、系统/APEX 字节、SELinux、全局 hidden-API 设置和其他应用均不做修改。实际设备绑定只采用用户确认的应用内临时范围，退出后撤销。

Direct Surface 平台接口没有 VM 所有者参数，因此桥接先要求唯一 crosvm，校验应用 UID、进程名、SELinux 域和已打开可执行文件的摘要；当前仍限定单 VM 实验。若出现新的 Binder / FD / 内存权限拒绝，应记录具体拒绝并修复应用路径，不扩大系统策略。

## 构建与已完成检查

- Android：`:app:testDebugUnitTest` 104 项全部通过；`:app:assembleDebug` 和 `:app:assembleRelease` 成功。
- Debug / Release APK：五个 ARM64 ELF 和桌面 crosvm 摘要校验通过。
- 原生：13 个 C 测试、1 个 C++ 帧缓存测试、3 个 Rust 测试通过，C/C++ 使用 ASan / UBSan。新增内存属性过滤测试覆盖真实不兼容掩码、边界索引和不增加能力的约束。
- Node：68 项脚本测试通过，包括桌面验收分类器、锁屏前置检查、已捕获的 Zink/Xorg 启动失败快速拒绝、Zink 线性 DMA-BUF 补丁打包门槛、Manjaro `startx` 依赖门槛、三轮上下文验收和本次租约的回收验收。设备入口测试使用模拟 ADB，不启动设备 VM；回收入口还验证了进程先退出、租约结束日志稍晚到达的情况及 Windows / Git Bash 的路径传递。
- 客体 agent 协议测试通过；Venus token 映射至 `avf-plasma.service`。
- 离线 QEMU ARM CPU 模拟：Manjaro 到达 systemd Multi-User System，无 init 缺失或内核 panic。使用快照盘，未改变产物盘。该检查不证明设备 AVF、GPU 或 Surface。
- `git diff --check` 通过。代码和产物没有提交。

产物目录：`.pi/device-debug/manjaro-plasma/`（已忽略）。包括 `manjaro-plasma-venus.zip`、`Viruaisor-manjaro-debug.apk`、本地 debug 证书签署的 `Viruaisor-manjaro-release-local.apk`、`SHA256SUMS`、`APK-SHA256SUMS`、`packages.txt` 和 `icd-loader.txt`。本地 Release 不是生产签名。两个 APK 的证书与已安装应用一致。

当前 APK SHA-256：Debug `ec7b148e52ea19a703be353aa502fb355360af067edefcc7bc4de6256a166e64`；本地 Release `e937563501315f3ef6cfa728456d605d825ca8a319cd5ac02097c7670996fc30`。独立宿主 probe 摘要为 `c8506950a14a56c9795b1163057c11ae76b08d33e26c6461a0b5cee9365c2329`。Debug APK 已更新安装。

最终候选 ZIP SHA-256：`54d9215c97281b905cc513f4671c8882d2bc793d6a52518c52bcfdd2d57dd6b8`；根盘：`345d726b8af764d8f6309365ab09db3465bd54d14fe7a8ace31880b8dadd7c9a`。设备发布记录为 `device-installed-linear-import-allocation-only-SHA256SUMS`，四个 VM 输入与最终 `SHA256SUMS` 一致。实际启动后可写根盘的摘要会正常改变。最终替换由应用自己的导入流程执行，并有 `VM bundle imported` 状态记录。

重建入口（WSL）：`scripts/prepare-manjaro-rootfs.sh` → `scripts/build-manjaro-venus-icd.sh` → `scripts/build-manjaro-zink.sh` → `scripts/build-manjaro-bundle.sh`。构建依赖既有、固定版本的 crosvm / Mesa / NDK 工作目录，GL 编译还需要宿主 bison/flex，细节见脚本。主机 rootfs 准备在隔离挂载命名空间中运行；这些主机操作不作用于 Android。

## 解锁后的验收步骤

私有临时绑定已获用户明确允许。重复验收需屏幕已解锁，不修改 APEX 或全局策略。完成后关闭 VM、撤销绑定、退出应用，让平板按原设置自行息屏。

1. 确认没有其他 VM，确认应用已导入 `osName=manjaro` / `gpuProfile=venus` 的最终镜像。
2. 在 Git Bash 运行 `bash scripts/test-root-direct-surface.sh 180`。脚本检查屏幕已解锁，持续保存本次日志，同时验证 KWin OpenGL、直接提交和截图像素；失败时保留证据。
3. 可重复运行 `node scripts/assert-manjaro-desktop.cjs <目录>/logcat.txt <目录>/frame-report.json`，旧 compute 成功、CPU fallback 或单独 setSurface 成功不够。
4. PowerShell 运行 `scripts/assert-surface-frame.ps1 -OutDir <输出目录>`；另核对实体屏幕，因为 HWC 绿色输出不会出现在截图中。`scripts/test-display-resize.ps1` 可测试运行时 mode 往返与 PID 不变。
5. 在 Plasma 打开桌面上的 **Viruaisor Input Check**，其 30 秒记录只读 VM 虚拟输入、不 grab 设备，写入 `~/viruaisor-input-check.jsonl`。确认两次 ESC 各有按下/释放，字母/修饰键、触控板移动/点击/双轴滚动、触屏点击/拖动/多点、窗口拖动均有效。
6. 验证 `Ctrl+Shift+Alt+Q` 退出捕获；Back / Home 能切出 Android；返回应用后 Konsole/桌面会话仍在，且没有粘住的按键、按钮或触摸点。
7. 运行 `bash scripts/test-root-display-cleanup.sh <输出目录>` 停止自身 VM。对于 Venus 捕获，脚本还会生成 `venus-cleanup-report.json`，要求最新租约的匹配 `DESKTOP_LEASE_END`、native exit 0、结束记录后的 `ROLLBACK=PASS`、无回收错误/VM/helper/显示层遗留；在同一次停止请求内等待结束日志，最多约 30 秒。再只读核对原始 crosvm/virtmgr 摘要、AVF 授权及全局设置。crosvm 对 shell 不可读，需要时仅用固定文件的只读 `su -c sha256sum` 检查；不更改权限。

原始 APEX 文件摘要应为：crosvm `e2855ffd29db9826431af49436a66324fde5a1c2179549c480a663ff1ca60e2d`；virtmgr `081f600e64780dbfd8d17681d4bd36813fb5dc1e86c268452932a028eef5a78c`。这些摘要从应用外部视角读取。

原有 Venus compute 的实机结果见 `docs/venus-guest-runtime-validation.md`，仅用于说明配对驱动的既有基础，不充当本次桌面的验收结果。GL / WSI / Mali 图像内存与真实扫描输出可能仍暴露需修复的问题；不能承诺仅解锁就已满足硬件加速要求。
