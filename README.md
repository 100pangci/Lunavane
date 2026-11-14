# Lunavane

Lunavane 是一个面向 Android 的 ZeroTier 客户端，使用官方 ZeroTier One Core 1.16.2、Android `VpnService`、Kotlin、Jetpack Compose 和 JNI/C++。它会把 Core 下发的网络配置应用到系统 TUN，并在应用数据路径中转发虚拟网络数据包。

项目仍在开发中，目前提供可构建的 Debug 版本。维护者已在一台 Android 真机上验证加入 ZeroTier 网络，并通过受管 LAN 路由访问局域网 NAS。设备、Android 版本、ZeroTier 网络配置和底层 UDP 连通性会影响实际结果；这项验证不代表所有环境均受支持。

> Lunavane 是独立项目，与 ZeroTier, Inc. 无隶属、赞助或官方支持关系。ZeroTier 名称及相关商标归其各自所有者所有。

## 功能

- 使用官方 ZeroTier Core 加入网络并接收控制器下发的网络配置。
- 通过 Android `VpnService` 建立 TUN，应用虚拟地址、受管路由、DNS 和 MTU。
- 在 TUN 与 ZeroTier Core 之间转发 IPv4/IPv6 包；应用侧处理 IPv4 ARP 和 IPv6 NDP 邻居发现，并缓存邻居解析期间的待发包。
- 通过 Android `ConnectivityManager` 观察所有非 VPN 的 Wi-Fi、蜂窝和以太网候选，先比较 validated 和 Internet 状态，再按链路类型选择；跨类型的同等候选保留当前网络，同类型的同等候选优先较新出现的网络，以支持双卡运营商切换。检测到所选底层网络变化或当前承载网络丢失时，会重建 ZeroTier Core 与 VPN TUN 会话；UDP socket 绑定失败时依次尝试其他候选，并按 1/2/4/8/16/30 秒有限退避恢复。
- 每个隧道会话使用 session generation 和 Core handle 校验回调与异步任务；旧会话的数据包、配置和回调不会写入新会话。
- 对发往 Core 和写往 TUN 的数据包执行数量与字节数上限，过载时丢弃新数据包而不是无限排队。
- 支持 Moon Orbit/Deorbit 请求；Moon world ID 和 seed 节点地址保存在应用本地。界面分别显示 Orbit 请求是否被接受、seed 节点是否在 Peer 列表中可见，以及“Moon 定义状态待确认”，不会仅凭 Peer 角色或可见性宣称 Moon 定义已验证。
- 定时通过 Native worker 只读查询 Core Peer 列表，显示 Peer 地址、角色、版本、延迟和路径；查询在 Native worker 上串行执行，并释放 Core 返回的查询结果。
- 提供 Material 3 / Material You 风格的四页界面：**首页**、**网络**、**Moon** 和**诊断**。首页以大号电源按钮执行连接或断开，仅显示网络名、虚拟地址、Peer 数量、底层网络和恢复阶段等摘要；网络页负责网络 ID 输入、网络配置和 Peer 详情；Moon 页负责 Orbit/Deorbit；诊断页显示完整状态和事件日志。设置入口位于首页右上角。
- 连接状态由节点、虚拟网络、恢复阶段和错误状态共同推导，区分未连接、连接中、等待授权、已连接、切换网络、恢复中、恢复失败和连接错误；仅在虚拟网络已授权且恢复阶段就绪时显示“已连接”，不会只凭 VPN 隧道存在就宣称连接成功。
- 支持跟随系统、浅色和深色外观，以及 Android 12 及以上的动态取色；可关闭动态取色回退到内置品牌配色。界面提供英文和简体中文，界面文案均由 Android 字符串资源提供（LEAF/MOON/PLANET 等协议角色名和原始诊断数据除外）。
- 诊断页保留进程内事件日志，支持文本选择与复制、滚动查看和清空；缓冲区最多保留 100 条。设置页提供外观、动态取色、版本、项目说明、GitHub 仓库和第三方开源许可入口。
- 提供自适应 Android 启动器图标。

