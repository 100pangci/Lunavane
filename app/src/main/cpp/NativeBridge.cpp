#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include <array>
#include <cstdlib>
#include <future>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <filesystem>
#include <functional>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "zerotier/include/ZeroTierOne.h"

namespace {
constexpr char kTag[] = "ZeroTierNova";
constexpr uint64_t kBroadcastMac = 0xffffffffffffULL;
constexpr uint64_t kZeroTierMulticastMac = 0x01005e000000ULL;
constexpr unsigned int kEtherTypeArp = 0x0806;
constexpr unsigned int kEtherTypeIpv4 = 0x0800;
constexpr unsigned int kEtherTypeIpv6 = 0x86dd;

int64_t monotonicMillis()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string hexId(uint64_t id, unsigned int width)
{
    std::ostringstream out;
    out << std::hex << std::nouppercase << std::setw(width) << std::setfill('0') << id;
    return out.str();
}

std::string statePath(int type, const uint64_t id[2])
{
    switch (type) {
        case ZT_STATE_OBJECT_IDENTITY_PUBLIC: return "identity.public";
        case ZT_STATE_OBJECT_IDENTITY_SECRET: return "identity.secret";
        case ZT_STATE_OBJECT_PLANET: return "planet";
        case ZT_STATE_OBJECT_MOON: return "moons.d/" + hexId(id[0], 16) + ".moon";
        case ZT_STATE_OBJECT_PEER: return "peers.d/" + hexId(id[0], 10);
        case ZT_STATE_OBJECT_NETWORK_CONFIG: return "networks.d/" + hexId(id[0], 16) + ".conf";
        default: return {};
    }
}

bool ensureDirectory(const std::string& path)
{
    std::error_code error;
    std::filesystem::create_directories(path, error);
    return !error;
}

class Bridge {
public:
    Bridge(JavaVM* vm, jobject callbacks, std::string stateDir)
        : vm_(vm), stateDir_(std::move(stateDir))
    {
        JNIEnv* env = nullptr;
        if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            throw std::runtime_error("JNI environment unavailable");
        }
        callbacks_ = env->NewGlobalRef(callbacks);
        worker_ = std::thread([this] { run(); });
        post([this] { initializeNode(); });
        waitForInitialization();
    }

    ~Bridge()
    {
        stop();
    }

    void stop()
    {
        bool expected = false;
        if (!stopping_.compare_exchange_strong(expected, true)) return;
        post([this] {
            if (node_) {
                ZT_Node_delete(node_);
                node_ = nullptr;
            }
        });
        {
            std::lock_guard<std::mutex> lock(queueMutex_);
            quit_ = true;
        }
        queueCondition_.notify_all();
        if (worker_.joinable()) worker_.join();
        JNIEnv* env = attach();
        if (env && callbacks_) env->DeleteGlobalRef(callbacks_);
        callbacks_ = nullptr;
        detach();
    }

    bool ready() const { return initialized_.load(); }

    int join(uint64_t nwid)
    {
        return invoke([this, nwid] { return node_ ? ZT_Node_join(node_, nwid, nullptr, nullptr) : ZT_RESULT_FATAL_ERROR_INTERNAL; });
    }

    int leave(uint64_t nwid)
    {
        return invoke([this, nwid] { return node_ ? ZT_Node_leave(node_, nwid, nullptr, nullptr) : ZT_RESULT_FATAL_ERROR_INTERNAL; });
    }

    int orbit(uint64_t moonWorldId, uint64_t moonSeed)
    {
        return invoke([this, moonWorldId, moonSeed] {
            return node_ ? ZT_Node_orbit(node_, nullptr, moonWorldId, moonSeed) : ZT_RESULT_FATAL_ERROR_INTERNAL;
        });
    }

    int deorbit(uint64_t moonWorldId)
    {
        return invoke([this, moonWorldId] {
            return node_ ? ZT_Node_deorbit(node_, nullptr, moonWorldId) : ZT_RESULT_FATAL_ERROR_INTERNAL;
        });
    }

