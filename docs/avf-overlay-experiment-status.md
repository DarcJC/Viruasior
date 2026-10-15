# 私有 AVF overlay 实验进度

日期：2026-10-15（Asia/Singapore）。用户已授权开始测试；禁止系统文件/APEX 替换、全局
挂载、刷写、策略/权限/系统设置变更的约束不变。

## 当前结论（14:03～14:07 最终复测）

**探针修复已通过正常与 app 错误路径的实机验证，外部残留审计通过。Venus 已推进到
真实 Android arm64 crosvm 编译基线产出，但未部署候选，也未证明 guest GPU 加速。**

- 当前普通非debuggable主Release APK：
  `.pi/device-debug/venus-overlay/Viruaisor-stock-probe-final-release.apk`，SHA-256
  `932f7e43af700187101bc203083c9d889eb869c16912377185932098f6229e34`，原开发签名不变。
  没有独立测试app，没有打包或部署新crosvm候选；仍仅允许固定stock副本/哨兵的root租约。
- app-only固定错误注入（不改root RPC）：token `e8ad0f620a894ab892c407682a1e33cf`，worker18666，
  virtmgr18790 → crosvm18813，副本dev65100/ino151065。普通AVF已stop/delete后故意抛错，
  正确报告OVERLAY_PROBE_VERIFIED=false；finally仍先完成release/等待，记录
  CLEANUP_NATIVE_RESULT=native_exit0和ROLLBACK=PASS，随后文件删除和同步退出。没有再出现
  fixed-control ENOENT。它证明app异常收尾，不等于worker/VM存活时的死亡或lease-timeout演练。
- 同一最终APK正常复测：token `a6313ab7691b4dd8a506e72e0328507a`，worker19060，namespace
  4026537003，virtmgr19163 → crosvm19187。副本dev65100/ino83733，UID10278、crosvm caps0、
  virtmgr精确caps800000，两个预期域正确。固定no-rootfs panic fixture保持status1，普通AVF
  stop/delete；native_exit0、ROLLBACK=PASS、OVERLAY_STOCK_EXEC_VERIFIED=true。
- 14:07:12外部审计确认：两个token均无mount/namespace线程/FD引用，lab为空，无临时VM定义，
  worker/helper/virtmgr/crosvm均已退出，只剩主app17111。原crosvm/virtmgr摘要、crosvm原dev/inode、
  init/zygote namespace不变；Enforcing，两项既有AVF授权不变，无测试包。
- 82项Android回归、Debug/Release构建和四组native回归通过；APK签名/本地库打包已核验。
  完整console原因、能力基线、清理修复见下一节。userspace、Surface和guest GPU/frames仍未验证。
- Venus本地编译基线13,261,040字节，SHA-256
  `dc5abb38e26ecb310e609e47734116875e9bb4f21e929a25b6cb87ef8d93dbd6`；AArch64 PIE、16KiB对齐、
  链入vkr/virglrenderer，真实native依赖闭合。它仍使用锁定的registry rutabaga alpha，尚未改为
  固定本地Android rutabaga，也未接embedded server/Android guest allocation及缓存同步。
  详见[构建结果与剩余门禁](android-crosvm-venus-build-status.md)。本地构建不改变设备系统组件。
- 最终补充证据在 `.pi/device-debug/venus-overlay/`：`stock-repaired-app-error.log`、
  `stock-repaired-app-error-native.log`、`stock-repaired-final-normal.log`、`stock-repaired-final-audit.log`。

## 13:37～13:45 诊断与首轮修复记录

**无覆盖 capability 基线、guest console 原因定位、修复后的 stock 私有执行和完整正常回滚
均已通过。正在推进本地 Venus-enabled crosvm 构建；尚无候选部署/guest GPU 成功结论。**

### 原因与最小修复

