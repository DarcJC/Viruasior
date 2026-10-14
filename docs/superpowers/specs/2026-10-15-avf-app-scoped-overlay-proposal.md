# Viruaisor 专属临时 AVF/crosvm 覆盖方案（待审核，未部署）

日期：2026-10-15，Asia/Singapore。

更新：用户随后明确授权开始测试此临时、可撤销方案，包括后台安装普通 APK 和按门禁进行
实验。下文的“待审核/未部署”描述保留为方案提出时的状态；实际执行结果另行记录。
禁止直接替换系统文件/APEX、全局覆盖、修改策略或持久安装的约束继续有效。

本文件是用户要求的方案草案，不是执行授权。当前仅核查源码、已有 GPU 记录和设备进程/
挂载元数据；没有安装新 APK、启动 VM、执行 setns/mount、替换文件、更新 APEX 或刷写分区。
本轮只新增方案/研究文档，并对已有报告的结论范围作文字澄清。

## 1. 结论与约束

建议研究 **Viruaisor 专用普通应用进程的独立 mount namespace + 单文件只读 bind 覆盖**。
这里的 overlay 指进程私有文件视图中的覆盖，不是全局 OverlayFS、`adb remount`、
APEX 更新或开机 root 模块。

可以争取隔离的是“哪些进程看到并执行候选 crosvm”。不能保证 GPU 固件、宿主内核和
系统显示故障只影响本应用：这些资源依旧由 Android 与其他应用共享。
如果要求任何故障都绝不影响其他应用，则此路线不能满足，应使用独立测试设备。

硬约束：

- 不写入、删除、替换 `/system`、`/vendor`、`/product`、`/system_ext` 的原始文件或任何系统分区。
- 不改 AVF APEX 包、原始文件 inode、签名、激活版本或全局 APEX 挂载。
- 不进入 PID 1、Zygote、system_server、全局 virtualizationservice 或其他应用的 namespace 做覆盖。
- 不装 Magisk/KernelSU 持久模块，不改启动脚本、系统属性、系统设置、权限授予或 SELinux 策略。
- 保持全局 Enforcing；不新增 permissive 域或扩大共享 app/shell 域的规则。
- VM 生命周期、镜像、vsock、回调和输入仍由普通应用 UID 执行；root 仅负责已批准的挂载租约。
- 不接触用户原始 guest rootfs 的可写状态；实验使用独立、可丢弃 guest 数据。
- 任何门禁失败即停止，不自动转向全局 overlay、root 运行整台 VM 或系统组件更新。

**需要准确理解的边界**：私有视图会将
`/apex/com.android.virt/bin/crosvm` 这个路径解析到候选副本，而不是原始 crosvm。
原文件、APEX 包和其他 namespace 的解析结果不变。若“禁止替换”也包含禁止这种
私有路径遮蔽，则不能采用本方案；现有 AVF 没有可选 crosvm 路径的公开接口。

## 2. 已核实事实与尚未证明的部分

### 已核实

- 现有 Release APK 在普通 UID 10278 下完成三次 Venus 宿主计算验证，16/16 正确且退出 0。
  工作路径是 DMA heap 外部分配、Vulkan 导入、renderer 资源 FD 导出/映射，不是驱动原生导出。
- 同一设备此前 UID 0 与 UID 10278，对已测 `STORAGE_BUFFER | TRANSFER_DST`、flags=0 的
  buffer 查询均返回 DMA-BUF features=4、exportFromImported=0，Opaque FD features=0。
- 正式主 APK 的 AVF 探测已将 Venus 配置传到 crosvm，但报
  `invalid rutabaga build parameters` 并 SIGABRT；临时 VM 已清理。
- AOSP AVF 的 app-side `VirtualizationService.nativeSpawn()` 经 libvmclient 启动
  `/apex/com.android.virt/bin/virtmgr`；virtmgr 再启动固定路径的 crosvm。
  这是应用的实际子进程链，不是把 app namespace 通过 Binder 自动传给全局服务。
- Android 17 Zygote 源码在应用特化时确保 app mount namespace；此次只读实测也确认主应用
  与 Zygote、PID 1 的 mount namespace 不同。

此次设备快照，仅用于解释，不得作为后续部署的硬编码 PID：