## 运行要求

- Android 8.0（API 26）或更新版本。
- 有效的 16 位十六进制 ZeroTier 网络 ID，以及加入该网络的授权。
- 若要访问远端 LAN，网络还需要配置相应的受管路由和网关转发。

应用显示名称为 **Lunavane**。安装包名：Debug 为 `nova.lunavane.debug`，Release 为 `nova.lunavane`，源码命名空间与安装包名一致，为 `nova.lunavane`。`versionName` 取最近的 `vX.Y.Z` 格式 Git 标签（去掉前缀 `v`，例如 `v1.2.3` → `1.2.3`）；没有可用标签时为 `0.0.0`。`versionCode` 取当前 Git 提交数：第一条提交为 `1`，之后每提交一次递增 `1`。发布版本请先打 `vX.Y.Z` 标签再构建。

> 从旧包 `dev.nova.zerotier` 更换到新包名时，Android 会将新包视为独立应用，旧包的节点身份、网络 ID、Moon 记录和外观偏好不会自动迁移。新包首次启动会生成新的节点身份，需要在 ZeroTier 控制台重新授权；如果之前已安装同包名的 `nova.lunavane.debug`，则属于同 ID 覆盖升级，数据会保留。Debug 包 `nova.lunavane.debug` 与 Release 包 `nova.lunavane` 也各自拥有独立的节点身份，请授权当前运行的那个节点。

## 构建和安装

### 工具链

- JDK 17。
- Android SDK Platform 36；最低支持 Android 8.0 / API 26，目标 SDK 为 API 35。
- Android Gradle Plugin `8.13.2` 和 Kotlin 插件 `2.2.20`。
- Android NDK `28.0.13004108`。
- CMake `3.22.1`。
- 仓库附带 Gradle Wrapper `8.13`。

首次构建需要联网下载 Gradle，以及 Google Maven、Maven Central 和 Gradle Plugin Portal 上的插件与依赖，并在 Android SDK 中接受所需许可。请在 Android Studio 中安装上述 SDK/NDK/CMake 组件，或通过本机 SDK Manager 安装。`local.properties` 是本机配置文件，不应提交到仓库。

### 获取源码

```sh
git clone https://github.com/100pangci/Lunavane.git
cd Lunavane
```

### 构建 Debug APK

在仓库根目录运行：

```sh
./gradlew --no-daemon :app:assembleDebug
```

生成文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接并开启 USB 调试的设备：

```sh
./gradlew --no-daemon :app:installDebug
```

Debug APK 包含 `arm64-v8a`、`armeabi-v7a` 和 `x86_64` ABI。当前仓库不提供签名 Release APK；Debug 构建用于开发和设备验证。

### 运行与连接

1. 安装并打开 Lunavane。首次使用时，首页会显示添加网络入口。
2. 在 **网络** 页输入 16 位十六进制 Network ID，可填写自定义名称，然后点 **添加网络** 保存。保存不会自动建立 VPN；Network ID 会统一转为小写，重复 ID 会被拒绝。
3. 网络卡片会分别标出当前选择和实际活动连接。可从卡片菜单编辑名称、调整顺序或删除；删除活动网络时，应用会先停止会话并等待 Core、TUN 和 UDP socket 清理完成，再删除本地记录。节点身份和 Moon 设置会保留。
4. 选中网络后，点击首页电源按钮连接。首次连接时，Android 13 及以上会请求通知权限，随后显示 Android 系统 VPN 授权界面。拒绝通知权限不会阻止连接；拒绝 VPN 授权时首页会提示重试。
5. 若需要控制器审批，请在 ZeroTier 网络控制器中授权节点。未收到配置时首页会显示“等待授权”。收到有效配置后，首页显示网络名、虚拟地址、Peer 数量、底层网络和恢复状态；**网络** 页显示分配的 IPv4/IPv6、实际应用路由、DNS 和 Peer 列表。
6. 点击首页网络名称可打开快速选择面板。未连接时选择另一网络只更改当前选择；已连接时选择其他网络会明确提供 **切换并连接**。切换会先完整停止旧会话，再加入目标网络；失败时显示错误，用户可重试或选择其他网络。
7. 点击首页电源按钮可断开当前连接；网络卡片也提供断开操作。