    int processWire(const sockaddr_storage& remote, const std::vector<uint8_t>& packet)
    {
        return invoke([this, remote, packet] {
            return node_ ? ZT_Node_processWirePacket(node_, nullptr, monotonicMillis(), 0, &remote,
                    packet.data(), static_cast<unsigned int>(packet.size()), &nextDeadline_) : ZT_RESULT_FATAL_ERROR_INTERNAL;
        });
    }

    int processFrame(uint64_t nwid, uint64_t sourceMac, uint64_t destinationMac,
                     unsigned int etherType, unsigned int vlanId, const std::vector<uint8_t>& payload)
    {
        return invoke([this, nwid, sourceMac, destinationMac, etherType, vlanId, payload] {
            return node_ ? ZT_Node_processVirtualNetworkFrame(node_, nullptr, monotonicMillis(), nwid,
                    sourceMac, destinationMac, etherType, vlanId, payload.data(),
                    static_cast<unsigned int>(payload.size()), &nextDeadline_) : ZT_RESULT_FATAL_ERROR_INTERNAL;
        });
    }

    int multicastSubscribe(uint64_t nwid, uint64_t mac, unsigned long adi)
    {
        return invoke([this, nwid, mac, adi] {
            return node_ ? ZT_Node_multicastSubscribe(node_, nullptr, nwid, mac, adi) : ZT_RESULT_FATAL_ERROR_INTERNAL;
        });
    }

private:
    struct Task {
        std::function<void()> run;
        std::function<void()> fail;
    };

    JNIEnv* attach()
    {
        JNIEnv* env = nullptr;
        if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
        if (vm_->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attachedHere_ = true;
            return env;
        }
        return nullptr;
    }

    void detach()
    {
        if (attachedHere_) {
            vm_->DetachCurrentThread();
            attachedHere_ = false;
        }
    }

    void post(std::function<void()> fn, std::function<void()> fail = {})
    {
        {
            std::lock_guard<std::mutex> lock(queueMutex_);
            if (quit_) {
                if (fail) fail();
                return;
            }
            queue_.push_back({std::move(fn), std::move(fail)});
        }
        queueCondition_.notify_one();
    }

    template <typename F>
    auto invoke(F&& action) -> decltype(action())
    {
        using T = decltype(action());
        if (std::this_thread::get_id() == workerId_) return action();
        auto promise = std::make_shared<std::promise<T>>();
        auto future = promise->get_future();
        post([promise, action = std::forward<F>(action)]() mutable {
            try { promise->set_value(action()); }
            catch (...) { promise->set_exception(std::current_exception()); }
        }, [promise] { promise->set_value(static_cast<T>(ZT_RESULT_FATAL_ERROR_INTERNAL)); });
        try { return future.get(); }
        catch (...) { return static_cast<T>(ZT_RESULT_FATAL_ERROR_INTERNAL); }
    }

    void waitForInitialization()
    {
        std::unique_lock<std::mutex> lock(initMutex_);
        initCondition_.wait(lock, [this] { return initialized_.load() || initFailed_.load(); });
    }

