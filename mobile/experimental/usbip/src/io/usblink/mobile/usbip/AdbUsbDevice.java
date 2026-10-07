package io.usblink.mobile.usbip;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Experimental ADB-only device. This does not export the phone's physical gadget or MTP. */
public final class AdbUsbDevice {
    public static final String BUS_ID = "99-1";
    public static final int DEVICE_ID = (99 << 16) | 1;
    public static final int MAX_TRANSFER = 1024 * 1024;
    // Existing Android debug identity, for this local interoperability experiment only.
    // A distributable product needs its own USB identity and reviewed driver binding.
    public static final int VID = 0x18d1, PID = 0x4ee7;
    private final String serial, product, path;
    private int configuration;

    public AdbUsbDevice(String serial, String model, String session) {
        if (!serial.matches("[A-Za-z0-9_-]{1,64}") || !session.matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Invalid experimental device identity");
        this.serial = "USBLink-" + serial;
        this.product = model;
        this.path = "/usblink/experimental/adb/" + session;
    }
    public byte[] record() {
        ByteBuffer b = ByteBuffer.allocate(312).order(ByteOrder.BIG_ENDIAN);
        fixed(b, path, 256); fixed(b, BUS_ID, 32);
        b.putInt(99).putInt(1).putInt(3); // USB_SPEED_HIGH, 512-byte bulk packets.
        b.putShort((short) VID).putShort((short) PID).putShort((short) 0x0100);
        b.put(new byte[]{0, 0, 0, 1, 1, 1});
        return b.array();
    }
    public String path(){return path;}
    private static void fixed(ByteBuffer b, String s, int size) {
        byte[] text = s.getBytes(StandardCharsets.US_ASCII);
        if (text.length >= size) throw new IllegalArgumentException("Identity too long");
        b.put(text); b.position(b.position() + size - text.length);
    }
    public synchronized void reset() { configuration = 0; }
    public synchronized boolean configured() { return configuration == 1; }
    /** Null means STALL. Never claim a request succeeded without implementing its effect. */
    public synchronized byte[] control(byte[] setup) {
        ByteBuffer b = ByteBuffer.wrap(setup).order(ByteOrder.LITTLE_ENDIAN);
        int type = b.get() & 255, request = b.get() & 255;
        int value = b.getShort() & 65535, index = b.getShort() & 65535, length = b.getShort() & 65535;
        byte[] answer = null;
        if (type == 0x80 && request == 6) answer = descriptor(value >> 8, value & 255);
        else if (type == 0 && request == 9 && index == 0 && length == 0 && value <= 1) {
            configuration = value; answer = new byte[0];
        } else if (type == 0 && request == 5 && value <= 127 && index == 0 && length == 0) answer = new byte[0];
        else if (type == 0x80 && request == 8 && value == 0 && index == 0) answer = new byte[]{(byte) configuration};
        else if (request == 0 && value == 0 && ((type == 0x80 && index == 0) ||
                (type == 0x81 && index == 0) || (type == 0x82 && (index == 0 || index == 0x81 || index == 2)))) answer = new byte[]{0, 0};
        else if (type == 0x81 && request == 10 && value == 0 && index == 0 && configured()) answer = new byte[]{0};
        else if (type == 1 && request == 11 && value == 0 && index == 0 && length == 0 && configured()) answer = new byte[0];
        else if (type == 2 && request == 1 && value == 0 && (index == 0x81 || index == 2) && length == 0) answer = new byte[0];
        if (answer == null) return null;
        return Arrays.copyOf(answer, Math.min(answer.length, length));
    }
    private byte[] descriptor(int type, int index) {
        if (type == 1 && index == 0) return new byte[]{18,1,0,2,0,0,0,64,
            (byte) VID,(byte)(VID>>8),(byte)PID,(byte)(PID>>8),0,1,1,2,3,1};
        if ((type == 2 || type == 7) && index == 0) return new byte[]{
            9,(byte)type,32,0,1,1,0,(byte)0x80,50,
            9,4,0,0,2,(byte)0xff,0x42,1,0,
            7,5,(byte)0x81,2,(byte)(type==7?64:0),(byte)(type==7?0:2),0,
            7,5,2,2,(byte)(type==7?64:0),(byte)(type==7?0:2),0};
        if (type == 6 && index == 0) return new byte[]{10,6,0,2,0,0,0,64,1,0};
        if (type != 3) return null;
        if (index == 0) return new byte[]{4,3,9,4};
        if (index == 1) return string("USBLink experiment");
        if (index == 2) return string(product);
        if (index == 3) return string(serial);
        return null;
    }
    private static byte[] string(String value) {
        if (value.length() > 100) value = value.substring(0,100);
        byte[] text = value.getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer b = ByteBuffer.allocate(text.length+2);
        b.put((byte)(text.length+2)).put((byte)3).put(text); return b.array();
    }
}