1. 无覆盖的普通 app worker PID16267（未绑定 root RPC、未挂载）启动固定 diskless VM；
   原 virtmgr PID16299，exe dev1896/ino832512，UID四值10278、CapEff0x800000，域为
   `untrusted_app_all_virtualizationmanager:s0:c22,c257,c512,c768`。其原始filecap字节为
   `0100000200008000000000000000000000000000`，对应既有CAP_SYS_NICE；固定AOSP17
   [canned_fs_config](https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/build/apex/canned_fs_config)
   也包含相同配置。原版crosvm PID16337/ino831872同样CapEff0x800000；不能说它原本为0。
   临时副本刻意不复制filecaps，运行crosvm为0，与原virtmgr的精确值分别校验。没有修改任何
   filecap、系统文件或授权；观察器同时增加virtmgr精确SELinux域校验，其他身份门禁保留。
2. 普通app通过AVF捕获console/log，两路并发排空、每路只保留256KiB。无覆盖console24174字节
   明确记录 `VFS: Unable to mount root fs on unknown-block(0,0)`，随后panic；固定测试没有磁盘/
   initrd，`panic=-1`导致立即reset。现在该固定fixture只改为panic=0并检查预期panic标记，
   保持kernel停留到普通AVF stop/delete。它是执行/KVM门禁，不是userspace成功启动。
3. 错误路径改为先释放并等待native结果，再关root连接、删除会话文件；若回滚不确认则保留
   control等待owner-death/namespace撤销，不再在native轮询时抢先删除。清理顺序有先红后绿回归。
4. 首次修复执行成功后，外部审计发现worker16903/virtmgr17026仍保留，cgroup.freeze=1、
   cgroup.events frozen=1。原先stopForeground后延迟300ms退出依赖下一次main-loop调度，
   被OEM冻结影响。改成同步退出一次性worker，异常也finally执行；没有修改冻结/省电策略。
   新增两项回归先失败再通过。此前这一轮不能称作完整进程/namespace回滚通过。

### 最终正常路径证据

- 13:44:37～13:44:49，token `42abcd40e5a5439f904a4e1cdf243a01`，CID2067：worker17494，
  target namespace4026537003、helper原namespace4026537004、初始members1；RO/NODEV，NOSUID0、
  NOEXEC0、0555、filecaps=none。原文件dev1896/ino831872，副本dev65100/ino83554。
- 原virtmgr17609/PPid17494，UID10278、caps800000、正确域；副本crosvm17631/PPid17609，
  UID10278、caps0、正确crosvm域。两者同target namespace；native明确记录副本exe dev/inode。
- console证实kernel进入预期no-rootfs panic，panic=0保持VM status1，随后普通app stop/delete。
  原AVF stop会记录forcible SIGKILL；这是该diskless已panic fixture的清理，不涉及任何用户磁盘。
  native_exit0、ROLLBACK=PASS、OVERLAY_STOCK_EXEC_VERIFIED=true；worker同步退出、virtmgr自动退出。
- 13:45:42外部审计：无该namespace线程/FD、实验mount、worker/helper/virtmgr/crosvm或临时VM
  定义；lab为空，仅主app16542保留。原两个系统文件hash及crosvm dev/inode不变，Enforcing。
  只有正常主包，无独立测试app；两项已有AVF授权不变，非debuggable。
- 四组native测试通过，policy/copy/mount-profile含ASan/UBSan，NDK编译通过；82项Android
  单元测试0失败、Debug/Release构建通过。新测试覆盖能力位/域、输出溢出排空、panic参数、
  正常/异常清理顺序及同步退出。APK包含原Binder shim、host/overlay worker。
- 当前主Release APK：`.pi/device-debug/venus-overlay/Viruaisor-stock-probe-synchronous-exit-release.apk`，
  SHA-256 `3c0286f22569aa73f26312c8279b1d02d4ca41f1c5a5ec45f19a7abe0e610a81`；同原开发签名，
  不是新增生产签名。overlay native SHA-256
  `ecd6dbb9b24eda7a4c76acc6ba4391efdf8b40ca98ed564d8a4a75dbfbf6555c`。
