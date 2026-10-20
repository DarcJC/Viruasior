# 双区工作台验证记录

日期：2026-10-20。设备：M367FC / Android 17（API 37）。

## 实现

- 主导航为工作台与设置。平板侧栏、紧凑窗口底部导航；桌面预览与控制／存储／显示管理面板按宽度并排或上下排列。
- 冷灰与钴蓝深浅主题，支持持久化跟随系统／浅色／深色选择。
- 必要 Root／AVF 配置保留在设置；Root 技术详情、显示桥接、Venus 验证和权限回退集中到独立诊断中心。
- 现有启动、停止、导入、VPN 共享、密码、根盘容量／恢复和显示模式均连接原有服务。关闭、替换、恢复和撤销授权保留明确确认。
- `MainActivity` 保留窗口、生命周期、输入和服务连接；应用壳、管理面板、显示宿主、公共组件和对话框拆为独立文件。

## 自动验证

运行 Android Studio JBR 25 下的 Gradle Wrapper：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

结果：构建成功。46 个单元测试套件共 198 项，197 通过、0 失败、1 跳过。跳过项为原有 `RootfsExt4IntegrationTest`，它要求显式设置 `ROOTFS_INTEGRATION=1` 才执行临时 ext4 镜像集成测试；本次未启用。

`scripts/verify-native-packaging.ps1` 通过：APK 中包含 11 个 arm64 ELF 工件，桌面固定版本、ext4 工具字节与许可证检查通过。`git diff --check` 通过。

新增操作策略测试覆盖诊断／权限互斥、恢复状态和容量校验。`WorkbenchUiTest` 在平板上运行 7 项界面测试，全部通过（20.517 秒）。测试通过公开无障碍操作验证界面状态与回调，另对实际应用执行独立的 ADB 坐标触摸回归。测试覆盖：

1. 日常界面与诊断分离、设置往返导航。
2. 关闭确认、取消不调用关闭动作。
3. 390 dp 窄窗口底部导航、存储容量错误反馈和显示页签。
4. 验证忙碌时禁止启动与替换。
5. 全屏往返保持原生 Surface 连接数。
6. 全屏控件自动收起并可触摸唤回。
7. 设置往返保持原生 Surface 连接数及导航状态。

设备系统限制了 instrumentation 从应用 UID 在后台启动测试 Activity。测试使用 `UiAutomation` 的 shell 启动已有 debug 测试宿主，并通过公开无障碍树查找控件。测试宿主中的坐标注入没有可靠触发控件，因而界面测试保留无障碍点击，并对实际 `MainActivity` 单独执行 ADB 触摸操作与截图检查。全屏测试明确等待全屏状态实际变化后再检查 Surface，避免仅凭连接计数产生假阳性。没有修改设备后台启动策略或隐藏 API 策略。测试窗口仅在前台保持亮屏。

## 黑屏与全屏控件回归

重现了切换布局时重新创建 Surface 消费队列的问题：连接次数从 1 增加到 2，静止客体的最后一帧丢失，形成长时间黑屏。工作台／全屏改为同一个原生视图与稳定父级；设置页面显示时保留工作台并移至裁剪范围外，同时隐藏其无障碍子树。回归测试先重现失败，再验证连接数保持不变。

真实应用触摸检查还发现移出一倍内容宽度不足以避开侧栏，隐藏管理面板的一角会拦截导航。保留容器增加裁剪，并将工作台完整移至屏幕外；独立的 ADB 触摸回归覆盖进入设置和返回工作台。

固定全屏 header 已移除。悬浮控件约 2.5 秒后收起为顶部小手柄；点击重新打开退出按钮，保留 `Ctrl+Shift+Alt+Q`。控件使用独立 Popup 窗口，位于 on-top Surface 上方，显示／收起不改变桌面视口。

## 实机验证边界

Root 获得、Android 授权、AVF 可用、VM 启动、Surface 连接与实际客体画面分别检查；连接成功不作为画面可用的唯一证据。SELinux 保持 Enforcing，未新增授权、设备级策略或 Root 能力。

不为界面验证替换系统包、修改密码、扩缩根盘、恢复磁盘或撤销权限。相关危险流程检查入口、校验和取消，底层沿用已有实现。截图、运行日志和测试输出保存在忽略的 `.pi/device-debug/ui-workbench/`，不提交设备图像。

用户再次解锁后，已完成以下真实应用检查：

| 里程碑 | 结果 |
| --- | --- |
| Root | 显示 helper 报告 UID 0，运行于 KernelSU context |
| Android 权限 | `MANAGE_VIRTUAL_MACHINE` 与 `USE_CUSTOM_VIRTUAL_MACHINE` 均 `granted=true` |
| AVF | 应用显示 `AVF_AVAILABLE` |
| VM 启动 | 客体 agent 就绪，`crosvm_default` PID 15017 |
| Surface | Root-owned BLAST Surface 已连接 |
| 客体实际画面 | `DISPLAY_PRESENTED` 报告 3408×2272 后的 1920×1280 画面，截图可见 Manjaro Plasma 桌面 |
| 全屏 | 进入后立即截图已有桌面；约 2.5 秒收起为小手柄；点击唤回、触摸退出和快捷键退出均通过 |
| 设置导航与主题 | 深色／浅色／跟随系统切换，诊断中心返回工作台，桌面画面均保留；侧栏无遮挡；外观最终恢复跟随系统 |
| 生命周期稳定 | 全屏及设置往返的 VM PID 均保持 15017，累计 Surface attach 数均保持 3，无新增连接 |
| 显示模式 | 经界面选择完成 1920×1280 @ 120 Hz → 1920×1200 @ 120 Hz → 1920×1280 @ 120 Hz；日志分别确认新画面的 1920×1200 和 1920×1280 尺寸 |
| 对话框 | 实机打开并取消根盘容量、账户密码对话框；未提交磁盘或密码改动 |

对应截图为 `25`–`34`，连接与 PID 对比见 `live-transitions.txt`，授权与呈现日志见 `permission-check-final.txt`、`selinux-final.txt` 和 `guest-display-final.txt`。

容量、模式与密码截图为 `35`–`39`，最终工作台为 `40-workbench-final.png`。显示模式切换记录见 `live-mode-change.txt`。没有执行根盘调整／恢复、系统包替换、密码更改或授权撤销。

## 结束与关闭

通过工作台「关闭虚拟机」及确认按钮正常停机，等待 `crosvm_default` 退出。随后 `scripts/device-smoke.sh 'VM stopped' --package pro.darc.viruaisor` 通过：AVF 可用，状态为停止。

按用户要求 force-stop 应用及测试包。Root 显示 helper 异步退出后再次核查，应用进程、VM、Root helper 和 Venus worker 均已关闭。结果见 `shutdown-final.txt`、`smoke-final.txt`、`closed-processes.txt`。没有保留自动启动 VM 的后台任务。设备临时截图和 UI XML 已清理，本地忽略目录中的验证证据保留。