应用同一时间只维护一个活动 ZeroTier 网络。ZeroTier 节点身份保存在 Core 状态目录中，因此切换网络不会更换 Node ID。应用重新打开后会恢复网络列表和上次选择，但不会自动启动 VPN。升级时，旧版偏好设置中的单个 Network ID 会以幂等方式迁移为已保存配置；旧偏好设置项和 Identity、Moon、主题等数据不会被迁移过程删除。

VPN 授权通过 Android 系统界面完成。隧道由 Android 前台服务运行；应用会等待 Core 返回真实网络配置和授权状态后再建立 TUN。首页不会仅凭服务运行状态显示“已连接”。

### 配置 Moon

Moon 配置可以离线预先保存。在 **Moon** 页输入 16 位十六进制 Moon world ID 和 10 位十六进制 seed 节点地址：节点未就绪时选择 **稍后提交**，配置会保存在本机并显示等待提交；节点就绪时选择 **保存并 Orbit**，应用会向 Core 提交请求并记录 Core 返回结果。同一个 Moon 可保存多个不同 Seed，重复条目会去重；节点启动时会重放已保存的 Orbit 请求。

删除时可分别选择 **向 Core Deorbit** 或 **仅删除本地设置**。Core Deorbit 会在确认 Core Moon 文件已移除后再更新本地记录；失败或状态未知时保留本地记录。仅删除本地设置不会更改运行中的 Core。

Moon 卡片可展开查看各 Seed 的 Peer 可见性和连接路径。Orbit 请求是否被 Core 接受、Seed Peer 是否可见、Moon 定义是否加载是三个独立状态；当前 Core API 无法确认定义是否加载，界面会继续显示“状态待确认”。

页面把以下事实分开显示，避免把“请求已提交”误认为“Moon 已验证”：

- **Orbit 请求**：Core 是否接受 `orbit` 调用，以及本机保存是否成功。
- **Seed Peer 可见性**：定时 Peer 查询中能否看到该 seed 地址，以及其角色、延迟和路径。
- **Moon 定义状态**：当前始终显示为“状态待确认”。Core 的 Peer 列表不包含所配置 Moon 的 world ID 或定义加载确认，因此即使 seed 可见或角色显示为 `MOON`，也不能确认指定 Moon 定义已经加载。

Deorbit 后应用会检查 Core 状态目录中的 Moon 文件是否已移除；对于未能加载的损坏文件，Core 桥接也会在 Deorbit 请求成功后尝试清理该文件。若文件仍存在或文件状态无法确认，应用会保留本地记录并提示重试；只有确认文件不存在后才删除本地保存。

## 路由和数据路径

```text
Android 应用流量
        │
        ▼
Android VPN TUN ── IPv4/IPv6 包 ── Lunavane ── ZeroTier Core
                                                │
                                                ▼
                                       受保护的 UDP socket
                                                │
                                                ▼
                                         Android 底层网络
```