- 证据：`stock-baseline-process-observer.log`、`stock-baseline-console.log`、`stock-baseline-app.log`、
  `stock-repaired-first.log`、`stock-repaired-sync-exit.log`、`stock-repaired-sync-exit-audit.log`。
  设备另保留app自有的bounded console/log文件；它们是诊断数据，不是测试app或执行hook。

### 下一门禁

正常stock执行通过，不代表睡眠/冻结时的严格墙钟租约保证或stock后代异常撤销已经通过。
这两项仍需在候选部署前独立核验。接下来仅做本地候选构建及依赖/AVF兼容性检查；不把
rust target安装、源码checkout或成功链接写成Venus后端/guest加速通过。

## 13:26～13:29 结论（历史）

**已证明普通 AVF 在私有 namespace 中实际执行字节不变的 stock crosvm 副本。
完整自动门禁仍失败，已回滚并停止；没有部署 Venus 候选或证明 guest GPU 可用。**

用户确认了[单文件 mount 标志对照](superpowers/specs/2026-10-15-stock-overlay-mount-flags-proposal.md)：
只对固定摘要的 stock-copy 去掉额外 NOSUID，sentinel 保留；RO/NODEV、root:root 0555、
无 SUID/SGID/file capabilities、只读 FD、源/副本字节与标签校验全部保留。新增实际 mount flags
读回，只有 profile 精确符合才发 MOUNT_READY。没有改系统/APEX 原文件、权限、策略或设置。

### 直接执行证据与停止原因

- 13:26:10 / CID2063、13:27:17 / CID2064 两次均不再 EACCES，crosvm 进入 KVM 初始化。
- 第二次 token `56901658d86f4b67b8d6fb05acec182a`：worker PID15225，独占 namespace
  4026537002，helper原 namespace4026537003。mount flags1029（RO/NODEV、无 NOSUID/NOEXEC），
  root:root 0555、filecaps=none，副本 dev65100/ino141375；外部原文件仍 dev1896/ino831872。
- 外部只读 `/proc` 观察器记录原 virtmgr PID15350/PPid15225、原 inode832512，接着 crosvm
  PID15379/PPid15350 的 executable **dev65100/ino141375，确为副本**。两者与 worker 同 namespace；
  crosvm 四个 UID均10278、CapEff0、NoNewPrivs0，SELinux 为
  `u:r:untrusted_app_all_virtmgr_crosvm:s0:c22,c257,c512,c768`。这不是仅凭 AVF run 返回的推断。
- 完整自动门禁未通过：原 virtmgr 的 CapEff 实际0x800000，观察器却硬编码要求它为0，
  因而未记录 VIRTMGR_OBSERVED/STOCK_EXEC_OBSERVED。不能把这个身份检查直接放宽；需先
  独立核实 stock 行为/能力来源，再审查精确允许值。crosvm 本身 CapEff0 已有直接证据。
- diskless/GPU-off VM 在约0.3～0.7秒后 guest reset，crosvm exit32，普通 app稍后 status0。
  尚无 guest console 根因证据；没有把此现象确诊为 panic，也未改变既定 VM 配置来延长运行。
- app 错误路径先删除 control，native 因而记录 fixed-control ENOENT，但随后两次均显式
  ROLLBACK=PASS、移除 root snapshot。这个收尾/报告顺序也应在下一轮探针修正中覆盖。
  普通 AVF删除独立 VM 定义；未执行新权限、策略或候选部署。

### 构建、部署与最终审计

- mount-profile 测试先红后绿；native policy/copy/真实执行/mount-profile 四组通过，
  policy/copy/mount-profile 另含 ASan/UBSan；NDK arm64/API37编译通过。
  72项 Android单元测试0失败，Debug/Release构建通过，APK含 Binder shim/host/overlay worker。
