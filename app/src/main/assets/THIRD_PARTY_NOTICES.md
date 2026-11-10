# 第三方软件许可声明

## ZeroTier One Core

ZeroTier, Inc. 的 ZeroTier One Core 1.16.2 源码按 Mozilla Public License 2.0 许可，详见 `app/src/main/cpp/zerotier/LICENSE-MPL.txt` 和 `app/src/main/cpp/zerotier/LICENSE.txt`。各源码文件内的版权和许可头应随源码保留。

## nlohmann/json

随 Core 提供的 nlohmann/json 按 MIT License 许可，详见 `app/src/main/cpp/zerotier/ext/nlohmann/LICENSE.MIT`。

## prometheus-cpp-lite

随 Core 提供的 prometheus-cpp-lite 按 MIT License 许可，详见 `app/src/main/cpp/zerotier/ext/prometheus-cpp-lite-1.0/LICENSE`。

## LZ4

ZeroTier 的 `node/Packet.cpp` 内嵌 LZ4 实现，版权与 BSD 2-Clause 许可如下：

```text
LZ4 - Fast LZ compression algorithm
Copyright (C) 2011-2016, Yann Collet.

BSD 2-Clause License (http://www.opensource.org/licenses/bsd-license.php)

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

* Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above
  copyright notice, this list of conditions and the following disclaimer
  in the documentation and/or other materials provided with the
  distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## AndroidX, Jetpack Compose 和 Kotlin Coroutines

AndroidX、Jetpack Compose 与 Kotlin Coroutines 依赖按各自分发包中的 Apache License 2.0 条款使用。AndroidX 相关许可文本随依赖保存在 APK 的 `META-INF` 资源中。
