package nova.lunavane.core

internal object NativeCore {
    init {
        System.loadLibrary("zerotiernova")
    }

    external fun create(callbacks: NativeCallbacks, stateDirectory: String): Long
    external fun destroy(handle: Long)
    external fun join(handle: Long, networkId: String): Int
    external fun leave(handle: Long, networkId: String): Int
    external fun orbit(handle: Long, moonWorldId: String, moonSeed: String): Int
    external fun deorbit(handle: Long, moonWorldId: String): Int
    external fun processWirePacket(handle: Long, address: ByteArray, port: Int, packet: ByteArray): Int
    external fun processVirtualFrame(
        handle: Long,
        networkId: Long,
        sourceMac: Long,
        destinationMac: Long,
        etherType: Int,
        vlanId: Int,
        payload: ByteArray,
    ): Int
    external fun multicastSubscribe(handle: Long, networkId: Long, mac: Long, adi: Long): Int
    external fun peers(handle: Long): Array<String>?
    external fun nodeAddress(handle: Long): String?
    external fun moonFileStatus(handle: Long, moonWorldId: String): Int
}