- 当前普通非 debuggable Release主APK（同原开发证书，保留数据与UID）为
  `.pi/device-debug/venus-overlay/Viruaisor-overlay-stock-mountflags-release.apk`；SHA-256
  `a159b85484fbe989adaeaa08d47989bb394a8321d84e069c54a624423994fa48`。
  native SHA-256 `4c061fd19e8b2361541d440ab7268ceafd7fcaa623a8b19c25cadd586a897d74`。
- 13:28:55 最终只读审计：SELinux Enforcing；原 crosvm/virtmgr摘要不变、crosvm原dev/inode不变；
  init/zygote namespace仍5574/6794；无目标4026537002线程或FD引用、无实验mount、临时VM定义、
  worker/root helper/virtmgr/crosvm，lab目录为空。正常主app PID13465保留，其namespace6964。
  只安装`pro.darc.viruaisor`，没有独立测试app，已有两项AVF授权仍在；没有新增授权。
- 使用正常`adb install -r`后的可运行窗口，未改变后台、息屏或解锁策略；长期冻结/锁屏的租约
  保证仍未解决。stock VM 后代异常回滚也尚未验证，哨兵死亡测试不能替代。
- 证据在忽略目录 `.pi/device-debug/venus-overlay/`：`stock-mountflags-full.log`、
  `stock-mountflags-retest-full.log`、`stock-mountflags-process-observer.log`、
  `stock-mountflags-final-audit.log`。按本轮停止条件，不继续修改检查或 VM 配置。

## 12:52～13:18 结论（历史）

**无害覆盖、正常/超时回滚、客户端死亡与 native helper 死亡后的隔离释放已实机验证。
原版 crosvm 字节副本的私有覆盖成功，但真实 AVF 执行门禁未通过；尚未部署 Venus 候选。**

| 门禁 | 实机结果 |
| --- | --- |
| 普通 UID 哨兵可见、主进程/root 对照不受影响 | 通过 |
| 8 秒租约超时并显式卸载、恢复 ORIGINAL | 12:52:17 通过，native exit=0、ROLLBACK=PASS |
| 客户端死亡后的 native 撤销 | 12:57:22 通过，OWNER_DIED、ROLLBACK=PASS |
| native helper 异常退出后的 namespace 释放 | 13:00:20 通过，exit=23；worker 退出后外部复查无 mount/ns/FD 残留 |
| 字节不变 stock crosvm 的私有覆盖 | 通过，源 dev/inode 与临时副本不同，外部原文件不变 |
| 普通 AVF 真正执行 stock 副本 | 未通过：先 ETXTBSY，修复后 EACCES；没有虚构启动/guest GPU 成功 |

### 本次定位和修复

1. 客户端死亡时 root service 的 onDestroy 立即强杀 native child，打断其 pidfd 回滚，留下
   root-owned 临时快照。先用真实 JVM 子进程写失败测试，再增加最多 1 秒退出宽限；复测看到
   OWNER_DIED 后 ROLLBACK=PASS，root 快照自动删除。app 被杀后不能执行 finally，普通测试
   `.target/.control/.outside` 由外部审计确认 namespace 已释放、内容 ORIGINAL 后逐个清理。
2. helper-death 模式故意 `_exit(23)`，由普通 worker 的退出释放 namespace，而非把它写成显式
   umount 成功。source/control/outside 自动清理；挂载点 `.target` 初次删除 EBUSY，worker 退出后
   外部确认恢复 ORIGINAL、没有 namespace/FD/mount 引用，再删除这个精确文件。
3. stock 执行最初返回 `Text file busy (os error 26)`：复制快照的 O_RDWR FD 尚未关闭。新增 Linux
   fexecve 回归，先重现 ETXTBSY，再通过 `/proc/self/fd/N` 打开同 inode 的只读 FD、验证 inode
   一致、关闭全部写句柄。没有从 app 可替换的路径做关闭后重开。NDK/回归测试通过后重试。