- Core 提供虚拟地址、路由、DNS、MTU 和组播配置；应用将适用配置安装到 Android VPN Builder。
- TUN 出站包由服务转换为 ZeroTier 虚拟帧。邻居解析由应用处理；解析期间的 IPv4/IPv6 包进入有界队列。
- Core 回调负责 UDP 数据报、虚拟帧和网络配置。Native worker 按 Core 返回的 deadline 运行后台任务；UDP 收发使用非阻塞 `DatagramChannel`，发送结果会返回给 Core，不会在 Native worker 上执行阻塞式 socket 发送。
- 网络配置回调在独立串行 executor 上建立/替换 TUN，并把实际结果同步返回 Core；回调期间 Native worker 会等待，但 `VpnService.Builder.establish()` 与通知 Binder 调用由独立线程执行。节点事件则入队到串行 core executor，避免回调直接执行通知操作。
- 物理网络切换或当前承载网络丢失时，服务会按会话顺序停止并重新创建 Core、TUN 和受保护 UDP socket；节点身份保存在 Core 状态目录中，Network ID 和 Moon 请求从本地偏好设置恢复。
- 停机流程按会话串行化：旧会话的 stop 不会销毁新会话刚建立的 Core handle，TUN、socket 和邻居队列也按会话清理。
- UDP socket 先由 `VpnService.protect()` 排除在 VPN 路由之外，再尝试显式绑定到选中的物理 `Network`；若 Android 以 `IOException` 拒绝网络绑定，服务保留已保护的 socket 并回退到系统默认物理路由，同时在 **诊断** 页记录。若绑定成功后的发送仍返回 “Network is unreachable”（该网络路由尚未可用），服务也会切换到一个未绑定网络、但仍受 `protect()` 保护的 socket，通过默认物理路由继续发送，并在冷却时间后才重新尝试绑定首选网络。Socket 创建、`protect()` 或本地端口绑定失败仍进入有限退避。服务通过 `VpnService.setUnderlyingNetworks()` 同步当前选定的底层网络。
- 每个虚拟帧和 TUN 写入任务都携带会话 generation 与 Core handle；任务在入队前和执行时都会校验，避免旧会话写穿新 TUN 或新 Core。
- 访问远端 LAN 时，ZeroTier 网络需要包含目标 LAN 前缀的受管路由；网关节点还必须能转发流量，并有可用的返回路径。**网络** 页显示当前实际应用的路由，可用于确认 Android 收到了哪些目标前缀。

## 权限与隐私

Manifest 声明的权限：

- `INTERNET`：与 ZeroTier 网络通信。
- `ACCESS_NETWORK_STATE`：观察底层网络变化。
- `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_SPECIAL_USE`：维持用户主动建立的 VPN 前台服务。
- `POST_NOTIFICATIONS`：声明并在连接时请求通知权限（Android 13 及以上）；拒绝不会阻止 VPN 连接，但通知可能不可见。
- Android 系统的 `BIND_VPN_SERVICE` 保护服务，并由用户通过系统 VPN 授权界面启用。

应用不申请存储权限。节点身份和 Core 状态保存在应用私有目录 `files/zerotier`；已保存网络、当前选择和 Moon 请求保存在应用私有偏好设置中。身份私钥不会写入诊断日志。诊断事件仅保留在进程内存中，最多 100 条；清除或进程退出后不再保留。应用不会静默上传配置或诊断日志，也没有内置遥测上传流程。

首页、网络页、诊断页和截图可能显示 ZeroTier 节点地址、虚拟地址及受管路由等信息；诊断日志会记录运行状态、网络状态和部分下一跳 IP。若在 Issue 或公开讨论中分享日志或截图，请遮盖网络 ID、节点地址、局域网/虚拟地址及其他可识别信息。不要分享身份目录或任何私钥。

## 故障排查

