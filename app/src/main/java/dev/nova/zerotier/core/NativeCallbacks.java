package dev.nova.zerotier.core;

public interface NativeCallbacks {
    void onWirePacket(byte[] address, int port, byte[] packet);
    void onVirtualFrame(long networkId, long sourceMac, long destinationMac, int etherType, int vlanId, byte[] payload);
    int onNetworkConfig(long networkId, int operation, int status, String configJson);
    void onNodeEvent(int eventType, String message);
}