    void initializeNode()
    {
        if (!ensureDirectory(stateDir_) || !ensureDirectory(stateDir_ + "/moons.d") ||
            !ensureDirectory(stateDir_ + "/peers.d") || !ensureDirectory(stateDir_ + "/networks.d")) {
            initFailed_ = true;
            initCondition_.notify_all();
            notifyEvent(1, "Cannot create private ZeroTier state directory");
            return;
        }

        ZT_Node_Callbacks callbacks{};
        callbacks.version = 0;
        callbacks.statePutFunction = statePut;
        callbacks.stateGetFunction = stateGet;
        callbacks.wirePacketSendFunction = wireSend;
        callbacks.virtualNetworkFrameFunction = virtualFrame;
        callbacks.virtualNetworkConfigFunction = networkConfig;
        callbacks.eventCallback = event;

        ZT_Node_Config config{};
        const auto result = ZT_Node_new(&node_, &config, this, nullptr, &callbacks, monotonicMillis());
        if (result != ZT_RESULT_OK || !node_) {
            initFailed_ = true;
            initCondition_.notify_all();
            notifyEvent(1, "ZeroTier core initialization failed: " + std::to_string(result));
            return;
        }
        chmod((stateDir_ + "/identity.secret").c_str(), 0600);
        initialized_ = true;
        initCondition_.notify_all();
        ZT_NodeStatus status{};
        ZT_Node_status(node_, &status);
        notifyEvent(0, hexId(status.address, 10));
    }

    static Bridge* self(void* user) { return static_cast<Bridge*>(user); }