- **一直等待网络配置**：确认网络 ID 正确、节点已在控制器中授权，并检查设备是否能访问互联网及 ZeroTier 上游节点。
- **VPN 已启动但没有虚拟地址或路由**：VPN 系统授权本身不会分配网络配置；检查控制器是否已授权节点并为该网络配置地址和路由。
- **能访问虚拟网段但不能访问远端 LAN**：检查 **网络** 页的路由列表是否有目标 LAN 前缀及下一跳，并确认远端网关转发和返回路由有效。
- **网络切换后暂时离线**：首页的“恢复阶段”和 **诊断** 页会显示当前恢复状态。短暂等待退避重试；若显示恢复失败（Recovery exhausted），切换一次物理网络或重新连接后会重新开始候选选择。打开 **诊断** 页查看 UDP socket、Peer 刷新和邻居解析事件。
- **有物理网络但 UDP socket 创建失败**：查看 **诊断** 页中的 socket 错误。如果系统拒绝 `Network.bindSocket()`，或绑定后仍报 “Network is unreachable”，应用会记录原因并使用已由 `VpnService.protect()` 排除在 VPN 路由之外的默认物理路由继续发送；若 socket 创建、VPN protect 或本地端口绑定失败，则继续有限退避重试。
- **需要提交诊断**：在 **诊断** 页选择并复制必要信息，或截取相关区域，并先遮盖前述地址和 ID。应用不会自动导出或上传诊断内容。

## 真机验收建议

以下步骤用于验证本版本的网络恢复、连接状态和界面，尚未由本次改动在真机上执行：

1. 连接设备并安装 Debug APK：

   ```sh
   adb devices -l
   ./gradlew --no-daemon :app:installDebug
   ```

2. 连接一个已授权的 ZeroTier 网络，确认首页显示已连接、虚拟地址和 Peer 数量；**网络** 页显示实际应用的路由和 Peer 列表，点击 Peer 可查看角色、延迟和路径。
3. Wi-Fi 与蜂窝网络交替切换 10 次，每次记录首页的“底层网络”“恢复阶段”和 **诊断** 页中的 UDP socket 事件。若设备支持双 SIM，再把默认移动数据卡在运营商 A、B 之间来回切换 10 次；确认隧道会显示重新连接，`Protected ZeroTier UDP socket bound to ...` 的 Network 标识随运营商改变，恢复阶段回到就绪，节点地址保持不变且网络地址/路由重新应用。
4. 切换到没有 Internet 的本地 Wi-Fi，确认应用仍能选择该物理网络并尝试建立 socket，而不是要求网络必须 validated。
5. 关闭再打开 Wi-Fi，或在系统设置中临时禁用当前网络，确认首页在“等待物理网络”和恢复后的已连接状态之间切换。
6. 断开并重新连接同一网络，确认 **网络** 页的节点地址保持不变（节点身份持久化）。
7. 在 **Moon** 页 Orbit 一个 Moon，确认页面同时显示 Orbit 请求状态、每个 seed 的 Peer 可见性和“Moon 定义状态待确认”。尝试 Deorbit，观察文件移除失败时是否保留本地记录。
8. 在设置中切换跟随系统/浅色/深色，关闭和开启动态取色（Android 12+），确认界面即时更新并在重新打开应用后保留；切换系统语言为简体中文/英文，确认界面文案完整。
9. 让设备休眠数分钟后唤醒，确认服务恢复且 TUN/UDP 不重复建立。

调试 Native 侧事件时可观察：

```sh
adb logcat -s ZeroTierNova
```

诊断事件只保存在进程内的 **诊断** 页缓冲区，不上传；如遇异常，请结合诊断页和系统 logcat 手动记录。

## 开发结构