### stock 执行测试的实际范围和结果

- 新增独立 STOCK_EXEC Binder 能力，完整 UID 先验、固定 profile/nonce、固定两个系统文件
  SHA-256；没有任意 source/path/PID/命令。source 通过 stdin 只读 FD 交给 native，并核对
  dev/inode、长度、root owner、只读文件系统；副本逐字节校验，root-owned 0555，关闭写 FD。
- 仅在该 `:venus_vm` 的独占 namespace 遮蔽 `/apex/com.android.virt/bin/crosvm`；stock virtmgr
  和原 crosvm inode/标签不改。把原 `crosvm_exec:s0` 标签复制到**临时副本**并读回确认，
  不新增 SELinux 规则、不重标记原文件。readonly/nodev/nosuid 是当前临时 mount 的标志。
- 普通 UID 10278 在覆盖握手后才首次初始化 AVF。固定 VM 名 `viruaisor-stock-overlay-probe`，
  1 vCPU/256 MiB，无磁盘、GPU、initrd、显示和共享目录；仅只读使用已导入的 kernel。
- 13:14:55 / CID 2062：worker PID 13265，namespace 4026537002；源 dev=1896、ino=831872，
  副本 dev=65100、ino=144576。root 外部原文件仍为原 dev/inode/hash，普通主进程对照不变。
- virtmgr PID 13380 在 exec 时报 `Permission denied (os error 13)`；未进入 crosvm 执行或 guest
  启动。正常 app 删除临时 VM 定义，native ROLLBACK=PASS，virtmgr/helper/worker 均退出。
  `SESSION_FILE_RETAINED` 是 app 早于 native 完成清理时的观察；最终 lab 目录已空。
- 13:08 日志中的 `viruaisor-venus-backend-probe` / CID 2060 / GPU panic 属于主界面的另一项
  Venus 后端探测，不是此次 GPU-off stock 测试。不得把不同 VM 的结果混在一起。
- stock 20 秒 lease 的兜底只可用 pidfd 撤销该固定实验 worker 和已核验 namespace/父子链/
  inode/UID/start-time 的本租约后代；普通启动/停止/删除仍由 app 的 AVF 完成。此 VM 后代
  兜底分支尚未实机验证，不能用哨兵死亡演练替代它的验证。

### EACCES 的只读调查与停止点

日志中没有捕获到与该副本直接对应的 execute/entrypoint AVC，不能据此断言必须改 SELinux。
已确认一个强假设：我们额外设置了 MS_NOSUID，而原 APEX mount 为 `ro,nodev,noatime`，
没有 nosuid。Linux 文档和上游 SELinux 的 `check_nnp_nosuid()` 明确指出，nosuid 也影响域转换，
不是只影响 SUID 位。设备该 policy capability 为 1；当前 plat CIL 没有显式 nosuid_transition
allow，但这个局部文本搜索不足以证明完整生效策略的全部权限。

**这仍是待 A/B 证实的假设，不是已经证明的唯一原因。** 当前代码与已安装 APK 仍保留
stock mount 的 nosuid；没有自动去掉该安全相关标志，也未添加规则或换部署路线。
建议下一步只对这一固定已校验副本采用与原文件相容的域转换挂载语义，保持 RO/nodev、
0555、无 SUID/SGID/file capabilities 和普通 app UID；先等用户确认。
详见 [单文件 mount 标志对照提案](superpowers/specs/2026-10-15-stock-overlay-mount-flags-proposal.md)。
依据：[mount(2) 的 MS_NOSUID 说明][mount-nosuid]、[上游 SELinux 检查代码][selinux-nosuid]。
上游 6.6 源码用于解释机制，不是设备 6.18.21 Android/OEM 内核的精确源码。

### 交付和最终复查

