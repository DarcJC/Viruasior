# stock crosvm 单文件 mount 标志对照（已确认并实测）

2026-10-15，Asia/Singapore。父方案的禁止系统/APEX 原文件更改、全局 overlay、刷写、
策略/权限/系统设置修改等边界全部保留。

用户随后回复“继续”，已授权下述单变量对照。13:26～13:29 结果见文末；原提案保留以便
对照范围。未扩大到候选二进制、策略/权限变更或不同的 VM 配置。

## 观察与假设

普通 Release app 的私有哨兵/异常回滚已通过。逐字节相同的 stock crosvm 副本在 worker
namespace 中可见，但实际 AVF exec 返回 EACCES；已经完整撤销，没有启动 guest。

原 APEX mount 为 ro,nodev,noatime，没有 nosuid；我们为临时文件 mount 额外添加了 nosuid。
该标志也限制 SELinux 域转换，可能阻止正常的 virtmgr→crosvm 转换。已查上游机制和本机
policy capability；**尚未通过 A/B 实测证明是唯一原因**，也不能归结为 Venus 编译失败。

## 两个可选动作

1. **建议：单变量对照。** 只对已固定摘要的 stock-copy mount 去掉额外的 nosuid，恢复与
   原 APEX 相容的域转换语义。保持 readonly/nodev、root-owned 0555、无 SUID/SGID 位、无 file
   capabilities；普通 app UID、预期 AVF SELinux 域、不可变 FD 和完整鉴权保持不变。
2. **保持停止。** 当前实现/设备无残留，保留记录，不继续 executable overlay。

不考虑添加 nosuid_transition allow、共享 app 域规则、关闭 SELinux 或全局修改 mount。

## 精确变更范围

| 固定 profile | 当前临时文件 mount | 建议对照 |
| --- | --- | --- |
| 无害 sentinel | RO + NODEV + NOSUID | 不变 |
| 固定 stock crosvm 字节副本 | RO + NODEV + NOSUID | RO + NODEV；仅此文件、仅此 worker namespace |

原 crosvm、virtmgr、APEX mount、标签、包和签名不改。临时副本只复制原 SELinux 标签；
继续校验 crosvm/virtmgr 原摘要、source FD 的 inode/只读 FS、逐字节副本、所有写 FD 已关闭。
执行前额外核查 mode 不含 SUID/SGID、security.capability 不存在，读取实际 mount flags 佐证。
不能将这一对照推广到任意二进制、任意 label 或目录。

## 验证与停止条件

先写 mount-profile 安全测试并观察失败，再实现单变量变更、构建同签名 Release APK。
仍仅使用现有固定 diskless/GPU-off VM：1 vCPU/256 MiB、只读 kernel、无 initrd/磁盘/显示。

成功必须看到普通 UID 的 AVF 执行的是临时副本 dev/inode，而非外部原 inode；父子链和
namespace 正确，crosvm 为预期 SELinux 域且 CapEff=0；随后普通 AVF stop/delete、native
正常 unmount、源 inode/hash 恢复、所有实验进程和 namespace/FD/mount 引用释放。
只要执行/身份/回滚未过，就停止并报告，不自动增加权限或改策略。

这一步成功也不证明 Venus 可用：还没有启用/构建 Venus crosvm，也没有 guest GPU 内存或
计算验证。stock VM 后代的异常撤销还需独立验证，不能套用哨兵演练结论。

## 风险与回滚

取消 nosuid 允许既有 SELinux 域转换被正常考虑，是安全相关的挂载语义变化，所以先确认。
风险由单一固定摘要、只读 0555/无特权位/无 capabilities、普通 UID、短租约和不共享 namespace
限制；它不提供对已有恶意 root 的防御。不上系统分区，不安装持久模块。

失败时由普通 app 停 VM、删除独立定义；租约仅可通过已验证 pidfd 撤销自身 worker/后代，
不按名字全局杀进程。撤销该唯一 mount，关闭 FD，复查原文件/其他 namespace/Enforcing。
提案确认前没有代码或设备侧部署；后续执行仅限上述已确认范围，安全边界继续生效。

## 13:26～13:29 实测结果

- 挂载 profile 测试先失败于 stock 仍带 NOSUID，再实现只有 stock 去掉该位；sentinel 不变。
  四组 native 回归和 NDK 编译通过，policy/copy/mount-profile ASan/UBSan 通过；72 项 Android
  单元测试与 Debug/Release 构建通过。同签名非 debuggable 主 APK 正常 `adb install -r`。
- 实际 mount flags=1029，RO/NODEV、非 NOSUID、非 NOEXEC；副本 root:root 0555，
  security.capability 缺失。源摘要、逐字节一致、标签/只读 FD 的原有检查全部保留。
- 两次 AVF 均越过原 EACCES，并进入 KVM 初始化。第二次外部只读观察器直接记录：
  worker 15225 → 原 virtmgr 15350 → crosvm 15379，namespace 都是 4026537002；
  crosvm `/proc/15379/exe` dev=65100、ino=141375，与该次副本完全一致，不是原 inode831872；
  crosvm 四个 UID 均10278、CapEff=0，域为 `untrusted_app_all_virtmgr_crosvm:s0:c22,c257,c512,c768`。
  **已证明 AVF 实际执行私有副本，但完整自动门禁仍未通过。**
- 原 virtmgr 实测 UID10278、CapEff=0x800000；native 观察器要求所有后代 CapEff=0，因而
  排除了 virtmgr，连带未接受其 crosvm 子进程。没有修改这个身份检查，也没有授予新能力。
  此位的 stock 基线/来源需独立核实，不能直接把检查改成接受任意非零 capability。
- 固定 diskless VM 在约0.3～0.7秒后发生 guest reset（crosvm exit32），app 等待后 status=0。
  尚未采集 guest console 证明 reset 根因；无磁盘/initrd和 `panic=-1` 是调查线索而非定论。
  没有改 panic 参数、添加 initrd/磁盘，或把短暂执行写成稳定 guest 启动成功。
- 两次错误路径仍显式 ROLLBACK=PASS，普通 app 删除独立 VM 定义；13:28:55 外部审计
  无该 namespace 的线程/FD 引用、无实验 mount、worker/helper/virtmgr/crosvm，lab 为空。
  原两个系统文件 hash、crosvm dev/inode 不变，Enforcing；只有正常主 app，无独立测试 app。

当前安装产物为 `.pi/device-debug/venus-overlay/Viruaisor-overlay-stock-mountflags-release.apk`，
SHA-256 `a159b85484fbe989adaeaa08d47989bb394a8321d84e069c54a624423994fa48`。
实验已按停止条件结束：先审查 stock virtmgr capability 和最小 VM reset/观测收尾，
再决定下一轮；没有构建或部署 Venus-enabled crosvm，不宣称 guest GPU 加速完成。