- `app/src/main/java/nova/lunavane/MainActivity.kt`：Compose 入口、VPN 授权与连接/断开动作串行化、外观偏好持久化。
- `app/src/main/java/nova/lunavane/NetworkProfiles.kt` 与 `NetworkProfileStore.kt`：已保存网络的校验、排序、迁移及本地持久化。
- `app/src/main/java/nova/lunavane/TunnelState.kt`：可观察的 UI 状态，以及节点、虚拟网络、恢复阶段、Peer、DNS、Moon 操作结果等只读字段。
- `app/src/main/java/nova/lunavane/vpn/ZeroTierVpnService.kt`：VPN 生命周期、UDP socket、TUN 转发、邻居解析、网络切换和 Peer 刷新。
- `app/src/main/java/nova/lunavane/vpn/`：会话校验、UDP 发送结果、有界包预算、网络候选与切换策略、恢复策略/调度和 Peer 行解析等可测试逻辑。
- `app/src/main/java/nova/lunavane/core/`：JNI 声明和 Native 回调接口。
- `app/src/main/cpp/NativeBridge.cpp`：官方 ZeroTier C API 桥接、状态文件持久化、Peer 查询和串行 worker。
- `app/src/main/cpp/zerotier/`：固定版本 ZeroTier Core 源码及其依赖。
- `app/src/main/java/nova/lunavane/ui/`：四页 Compose 界面、Material 3 主题、连接状态映射、输入校验和通用组件；`ui/components/` 放置电源按钮、品牌标记、路径卡片等复用组件。
- `app/src/main/res/values/`、`app/src/main/res/values-night/` 与 `app/src/main/res/values-zh-rCN/`：英文和简体中文文案，以及日间/夜间启动主题与颜色。
- `app/src/test/java/nova/lunavane/vpn/`：本地 JVM 单元测试，覆盖会话校验、发送结果、恢复策略和调度、候选选择、包预算和 Peer 解析。
- `app/src/test/java/nova/lunavane/ui/`：连接状态推导、输入校验和 Moon 分组的本地单元测试。
- `app/src/test/java/nova/lunavane/NetworkProfilesTest.kt`：网络列表迁移、去重、重命名、选择、删除、排序和最近使用状态。

## 已知限制

- 目前以 Debug 构建和实际设备验证为主；已有本地 JVM 单元测试，但尚未提供签名 Release 包、Android 仪器测试或端到端自动化测试，也未声明对不同设备/网络组合的完整兼容性。
- 本次 Material 3 界面重构已通过构建、JVM 单元测试和 Android Lint，但尚未在真机上执行视觉、触控和系统返回手势验收；小屏、横屏、大字体、手势导航和不同厂商系统上的最终表现仍需设备验证。
- 已验证环境不代表 IPv6、所有 Android 厂商系统、所有网络或所有 ZeroTier 拓扑均已验证。
- 应用同一时间只支持一个活动网络；多个网络配置可保存并快速串行切换，但不支持多网络同时在线。
- 界面不显示连接时长、传输速率或流量统计。应用目前没有可靠的本地统计数据源，因此不会用估算值或伪造值填充这些指标；Peer 延迟仅显示 Core 实际返回的字段。
- 网络连通性依赖 Android 底层网络、ZeroTier 上游可达性、对端授权、受管路由和远端网关配置。网络策略阻止 UDP 时，应用不能保证连接可用。
- Moon Orbit 请求被 Core 接受不代表 seed 已成功获取 Moon 定义；获取过程是异步的。Peer 列表也不提供指定 Moon world ID 的定义加载确认，因此界面不会把 seed 可见或 `MOON` 角色当作已验证。
- UDP 恢复采用有限退避；退避耗尽后应用停止自动重试，等待新的物理网络事件或用户重新连接。这不是无限重连保证。
- 底层网络候选目前只包含 Wi-Fi、蜂窝和以太网；蓝牙共享、USB 网卡等类型的网络会被忽略，不会被选作 ZeroTier 的底层传输。
- Peer 查询每 5 秒通过 Native worker 执行一次，其字段只包含 Core 明确初始化的数据；未被 Core 填充的 bonding 字段不会显示。

## 许可证

Lunavane 自行编写的实现代码、Android 资源和项目构建配置采用 Mozilla Public License 2.0（MPL-2.0），完整文本见 [`LICENSE`](LICENSE)。该许可证不适用于 vendored `app/src/main/cpp/zerotier/` 源码或 Gradle Wrapper 文件，也不会更改任何第三方文件原有的许可条件。

ZeroTier Core、其依赖和 Gradle Wrapper 须遵守各自随附的许可证。归属与许可摘要见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 及各组件的许可证文件；分发或修改第三方代码时请保留其版权和许可声明。