- 已安装普通非 debuggable Release 主 APK，同开发签名，未安装独立测试 app；保留 UID、数据和
  两项已有 AVF 授权。当前产物 `.pi/device-debug/venus-overlay/Viruaisor-overlay-stock-sealed-release.apk`。
- APK SHA-256：`df77d38517822d6ba1fb0ebf55cb694d5d1ffe51a7ae03af7224b371ffa2dcbd`。
  overlay native SHA-256：`bfb00b26a4c0933b564f8a94147899512c2491c241709c893db300967197d1c1`。
- 72 项 Android 单元测试、Debug/Release 构建通过；native 策略/复制/真实执行回归通过，
  编译开启 -Wall/-Wextra/-Werror，策略/复制测试含 ASan/UBSan。APK 确认含 Binder shim 和两类 worker。
- 复查 Enforcing，原 crosvm/virtmgr SHA-256 不变、crosvm dev/inode 不变；lab 目录为空，无实验
  namespace/namespace FD/mount、root helper、virtmgr/crosvm 或临时 VM 定义残留。保留正常 app。
- 背景限制仍在：设备自动息屏后会再次 Greezer Denial。helper-death 和 stock 重试使用正常
  `adb install -r` 后的可运行窗口，没有改变后台/省电/解锁/权限策略；这不证明长期锁屏可靠。
- 记录：`sentinel-faults.log`、`stock-execution.log`、`stock-execution-sealed.log`、
  `stock-exec-denial.txt` 均位于忽略的 `.pi/device-debug/venus-overlay/`。

## 上午阶段结论（历史）

**私有 namespace 的哨兵覆盖和正常回滚已在普通 Release APK 实机通过；异常回滚演练
被小米后台冻结机制拦截，尚未通过。没有进行 crosvm/APEX 路径覆盖或启动 VM。**

后续恢复时间为 2026-10-15 上午，以下时间均为设备日志的 UTC+8，与 Asia/Singapore 一致。
旧的离线结果作为历史记录保留在后文。

- 普通 `adb install -r` 成功，无需解锁或安装确认。安装前拉取现有 APK 验证签名一致，
  保留 app UID 10278、数据和已有 AVF 授权；未安装独立 test APK。
- 第一轮于 10:15:18 在挂载前拒绝：libsu root helper 继承 worker namespace，
  `target_ns=helper_ns=4026536996 members=3`。没有放宽独占判定。
- 修正固定 bootstrap 为 `su -c 'exec /system/bin/unshare -m /system/bin/sh'`：
  **在启动 libsu Java 进程之前**创建 helper 自己的临时 namespace，随后 native child
  才进入 worker 既有 namespace。没有 mount-master、进入全局 namespace 做覆盖或策略变更。
  shell 仅用于既有 libsu bootstrap，不新增 shell RPC。相关命令/路径白名单测试先失败后通过。
- 10:18:47～10:18:48 正常实机成功：worker PID 4598 / UID 10278，
  `target_ns=4026536999 helper_ns=4026537000 members=1`；worker 读到 OVERLAY，
  主 app PID 4733 / UID 10278 与 root 对照均读到 ORIGINAL；native `exit=0`、
  `ROLLBACK=PASS`，app 再读到 ORIGINAL，`OVERLAY_PROBE_VERIFIED=true`，root helper 已退出。
  后续 `/proc/*/task/*/ns/mnt` 和 namespace FD 元数据扫描未见该目标 namespace 引用。
- 10:19:46 后台 timeout 演练未完成握手：ActivityManager 的广播记录明确显示
  `com.topjohnwu.superuser.RECEIVER_BROADCAST`、调用者 root PID 4908、接收 worker PID 4843，
  `SKIPPED ... reason: Greezer Denial`。root server 自行退出，worker 后续记录绑定超时并退出。
  没有进入 mount；不能把这次绑定超时算作 8 秒 native 租约回滚通过。第二次后台重试
  同样绑定超时，worker 于 10:22:54 记录失败后退出；已请求用户保持普通主 app 前台，
  不修改后台/省电/权限设置。两次未进入挂载的重试文件均自动清理，lab 目录已空。