| 进程/文件 | 只读结果 |
| --- | --- |
| Viruaisor 主进程 PID 28357 / UID 10278 | mnt:[4026536932]；CapEff=0；Seccomp=2 |
| Zygote PID 864 | mnt:[4026536794] |
| PID 1 | mnt:[4026535574] |
| 稳定 `/apex/com.android.virt` 挂载 | ro，无 shared/master 传播标志 |
| 原始 crosvm | 类型 crosvm_exec；SHA-256 `e2855ffd29db9826431af49436a66324fde5a1c2179549c480a663ff1ca60e2d` |
| 原始 virtmgr | 类型 virtualizationmanager_exec；SHA-256 `081f600e64780dbfd8d17681d4bd36813fb5dc1e86c268452932a028eef5a78c` |

### 尚未证明

独立实验进程的 OEM 实际继承链、root helper 的 setns/mount 权限、临时副本可用的 SELinux
标签和执行入口、候选 crosvm 的 OEM ABI/动态链接兼容性，以及 guest blob 的 KVM 映射、
缓存一致性、fence、GPU 读回、图形输出和完整回滚，均需逐项验证。

源码与核查依据见 [隔离研究](../../avf-app-scoped-overlay-research.md)、
[宿主验证](../../venus-host-validation.md) 和 [宿主构建要求](../../venus-host-build-research.md)。

## 3. 备选路线

| 路线 | 判断 |
| --- | --- |
| A：专用 app 进程 namespace，保留 stock virtmgr，仅覆盖 crosvm | 推荐候选；改动最少，生命周期和权限保持普通 app 模式，但仍有执行标签/ABI/内存门禁 |
| B：app 自带私有 virtmgr/VM launcher，不使用固定 APEX 路径 | 改动更大，需自行适配 Binder RPC、调用身份、域转换和平台 ABI；不是 A 的静默降级方案 |
| C：全局 overlay、替换系统文件/APEX、持久模块 | 违反用户约束，排除 |

仅更改 guest ICD、仅放入一个 renderer .so、仅使用一个私有 root shell 的 namespace，
都不会自动让现有 app/virtmgr 使用 Venus 后端。

## 4. A 路线的拟议改动范围

### 应用层

- 在同一正式 APK 内增加不导出的 `:venus_vm` 专用普通进程，保留 app UID 和现有 AVF 权限；
  不用 `isolatedProcess=true`，也不把 UI、现有 VmService 或默认 VM 迁进去。
- 进程首次访问任何 AVF 实例前先等待覆盖准备握手，避免缓存/复用覆盖前启动的 virtmgr。
- 实验入口默认关闭，只接受主 app 的显式操作；取消或失败时保持原有默认路径。
- 同一时间仅一个实验租约，与该 app 的正常 VM 启动互斥。

### 构建产物

- 构建 arm64/API 37、匹配该设备 AVF 控制/平台接口的候选 crosvm，启用 rutabaga 的
  virgl_renderer/Venus 支持；优先静态链接 Venus renderer，避免覆盖整个 lib64 目录。
- 纳入已有 renderer 对象清理修复、经验证的本进程 libcrypto 生命周期处理。
- 将 Android 外部分配/导入实现接入 **真实 renderer 分配与 virtio blob 路径**；不能只复用
  当前 host harness 手工导入资源后，就宣称标准 guest 内存分配已可用。
- 每个产物固定源码、编译选项、许可证、build ID、SHA-256、ELF 依赖和目标设备基线。
  上游 AOSP 构建成功不等于与 OEM 的 virtmgr/crosvm ABI 兼容。

### 最小 root 组件

- 仅暴露 prepare/revoke/inspect 某个租约等固定操作，不接受任意 shell、路径、PID 或挂载目标。
- 验证完整应用 UID、包身份、签名、nonce/私有 FD 通道；通过 pidfd 和启动时间识别确切实验进程。
- 验证目标 namespace 是该实验进程独占，而非仅与 init/zygote inode 不同。
  枚举所有可观测成员；存在无关成员或无法确定时拒绝。
- 只进入该进程已有的 namespace，并在其中确认/建立所需的私有传播属性，再进行挂载。
  **进入后不能再 unshare 出 helper 自己的副本**，否则目标 app 看不到覆盖。
- 对经过摘要验证的候选构建只读、不可篡改的临时快照，防止 app 可写文件、符号链接和
  校验后替换造成 root TOCTOU 风险。只允许清单中的单文件目标，初始目标仅 crosvm。
- 候选 inode 不会因挂在 APEX 路径下就自动取得 `crosvm_exec` 标签。
  临时产物 owner/mode/标签或挂载上下文的处理必须单独列明、先验证现有策略允许；
  不在当前方案中注入 SELinux 规则或重标记原始系统文件。
