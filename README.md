# Lunavane

Lunavane 是一个面向 Android 的 ZeroTier 客户端，使用官方 ZeroTier One Core 1.16.2、Android `VpnService`、Kotlin、Jetpack Compose 和 JNI/C++。它会把 Core 下发的网络配置应用到系统 TUN，并在应用数据路径中转发虚拟网络数据包。

项目仍在开发中，目前提供可构建的 Debug 版本。维护者已在一台 Android 真机上验证加入 ZeroTier 网络，并通过受管 LAN 路由访问局域网 NAS。设备、Android 版本、ZeroTier 网络配置和底层 UDP 连通性会影响实际结果；这项验证不代表所有环境均受支持。

> Lunavane 是独立项目，与 ZeroTier, Inc. 无隶属、赞助或官方支持关系。ZeroTier 名称及相关商标归其各自所有者所有。

## 功能

- 使用官方 ZeroTier Core 加入网络并接收控制器下发的网络配置。
- 通过 Android `VpnService` 建立 TUN，应用虚拟地址、受管路由、DNS 和 MTU。
- 在 TUN 与 ZeroTier Core 之间转发 IPv4/IPv6 包；应用侧处理 IPv4 ARP 和 IPv6 NDP 邻居发现，并缓存邻居解析期间的待发包。
- 监听 Android 默认网络变化，保护 ZeroTier UDP socket 免于回流到 VPN，并在底层网络变化时更新 socket。
- 支持 Moon Orbit/Deorbit 请求；Moon world ID 和 seed 节点地址保存在应用本地。
- 诊断事件缓冲区最多保留 100 条，首页显示最近 10 条，可在界面清除缓冲。
- 提供自适应 Android 启动器图标。

## 运行要求

- Android 8.0（API 26）或更新版本。
- 有效的 16 位十六进制 ZeroTier 网络 ID，以及加入该网络的授权。
- 若要访问远端 LAN，网络还需要配置相应的受管路由和网关转发。

应用显示名称为 **Lunavane**。当前包名仍为 `dev.nova.zerotier`，版本号为 `0.1.0`。

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

1. 安装并打开 Lunavane。
2. 输入 16 位十六进制网络 ID，点击 **Connect**。
3. 在 Android 系统 VPN 授权界面确认连接。
4. 在 ZeroTier 网络控制器中授权此节点加入网络。
5. 等待 Core 收到有效网络配置。首页会显示节点地址、网络名、分配地址和实际应用的路由。
6. 点击 **Disconnect** 停止隧道。

VPN 授权通过 Android 系统界面完成。隧道运行期间，应用以 Android 前台服务维持连接并显示持续通知。仅启动服务或获得 VPN 授权不代表网络已经连接；应用会等待 Core 返回有效配置后再建立 TUN。

### 配置 Moon

在已连接且 Core 节点就绪时，输入 16 位十六进制 Moon world ID 和 10 位十六进制 seed 节点地址，然后点击 **Orbit**。Orbit 请求和 seed 映射保存在本机应用偏好设置中；Core 接受请求后，Moon 获取仍是异步过程。移除已保存的本机请求可使用 **Deorbit**。

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
- Core 回调负责 UDP 数据报、虚拟帧和网络配置。Native worker 按 Core 返回的 deadline 运行后台任务。
- UDP socket 先由 `VpnService.protect()` 排除在 VPN 路由之外。若 Android 拒绝显式绑定当前 `Network`，应用会记录本地诊断并继续使用受保护 socket 的默认底层路由。
- 访问远端 LAN 时，ZeroTier 网络需要包含目标 LAN 前缀的受管路由；网关节点还必须能转发流量，并有可用的返回路径。首页显示当前实际应用的路由，可用于确认 Android 收到了哪些目标前缀。

## 权限与隐私

Manifest 声明的权限：

- `INTERNET`：与 ZeroTier 网络通信。
- `ACCESS_NETWORK_STATE`：观察底层网络变化。
- `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_SPECIAL_USE`：维持用户主动建立的 VPN 前台服务。
- `POST_NOTIFICATIONS`：声明通知权限；通知的实际可见性受 Android 版本和系统通知设置影响。
- Android 系统的 `BIND_VPN_SERVICE` 保护服务，并由用户通过系统 VPN 授权界面启用。

应用不申请存储权限。节点身份和 Core 状态保存在应用私有目录 `files/zerotier`；网络 ID 与 Moon 请求保存在应用私有偏好设置中。身份私钥不会写入诊断日志。诊断事件仅保留在进程内存中，最多 100 条；清除或进程退出后不再保留。应用不会静默上传配置或诊断日志，也没有内置遥测上传流程。

诊断内容可能包含节点地址、IP 地址、网络状态等环境信息。若在 Issue 或公开讨论中分享日志或截图，请先遮盖网络 ID、节点地址、局域网地址和其他可识别信息。不要分享身份目录或任何私钥。

## 故障排查

- **一直等待网络配置**：确认网络 ID 正确、节点已在控制器中授权，并检查设备是否能访问互联网及 ZeroTier 上游节点。
- **VPN 已启动但没有虚拟地址或路由**：VPN 系统授权本身不会分配网络配置；检查控制器是否已授权节点并为该网络配置地址和路由。
- **能访问虚拟网段但不能访问远端 LAN**：检查首页 Routes 中是否有目标 LAN 前缀及下一跳，并确认远端网关转发和返回路由有效。
- **网络切换后暂时离线**：等待 ZeroTier 上游重新可达；打开 Diagnostics 查看 UDP socket 和邻居解析事件。
- **需要提交诊断**：仅分享手动复制或截取的必要信息，并先遮盖前述地址和 ID。应用不会自动导出或上传诊断内容。

## 开发结构

- `app/src/main/java/dev/nova/zerotier/vpn/ZeroTierVpnService.kt`：VPN 生命周期、UDP socket、TUN 转发、邻居解析和网络切换。
- `app/src/main/java/dev/nova/zerotier/core/`：JNI 声明和 Native 回调接口。
- `app/src/main/cpp/NativeBridge.cpp`：官方 ZeroTier C API 桥接、状态文件持久化和串行 worker。
- `app/src/main/cpp/zerotier/`：固定版本 ZeroTier Core 源码及其依赖。
- `app/src/main/java/dev/nova/zerotier/ui/`：Compose 界面、主题和首页。
- `app/src/main/res/`：Android 启动器图标资源。

## 已知限制

- 目前以 Debug 构建和实际设备验证为主；尚未提供签名 Release 包、单元测试、Android 仪器测试或端到端自动化测试，也未声明对不同设备/网络组合的完整兼容性。
- 已验证环境不代表 IPv6、所有 Android 厂商系统、所有网络或所有 ZeroTier 拓扑均已验证。
- 网络连通性依赖 Android 底层网络、ZeroTier 上游可达性、对端授权、受管路由和远端网关配置。网络策略阻止 UDP 时，应用不能保证连接可用。
- Moon Orbit 请求被 Core 接受不代表 seed 已成功获取 Moon 定义；获取过程是异步的。

## 许可证

本仓库当前没有为 Lunavane 自有 Kotlin、Java、C++ 桥接和界面代码声明项目级许可证。公开可见不等于授予复制、修改或再分发许可。ZeroTier Core 及第三方依赖按各自许可证使用；版权、许可证文本和依赖说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 及 `app/src/main/cpp/zerotier/` 下的文件。分发或修改第三方源码时请遵守对应许可证。
