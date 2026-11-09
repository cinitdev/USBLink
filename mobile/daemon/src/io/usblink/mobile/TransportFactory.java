package io.usblink.mobile;

/** The TCP package does not contain USB descriptors or server code. */
final class TransportFactory {
    static final boolean USB=false;
    static SharingTransport create(Platform platform){return new ProxyPool();}
    static SharingTransport create(Platform platform,AdbHistory history){return new ProxyPool(history);}
}