- 设备处于 `mWakefulness=Asleep`。Java/native 计时和进程执行可能受 suspend/OEM 冻结影响；
  当前的 8/15/45 秒阈值不能宣称是设备睡眠/冻结时的严格墙钟撤销保证。这是后续部署门禁，
  正常一次成功不替代死亡/冻结/超时演练。
- 重连后的基线与多次复查：SELinux Enforcing，原 crosvm 和 virtmgr 哈希与旧基线一致；
  没有实验 VM、virtmgr 或 crosvm 进程。没有替换系统文件或 AVF APEX。

已安装的正常 Release APK：`.pi/device-debug/venus-overlay/Viruaisor-overlay-bootstrap-release.apk`，
SHA-256 `0c3c9f26a47b639135f706619b050b08e3a4d342fa32b9a8877a2e3d044de7bf`。
同开发签名证书保持不变；Android 单元测试现为 68 项，全量测试、Debug/Release 构建通过。
记录：`.pi/device-debug/venus-overlay/sentinel-logcat.log`、`greezer-broadcasts.txt`。

实现依据：[libsu 6.0.0 Shell.Builder 自定义 argv][libsu-shell]、
[libsu RootServiceManager 后台 app_process 启动和 Binder 广播][libsu-service]。
这里的源码依据只用于解释 bootstrap，不代表 OEM 后台调度保证。

## 初次离线阶段结论（历史）

**离线哨兵实现与构建通过；设备 ADB 断线，未部署，未取得实机隔离或 GPU 成功结果。**
本轮没有安装任何 APK/测试 app、执行 setns/mount、启动 VM 或操作 guest 磁盘。
不能把本地测试通过、历史宿主 GPU 验证通过当作私有 crosvm/Venus 已打通。

已有无线连接在本机 ADB 日志中于 `10-15 02:38:43.440` 报 SSL_read/transport 断线。
随后 `adb devices -l` 无设备，`adb mdns services` 无服务；重启本机 ADB server 后仍无设备。
已知历史地址 `192.168.1.107` 单次 ping 超时（它本身不能证明设备关机或 IP 未变化）。
没有扫描网络、重新配对、唤醒屏幕、解锁或绕过安装确认。

## 已完成的初次离线改动（历史）

- 同一主 APK 增加不导出的普通 UID `:venus_vm` 前台哨兵 service；默认不启动，不访问 AVF。
- 同 APK 主进程增加不导出的对照 receiver，只读固定哨兵路径，写固定会话观察结果。
- 非 daemon libsu root service 只有一项哨兵能力：先验证完整 Binder UID，PID 来自 Binder，
  输入仅限 32 位小写十六进制 nonce 和四种固定演练模式，不接受任意 shell、路径或 PID。
- Native helper 验证普通 app UID/cmdline/CapEff、Zygote 父进程、pidfd、启动时间、namespace
  inode 与独占成员；无法证明则拒绝。在目标已有 namespace 内建立私有传播，**不 unshare**。
- 唯一挂载目标是本 app 随机会话文本哨兵；使用 root-owned 0444 快照和只读 bind。
  只为该临时快照复制哨兵现有 SELinux 标签；不重标记系统文件、不注入策略。
- Native 租约 8 秒、root 子进程等待上限 15 秒、独立 app 看护 45 秒；正常路径明确 unmount
  并检查原始内容。`timeout`、`client-death`、`helper-death` 模式已实现，**尚未在设备演练**。
  后两种模式须额外从外部审计 namespace/进程引用归零，不能仅凭 app 退出声称完整回滚。
- 本实现没有 crosvm 挂载能力。它只为下一门禁提供观测，不能直接用于系统二进制覆盖。

## 初次离线本地验证（历史）

