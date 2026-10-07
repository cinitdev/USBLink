package io.usblink.mobile;

/** The normal package does not contain experimental USB descriptors or server code. */
final class TransportFactory {
    static final boolean USB=false;
    static SharingTransport create(Platform platform){return new ProxyPool();}
}
