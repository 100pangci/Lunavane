# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and development commands

Use the Gradle wrapper from the repository root. The documented toolchain is JDK 17, Android SDK Platform 36, NDK `28.0.13004108`, and CMake `3.22.1`.

```sh
# Build the debug APK
./gradlew --no-daemon :app:assembleDebug

# Install on a connected device or emulator
./gradlew --no-daemon :app:installDebug

# Run Android lint
./gradlew --no-daemon :app:lintDebug

# Run local unit tests
./gradlew --no-daemon :app:testDebugUnitTest

# Run one local unit test (replace the class with the test to run)
./gradlew --no-daemon :app:testDebugUnitTest --tests 'nova.lunavane.YourTestClass'

# Run instrumented tests on a connected device, if present
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

Local JVM unit tests live under `app/src/test/java/nova/lunavane/` (network recovery/policy, session gating, Peer parsing, Moon persistence, and UI state mapping). There are no instrumented test sources; the `connectedDebugAndroidTest` command applies only when such a test is added. Use the single-test filter above to run one class. Debug APK output is `app/build/outputs/apk/debug/app-debug.apk`; the project currently documents no signed Release package.

## Architecture

Lunavane is an Android ZeroTier client. The main path crosses the Compose UI, an Android VPN foreground service, JNI, and the vendored ZeroTier One Core:

1. `MainActivity` hosts the Compose UI, persists the selected network ID and appearance preferences, requests Android's VPN consent, and starts or stops `ZeroTierVpnService` with intents. Connection/disconnection actions are serialized through a pending-action flow so repeated taps cannot enqueue duplicate operations.
2. `LunavaneApp` provides the four-tab shell (Home, Networks, Moon, Diagnostics) and in-app Settings pages. `HomeScreen` shows the power action and summary; `NetworksScreen` owns network ID input, configuration details, routes/DNS, and Peer details; `MoonScreen` owns Orbit/Deorbit and seed visibility; `DiagnosticsScreen` shows runtime state and the in-memory `DiagnosticLog`. UI copy belongs in `res/values/strings.xml` (English) and `res/values-zh-rCN/strings.xml` (Simplified Chinese), not in composables. `ConnectionStatus.kt` maps `TunnelUiState` to a user-facing connection state; never infer "connected" from `tunnelActive` alone.
3. `ZeroTierVpnService` owns the tunnel session. It starts the Core, watches the default Android network, protects the ZeroTier UDP socket from VPN routing, applies Core network configuration with `VpnService.Builder`, and moves IP packets between the TUN and the Core. It also handles IPv4 ARP, IPv6 neighbor discovery, and bounded queues for packets waiting on neighbor resolution. Session-generation checks discard callbacks and work from an older tunnel session. Besides its existing status strings, it publishes read-only UI fields (DNS, underlay transport, last error, typed Moon operation outcomes) into `TunnelState`.
4. `NativeCore.kt` declares the JNI surface and `NativeCallbacks.java` declares callbacks into Kotlin. `NativeBridge.cpp` implements both sides of that contract, owns a ZeroTier node, serializes Core operations on a native worker, runs Core background tasks by deadline, and translates Core callbacks into Kotlin callbacks. Keep JNI method names, argument types, and callback signatures aligned across these files when changing the boundary.
5. Native network configuration is serialized as JSON in `NativeBridge.cpp`, parsed into `NetworkConfig`, then applied by the VPN service. Changes to the serialized fields must be kept in sync with the Kotlin parser and VPN setup.
6. ZeroTier node identity and Core state are stored in the app-private `files/zerotier` directory. Moon orbit requests and the last network ID are stored in app-private preferences; Moon requests are replayed when the Core starts. Appearance settings use new keys in the existing private preferences. Diagnostic events exist only in process memory.

`app/src/main/cpp/zerotier/` contains vendored ZeroTier Core and dependencies; `CMakeLists.txt` compiles the Core node sources together with `NativeBridge.cpp`. Treat vendored sources as third-party code: preserve their notices and licenses, and keep Lunavane-specific JNI work in `NativeBridge.cpp` unless a Core change is actually required. See `README.md` for networking behavior, setup, privacy details, and known limitations; see `THIRD_PARTY_NOTICES.md` for dependency notices.