- 先观察新增 native/Kotlin 安全策略测试失败，再实现并验证通过。
- `:app:testDebugUnitTest :app:assembleDebug` 通过，Android 单元测试共 66 项。
- `:app:assembleRelease` 通过；普通 Release 构建（非 instrumentation APK、非 debuggable）。
- Native 策略测试以 GCC `-Wall -Wextra -Werror`、ASan/UBSan 通过；NDK r30 arm64/API37
  helper 编译通过；Linux 版本错误身份/非法 nonce 拒绝路径通过，未进入挂载代码。
- ELF 为 PIE，使用 `/system/bin/linker64`，LOAD 对齐 0x4000，仅依赖 libc/libdl。
- `scripts/assert-venus-backend.test.cjs` 三项回归通过，`git diff --check` 通过。
- APK 确认包含 `libviruaisor_native.so`、宿主 renderer worker 和新的 overlay helper。

产物：`.pi/device-debug/venus-overlay/Viruaisor-overlay-release.apk`。
SHA-256：`c3703e5a2ea60d8c5f10ccce3e85928cbaed05e848b07a709608af232a01e075`。
签名证书 SHA-256：`82c1caf021cab5f539ed3db1c9ce1e53d56e1866deed357670bb2b598e647634`，
与此前安装主 app 相同的开发证书，不是新增发行证书或商店签名。

## 初次离线时的重连计划（历史）

1. 重查设备身份、SELinux Enforcing、原 crosvm/virtmgr 哈希、现有 VM/进程及 APK 签名。
   只用正常 `adb install -r` 保留主 app 数据；若需要确认，等待用户，不用 root 安装绕过。
2. 用 root ADB 启动不导出的 app service（service 本身仍是普通 UID）：

   ```sh
   adb shell su -c 'am start-foreground-service -n pro.darc.viruaisor/.gpu.VenusOverlayProbeService -a pro.darc.viruaisor.action.OVERLAY_SENTINEL_PROBE --es mode normal'
   ```

   查看 `ViruaisorOverlay`/`ViruaisorOverlayRoot` 日志及 app 的
   `files/venus-overlay-last.log`。必须同时看到普通 worker 的 OVERLAY、主 app 和 root 对照的
   ORIGINAL、native 退出 0、ROLLBACK=PASS、恢复 ORIGINAL 和 worker/helper 退出。
3. 单独演练超时/客户端死亡/helper 死亡，审计所有 namespace FD、进程和 mount 引用。
   只清理报告中确切 nonce 对应的 `.target/.control/.outside/.snapshot`，不递归删除 app 数据。
   helper 死亡时 target 可能因 EBUSY 留到 namespace 释放后，须外部复核并清理。
4. 重查其他 namespace、原始二进制哈希、Enforcing、没有遗留 VM/helper/test app。
   隔离、回滚、可见性或任何安全门禁失败即停止，不自动扩大权限。
5. 全部通过后，才另加经审查的字节不变 stock crosvm 副本执行门禁；再通过后才构建/接入
   Venus 候选。当前没有启动大型 AOSP 构建或准备全局覆盖。

构建侧缺口和固定源码见 [crosvm/Venus 构建研究](android-crosvm-venus-build-feasibility.md)。
后续仍需真实 renderer 分配/import、embedded server 选择、失败禁止 2D 回退、guest blob
映射/缓存/fence/正确 GPU 读回，不能只启用一个编译开关。

初次离线阶段尚无设备侧变更可回滚；重连后的安装与测试以本文开头更新为准。

[libsu-shell]: https://github.com/topjohnwu/libsu/blob/6.0.0/core/src/main/java/com/topjohnwu/superuser/Shell.java
[libsu-service]: https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java
[mount-nosuid]: https://man7.org/linux/man-pages/man2/mount.2.html
[selinux-nosuid]: https://github.com/torvalds/linux/blob/v6.6/security/selinux/hooks.c#L2118