    static void statePut(ZT_Node*, void* user, void*, ZT_StateObjectType type,
                         const uint64_t id[2], const void* data, int length)
    {
        Bridge* bridge = self(user);
        const std::string relative = statePath(type, id);
        if (relative.empty()) return;
        const std::string path = bridge->stateDir_ + "/" + relative;
        if (length < 0) {
            unlink(path.c_str());
            return;
        }
        const auto slash = path.find_last_of('/');
        if (slash != std::string::npos) ensureDirectory(path.substr(0, slash));
        const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd < 0) return;
        const auto* bytes = static_cast<const uint8_t*>(data);
        size_t remaining = static_cast<size_t>(length);
        while (remaining) {
            const ssize_t written = write(fd, bytes, remaining);
            if (written < 0) {
                if (errno == EINTR) continue;
                break;
            }
            bytes += written;
            remaining -= static_cast<size_t>(written);
        }
        fchmod(fd, 0600);
        close(fd);
    }

    static int stateGet(ZT_Node*, void* user, void*, ZT_StateObjectType type,
                        const uint64_t id[2], void* buffer, unsigned int maxLength)
    {
        Bridge* bridge = self(user);
        const std::string relative = statePath(type, id);
        if (relative.empty()) return -1;
        const std::string path = bridge->stateDir_ + "/" + relative;
        const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) return -1;
        struct stat fileInfo{};
        if (fstat(fd, &fileInfo) != 0 || !S_ISREG(fileInfo.st_mode) ||
            fileInfo.st_size < 0 || static_cast<uint64_t>(fileInfo.st_size) > maxLength) {
            close(fd);
            return -1;
        }
        size_t remaining = static_cast<size_t>(fileInfo.st_size);
        auto* output = static_cast<uint8_t*>(buffer);
        const size_t total = remaining;
        while (remaining) {
            const ssize_t count = read(fd, output, remaining);
            if (count < 0) {
                if (errno == EINTR) continue;
                close(fd);
                return -1;
            }
            if (count == 0) break;
            output += count;
            remaining -= static_cast<size_t>(count);
        }
        close(fd);
        return remaining == 0 ? static_cast<int>(total) : -1;
    }

    static int wireSend(ZT_Node*, void* user, void*, int64_t, const sockaddr_storage* remote,
                        const void* packet, unsigned int length, unsigned int)
    {
        Bridge* bridge = self(user);
        auto* env = bridge->attach();
        if (!env || !bridge->callbacks_) { bridge->detach(); return -1; }
        const jclass cls = env->GetObjectClass(bridge->callbacks_);
        const jmethodID method = env->GetMethodID(cls, "onWirePacket", "([BI[B)V");
        if (!method || env->ExceptionCheck()) { env->ExceptionClear(); bridge->detach(); return -1; }
        uint16_t port = 0;
        std::array<uint8_t, 16> addressBytes{};
        size_t addressLength = 0;
        if (remote->ss_family == AF_INET) {
            const auto* ipv4 = reinterpret_cast<const sockaddr_in*>(remote);
            std::memcpy(addressBytes.data(), &ipv4->sin_addr, 4);
            addressLength = 4;
            port = ntohs(ipv4->sin_port);
        } else if (remote->ss_family == AF_INET6) {
            const auto* ipv6 = reinterpret_cast<const sockaddr_in6*>(remote);
            std::memcpy(addressBytes.data(), &ipv6->sin6_addr, 16);
            addressLength = 16;
            port = ntohs(ipv6->sin6_port);
        } else {
            bridge->detach();
            return -1;
        }
        jbyteArray javaAddress = env->NewByteArray(static_cast<jsize>(addressLength));
        jbyteArray javaPacket = env->NewByteArray(static_cast<jsize>(length));
        if (!javaAddress || !javaPacket) { bridge->detach(); return -1; }
        env->SetByteArrayRegion(javaAddress, 0, static_cast<jsize>(addressLength), reinterpret_cast<const jbyte*>(addressBytes.data()));
        env->SetByteArrayRegion(javaPacket, 0, static_cast<jsize>(length), static_cast<const jbyte*>(packet));
        env->CallVoidMethod(bridge->callbacks_, method, javaAddress, static_cast<jint>(port), javaPacket);
        const bool ok = !env->ExceptionCheck();
        if (!ok) env->ExceptionClear();
        env->DeleteLocalRef(javaAddress);
        env->DeleteLocalRef(javaPacket);
        env->DeleteLocalRef(cls);
        bridge->detach();
        return ok ? 0 : -1;
    }

    static void virtualFrame(ZT_Node*, void* user, void*, uint64_t nwid, void**, uint64_t sourceMac,
                             uint64_t destinationMac, unsigned int etherType, unsigned int vlanId,
                             const void* payload, unsigned int length)
    {
        Bridge* bridge = self(user);
        auto* env = bridge->attach();
        if (!env || !bridge->callbacks_) { bridge->detach(); return; }
        const jclass cls = env->GetObjectClass(bridge->callbacks_);
        const jmethodID method = env->GetMethodID(cls, "onVirtualFrame", "(JJJII[B)V");
        if (!method || env->ExceptionCheck()) { env->ExceptionClear(); bridge->detach(); return; }
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(length));
        if (!bytes) { bridge->detach(); return; }
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length), static_cast<const jbyte*>(payload));
        env->CallVoidMethod(bridge->callbacks_, method, static_cast<jlong>(nwid), static_cast<jlong>(sourceMac),
                            static_cast<jlong>(destinationMac), static_cast<jint>(etherType),
                            static_cast<jint>(vlanId), bytes);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(cls);
        bridge->detach();
    }

    static int networkConfig(ZT_Node*, void* user, void*, uint64_t nwid, void**,
                             ZT_VirtualNetworkConfigOperation operation, const ZT_VirtualNetworkConfig* config)
    {
        Bridge* bridge = self(user);
        if (!config) return EINVAL;
        std::ostringstream json;
        json << "{\"networkId\":\"" << hexId(nwid, 16) << "\",\"status\":" << config->status
             << ",\"name\":\"";
        for (const char c : config->name) {
            if (!c) break;
            if (c == '\\' || c == '"') json << '\\';
            if (static_cast<unsigned char>(c) >= 0x20) json << c;
        }
        json << "\",\"mac\":\"" << hexId(config->mac, 12) << "\",\"mtu\":" << config->mtu
             << ",\"addresses\":[";
        bool comma = false;
        for (unsigned int i = 0; i < config->assignedAddressCount; ++i) {
            const auto& address = config->assignedAddresses[i];
            char text[INET6_ADDRSTRLEN]{};
            int prefix = 0;
            if (address.ss_family == AF_INET) {
                const auto* value = reinterpret_cast<const sockaddr_in*>(&address);
                inet_ntop(AF_INET, &value->sin_addr, text, sizeof(text));
                prefix = ntohs(value->sin_port);
            } else if (address.ss_family == AF_INET6) {
                const auto* value = reinterpret_cast<const sockaddr_in6*>(&address);
                inet_ntop(AF_INET6, &value->sin6_addr, text, sizeof(text));
                prefix = ntohs(value->sin6_port);
            } else continue;
            if (comma) json << ',';
            json << "{\"address\":\"" << text << "\",\"prefix\":" << prefix << '}';
            comma = true;
        }
        json << "],\"routes\":[";
        comma = false;
        for (unsigned int i = 0; i < config->routeCount; ++i) {
            const auto& route = config->routes[i];
            char text[INET6_ADDRSTRLEN]{};
            int prefix = 0;
            if (route.target.ss_family == AF_INET) {
                const auto* value = reinterpret_cast<const sockaddr_in*>(&route.target);
                inet_ntop(AF_INET, &value->sin_addr, text, sizeof(text));
                prefix = ntohs(value->sin_port);
            } else if (route.target.ss_family == AF_INET6) {
                const auto* value = reinterpret_cast<const sockaddr_in6*>(&route.target);
                inet_ntop(AF_INET6, &value->sin6_addr, text, sizeof(text));
                prefix = ntohs(value->sin6_port);
            } else continue;
            char gateway[INET6_ADDRSTRLEN]{};
            if (route.via.ss_family == AF_INET) {
                const auto* value = reinterpret_cast<const sockaddr_in*>(&route.via);
                inet_ntop(AF_INET, &value->sin_addr, gateway, sizeof(gateway));
            } else if (route.via.ss_family == AF_INET6) {
                const auto* value = reinterpret_cast<const sockaddr_in6*>(&route.via);
                inet_ntop(AF_INET6, &value->sin6_addr, gateway, sizeof(gateway));
            }
            if (comma) json << ',';
            json << "{\"address\":\"" << text << "\",\"prefix\":" << prefix
                 << ",\"gateway\":\"" << gateway << "\"}";
            comma = true;
        }
        json << "],\"dnsServers\":[";
        comma = false;
        for (const auto& server : config->dns.server_addr) {
            char text[INET6_ADDRSTRLEN]{};
            if (server.ss_family == AF_INET) inet_ntop(AF_INET, &reinterpret_cast<const sockaddr_in*>(&server)->sin_addr, text, sizeof(text));
            else if (server.ss_family == AF_INET6) inet_ntop(AF_INET6, &reinterpret_cast<const sockaddr_in6*>(&server)->sin6_addr, text, sizeof(text));
            else continue;
            if (comma) json << ',';
            json << '"' << text << '"';
            comma = true;
        }
        json << "],\"dnsDomain\":\"" << config->dns.domain << "\",\"multicast\":[";
        comma = false;
        for (unsigned int i = 0; i < config->multicastSubscriptionCount; ++i) {
            if (comma) json << ',';
            json << "{\"mac\":\"" << hexId(config->multicastSubscriptions[i].mac, 12)
                 << "\",\"adi\":" << config->multicastSubscriptions[i].adi << '}';
            comma = true;
        }
        json << "]}";
        return bridge->notifyNetwork(nwid, operation, config->status, json.str());
    }

    int notifyNetwork(uint64_t nwid, int operation, int status, const std::string& json)
    {
        auto* env = attach();
        if (!env || !callbacks_) { detach(); return EIO; }
        const jclass cls = env->GetObjectClass(callbacks_);
        const jmethodID method = env->GetMethodID(cls, "onNetworkConfig", "(JIILjava/lang/String;)I");
        if (!method || env->ExceptionCheck()) { env->ExceptionClear(); detach(); return EIO; }
        jstring text = env->NewStringUTF(json.c_str());
        const jint result = env->CallIntMethod(callbacks_, method, static_cast<jlong>(nwid),
                static_cast<jint>(operation), static_cast<jint>(status), text);
        const bool failed = env->ExceptionCheck();
        if (failed) env->ExceptionClear();
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(cls);
        detach();
        return failed ? EIO : result;
    }

    static void event(ZT_Node*, void* user, void*, ZT_Event type, const void* metadata)
    {
        Bridge* bridge = self(user);
        std::string message;
        if (type == ZT_EVENT_UP) message = "node initialized";
        else if (type == ZT_EVENT_ONLINE) message = "online";
        else if (type == ZT_EVENT_OFFLINE) message = "offline";
        bridge->notifyEvent(type, message);
    }

    void notifyEvent(int type, const std::string& message)
    {
        auto* env = attach();
        if (!env || !callbacks_) { detach(); return; }
        const jclass cls = env->GetObjectClass(callbacks_);
        const jmethodID method = env->GetMethodID(cls, "onNodeEvent", "(ILjava/lang/String;)V");
        if (method && !env->ExceptionCheck()) {
            jstring text = env->NewStringUTF(message.c_str());
            env->CallVoidMethod(callbacks_, method, static_cast<jint>(type), text);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(text);
        } else if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
        detach();
    }

    void run()
    {
        workerId_ = std::this_thread::get_id();
        for (;;) {
            Task task;
            {
                std::unique_lock<std::mutex> lock(queueMutex_);
                if (queue_.empty() && !quit_) {
                    if (node_) {
                        const int64_t remaining = nextDeadline_ - monotonicMillis();
                        const int64_t waitMs = remaining > 0 ? remaining : 1;
                        queueCondition_.wait_for(lock, std::chrono::milliseconds(waitMs), [this] {
                            return quit_ || !queue_.empty();
                        });
                    } else {
                        queueCondition_.wait(lock, [this] { return quit_ || !queue_.empty(); });
                    }
                }
                if (quit_ && queue_.empty()) break;
                if (!queue_.empty()) {
                    task = std::move(queue_.front());
                    queue_.pop_front();
                }
            }
            if (task.run) task.run();
            if (node_) {
                const int64_t now = monotonicMillis();
                if (nextDeadline_ <= 0 || now >= nextDeadline_) {
                    ZT_Node_processBackgroundTasks(node_, nullptr, now, &nextDeadline_);
                }
            }
        }
    }

    JavaVM* vm_;
    jobject callbacks_ = nullptr;
    std::string stateDir_;
    ZT_Node* node_ = nullptr;
    std::thread worker_;
    std::thread::id workerId_;
    std::mutex queueMutex_;
    std::condition_variable queueCondition_;
    std::deque<Task> queue_;
    std::atomic<bool> initialized_{false};
    std::atomic<bool> initFailed_{false};
    std::atomic<bool> stopping_{false};
    std::mutex initMutex_;
    std::condition_variable initCondition_;
    bool quit_ = false;
    static thread_local bool attachedHere_;
    volatile int64_t nextDeadline_ = 0;
};

