package io.usblink.mobile;

/** Included only by the explicitly selected experimental module build. */
final class TransportFactory {
    static final boolean USB=true;
    static SharingTransport create(Platform platform){return new UsbTransport(platform);}
}