- 设置 worker、namespace、mount、FD、payload 的租约和有界看护。挂载准备 helper 完成后退出；
  如回滚需要看护进程，它只能保留租约撤销所需的固定能力，不能成为通用 root daemon。

拟议路径是：普通 `:venus_vm` → 未修改的 stock virtmgr → 私有视图中的候选 crosvm。
全局 virtualizationservice、Terminal 和其他应用继续使用原始 APEX 视图。

## 5. 分阶段验证与停止条件

以下都是 **审批之后** 的执行步骤，本轮没有执行。

1. **离线构建/审查**：比对设备 fingerprint、原始文件摘要、AVF/crosvm 平台接口和 ELF 依赖；
   对 root 接口身份、PID 复用、路径/摘要篡改、租约超时、撤销幂等性编写先失败后通过的测试。
   不在设备上挂载或启动 VM。
2. **隔离与撤销演练**：首次只在专用进程的应用自有测试路径做无害 sentinel 只读覆盖，不碰
   APEX 路径、不启 VM；验证 UI/zygote/init/其他 namespace 看不到 sentinel，并演练取消、
   app 死亡、helper 死亡和超时回滚。通过后才考虑 crosvm 目标。
3. **执行权限门禁**：先使用字节不变的 stock crosvm 临时副本验证来源 inode 的标签、mount flags、
   执行/domain transition 和依赖解析。不能用只读属性、路径相同或 UID 0 替代这个证明。
   如需真实 virtmgr→crosvm 域转换验证，使用关闭 GPU、无磁盘的临时 VM；root 下单独运行
   `crosvm --version` 不能证明这条执行权限链。
   若必须变更系统标签、系统策略、全局挂载或升级权限，则停止并报告，不继续扩权。
4. **后端初始化**：绑定候选 crosvm 的单文件只读覆盖；从普通进程调用 AVF 启动无磁盘小 VM，
   验证实际执行 build ID、进程父子链、namespace、UID/域、有效参数和 Venus capset 4。
   `run()` 返回、不崩溃或日志出现 Venus 均不是最终通过。
5. **guest 内存与计算**：使用可丢弃 initrd/guest 或独立实验镜像，验证 guest virtio-gpu 的
   RESOURCE_BLOB/HOST_VISIBLE/CONTEXT_INIT 等能力、真实 guest Mesa Venus ICD、KVM blob 映射、
   CPU→GPU→CPU 的依赖与缓存维护。多种非平凡输入、至少三轮逐项正确读回；任何软件 GPU
   fallback、未变化哨兵、超时、清理崩溃都失败。不得伪造 HOST_COHERENT 来绕过缓存问题。
6. **正常关闭与回滚回归**：先证明 VM 和全部 renderer 线程正常退出、资源归零、原始视图恢复，
   再另行进行图形/presentation 验证。compute 通过不等于已显示加速桌面；初期不使用全局
   Direct Surface 桥接，也不与其他 AVF VM 并行启动实验。

建议首轮上限：准备/握手 30 秒，单次计算 30 秒，guest 启动 60 秒，总租约最长 5 分钟，
一个实验 VM，1–2 vCPU、最多 1 GiB guest RAM。超出先停，不自动提高资源限额。
完整的 namespace/mount 扫描、GPU/内存 gate 任一不可读或结果歧义，均按失败处理。

## 6. 回滚方式

### 正常结束、主动取消或一般失败

1. 普通应用停止实验 VM，关闭其 RPC/console/vsock 连接，等待该实验的 crosvm、virtmgr 和
   renderer 后代退出；禁止按进程名全局 pkill。
2. 若有界正常关闭失败，只通过已验证的 pidfd 终止本租约的进程，不重启全局服务、不误杀主 UI。
3. 在原目标 namespace 内按逆序撤销本租约记录的 mount，释放临时快照、namespace FD、
   pidfd 和看护进程。撤销错误要明确报出，不能用 lazy unmount 当作完整回滚的证明。
4. 重新读取全局/其他 namespace 的原始摘要、实验进程与 FD/mount 存活状态；确认主 app 的
   普通启动路径仍使用原始 crosvm。只有这些检查通过才显示“回滚完成”。

### app 或 helper 崩溃

租约看护通过客户端死亡/截止时间执行同一撤销流程。namespace 可能被子进程或打开的
namespace FD 保持，不能假设主 app 一死覆盖就消失；反之也不能只卸载文件却让旧候选代码
继续在存活进程中执行。清空本租约全部引用后私有 namespace 才可释放。