thread_local bool Bridge::attachedHere_ = false;

jbyteArray byteArray(JNIEnv* env, jbyteArray input, std::vector<uint8_t>& output)
{
    const jsize length = env->GetArrayLength(input);
    output.resize(static_cast<size_t>(length));
    if (length) env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(output.data()));
    return input;
}

uint64_t parseHex(JNIEnv* env, jstring value)
{
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return 0;
    char* end = nullptr;
    const uint64_t result = std::strtoull(chars, &end, 16);
    const bool valid = end && *end == '\0' && end != chars;
    env->ReleaseStringUTFChars(value, chars);
    return valid ? result : 0;
}

Bridge* fromHandle(jlong handle) { return reinterpret_cast<Bridge*>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_nova_zerotier_core_NativeCore_create(JNIEnv* env, jobject, jobject callbacks, jstring directory)
{
    const char* path = env->GetStringUTFChars(directory, nullptr);
    if (!path) return 0;
    std::string stateDir(path);
    env->ReleaseStringUTFChars(directory, path);
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return 0;
    try {
        auto* bridge = new Bridge(vm, callbacks, std::move(stateDir));
        if (!bridge->ready()) { delete bridge; return 0; }
        return reinterpret_cast<jlong>(bridge);
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Core initialization: %s", exception.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nova_zerotier_core_NativeCore_destroy(JNIEnv*, jobject, jlong handle)
{
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nova_zerotier_core_NativeCore_join(JNIEnv* env, jobject, jlong handle, jstring networkId)
{
    if (!handle) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    const uint64_t id = parseHex(env, networkId);
    if (!id) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    return fromHandle(handle)->join(id);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nova_zerotier_core_NativeCore_leave(JNIEnv* env, jobject, jlong handle, jstring networkId)
{
    if (!handle) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    const uint64_t id = parseHex(env, networkId);
    if (!id) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    return fromHandle(handle)->leave(id);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nova_zerotier_core_NativeCore_orbit(JNIEnv* env, jobject, jlong handle, jstring moonWorldId, jstring moonSeed)
{
    if (!handle) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    const uint64_t id = parseHex(env, moonWorldId);
    const uint64_t seed = parseHex(env, moonSeed);
    if (!id || !seed) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    return fromHandle(handle)->orbit(id, seed);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nova_zerotier_core_NativeCore_deorbit(JNIEnv* env, jobject, jlong handle, jstring moonWorldId)
{
    if (!handle) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    const uint64_t id = parseHex(env, moonWorldId);
    if (!id) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    return fromHandle(handle)->deorbit(id);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nova_zerotier_core_NativeCore_processWirePacket(JNIEnv* env, jobject, jlong handle,
                                                         jbyteArray address, jint port, jbyteArray packet)
{
    if (!handle) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    std::vector<uint8_t> addressBytes;
    std::vector<uint8_t> packetBytes;
    byteArray(env, address, addressBytes);
    byteArray(env, packet, packetBytes);
    sockaddr_storage remote{};
    if (addressBytes.size() == 4) {
        auto* value = reinterpret_cast<sockaddr_in*>(&remote);
        value->sin_family = AF_INET;
        value->sin_port = htons(static_cast<uint16_t>(port));
        std::memcpy(&value->sin_addr, addressBytes.data(), 4);
    } else if (addressBytes.size() == 16) {
        auto* value = reinterpret_cast<sockaddr_in6*>(&remote);
        value->sin6_family = AF_INET6;
        value->sin6_port = htons(static_cast<uint16_t>(port));
        std::memcpy(&value->sin6_addr, addressBytes.data(), 16);
    } else return ZT_RESULT_FATAL_ERROR_INTERNAL;
    return fromHandle(handle)->processWire(remote, packetBytes);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nova_zerotier_core_NativeCore_processVirtualFrame(JNIEnv* env, jobject, jlong handle,
                                                            jlong networkId, jlong sourceMac,
                                                            jlong destinationMac, jint etherType,
                                                            jint vlanId, jbyteArray payload)
{
    if (!handle) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    std::vector<uint8_t> bytes;
    byteArray(env, payload, bytes);
    return fromHandle(handle)->processFrame(static_cast<uint64_t>(networkId),
            static_cast<uint64_t>(sourceMac), static_cast<uint64_t>(destinationMac),
            static_cast<unsigned int>(etherType), static_cast<unsigned int>(vlanId), bytes);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nova_zerotier_core_NativeCore_multicastSubscribe(JNIEnv* env, jobject, jlong handle,
                                                          jlong networkId, jlong mac, jlong adi)
{
    if (!handle) return ZT_RESULT_FATAL_ERROR_INTERNAL;
    return fromHandle(handle)->multicastSubscribe(static_cast<uint64_t>(networkId),
            static_cast<uint64_t>(mac), static_cast<unsigned long>(adi));
}