### GPU/宿主严重故障

若 GPU/系统显示挂死，进程清理可能不足以恢复 GPU；人工重启是应急选项，不是常规回滚
步骤或成功保证。因为不创建开机覆盖/持久模块，重启后不会重新启用该实验。
重启不能撤销已经写入的 guest 数据，所以实验始终不写用户原始磁盘。
不要卸载主 app 或清除数据来回滚 overlay，也不改动设备既有的其他 root 模块/挂载。

## 7. 主要风险

| 风险 | 控制与失败边界 |
| --- | --- |
| 挂载传播或 namespace 身份误判，影响其他 app | 独占进程 + 成员/传播双重检查 + sentinel 演练；不确定即拒绝 |
| 私有副本仍被 SELinux/执行入口拒绝 | 独立门禁；不新增策略，不假设 bind 后继承原路径标签 |
| OEM crosvm 与候选 ABI/动态库不兼容 | 匹配平台接口/依赖；保留 stock virtmgr；禁止整 APEX/lib64 覆盖 |
| 候选代码不受原始 OEM APEX 签名认证 | 原 APEX 验证状态不能证明候选可信；另验 APK/产物来源、签名、固定摘要与完整性 |
| guest DMA-BUF 缓存或 KVM 映射不正确 | guest 真正映射和多轮计算 gate；宿主手工 DMA_BUF_SYNC 通过不足以替代 |
| GPU/内核故障跨应用影响 | 小资源、串行实验、短期限；仍有系统挂死/需重启风险，不能声称完全 app 沙箱隔离 |
| root helper 成为高权限入口或遗留挂载 | 身份/产物白名单、不可变快照、pidfd、租约看护、幂等撤销和残留审计 |

KernelSU 既有 root 域可能拥有很宽的权限；全局 Enforcing 本身不证明 helper 被强制最小权限
隔离。本方案依赖设备已有 root 信任，不能对抗已经被攻陷的 root，也不会扩大既有策略。

## 8. root 能否支持驱动原生 FD 导出

不能把“改用 root”当作补齐导出能力的方案。本机已有 UID 0 对照记录，对同一已测 buffer
组合与普通 app 结果一致：DMA-BUF 仅 IMPORTABLE，Opaque FD 不支持。
Vulkan 定义 EXPORTABLE=0x2、IMPORTABLE=0x4；仅有 0x4 不是原生导出许可。
查询结果按资源参数/句柄类型成立，不能扩展成所有图像、格式、usage 都永远不支持。
来源：[Vulkan feature 定义][vk-features]、[按 buffer 参数查询能力][vk-query]。

root 可以帮助打开被权限挡住的 udmabuf/分配器，但 UID 0 也不天然绕过 SELinux。
外部分配 DMA-BUF → 导入 Vulkan → 把原 DMA-BUF FD 交给 renderer/guest，是可研究的
Android 内存路线；它不等于从任意驱动创建的 VkDeviceMemory 原生导出 FD。
外部创建 FD 后查询兼容内存类型是 Vulkan 明确区分的导入用途。
来源：[外部 FD 导入查询][vk-import]、[Android SELinux 权限模型][selinux]。

本项目的普通 APK 已无需 root 完成 DMA heap 路径，因此后续优先延续它，不把整套 GPU/VM
移到 root。另有资源类型的原生导出能力，只能通过合法参数查询/实测另行确认，不能用
强行调用不支持的导出 API、修改能力位或绕过驱动校验作为实现。

## 9. 审核后建议的下一步

先批准或修改 **第 5 节步骤 1 的离线实现/测试范围**。sentinel 挂载、crosvm 路径覆盖、
启动实验 VM 各自都应在看到产物/门禁结果后再明确确认；审核本文不等于授权直接部署。
若需要扩大单文件目标、修改 stock virtmgr、引入策略变化或永久安装，应提出新方案，不能
作为本方案的自动后续动作。

[vk-features]: https://docs.vulkan.org/refpages/latest/refpages/source/VkExternalMemoryFeatureFlagBits.html
[vk-query]: https://docs.vulkan.org/refpages/latest/refpages/source/vkGetPhysicalDeviceExternalBufferProperties.html
[vk-import]: https://docs.vulkan.org/refpages/latest/refpages/source/vkGetMemoryFdPropertiesKHR.html
[selinux]: https://source.android.com/docs/security/features/selinux/concepts
