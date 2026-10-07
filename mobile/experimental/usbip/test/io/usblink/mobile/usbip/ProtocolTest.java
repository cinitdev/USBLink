package io.usblink.mobile.usbip;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Wire tests use independent byte-level requests and a fake adbd; no Windows driver is touched. */
public final class ProtocolTest {
    private static final InetAddress LOCAL=InetAddress.getLoopbackAddress();
    private static int checked;
    public static void main(String[] args)throws Exception {
        try(ServerSocket adb=new ServerSocket(0,2,LOCAL);
            UsbIpServer server=new UsbIpServer(LOCAL,0,LOCAL,
                new AdbUsbDevice("test-phone","Test Phone","01234567-89ab-cdef-0123-456789abcdef"),
                ()->new Socket(LOCAL,adb.getLocalPort()),line->{})) {
            list(server.port());badImport(server.port());
            if(server.firstImportNanos()!=-1)throw new AssertionError("Read-only preparation must not start the transfer lease");
            try(Socket client=connect(server.port())) {
                importDevice(client);
                if(server.firstImportNanos()<0)throw new AssertionError("The accepted import must start its own transfer lease");
                try(Socket device=adb.accept()) {
                    device.setSoTimeout(3000);
                    badImportBusy(server.port());
                    DataInputStream in=new DataInputStream(client.getInputStream());
                    OutputStream out=client.getOutputStream();
                    // Fragment the entire request across TCP writes: readFully must reassemble it.
                    send(out,submit(1,1,0,18,setup(0x80,6,0x100,0,18)),true);
                    Reply reply=reply(in,true);eq(reply.status,0);eq(reply.data.length,18);
                    eq(reply.data[0]&255,18);eq(reply.data[1]&255,1);
                    send(out,submit(2,1,0,255,setup(0x80,6,0x200,0,255)),false);
                    reply=reply(in,true);eq(reply.length,32);eq(reply.data[20]&255,0x81);eq(reply.data[27]&255,2);
                    send(out,submit(3,1,0,20,setup(0x80,6,0x3ee,0,20)),false);eq(reply(in,true).status,-32);
                    send(out,submit(4,0,0,0,setup(0,9,1,0,0)),false);eq(reply(in,false).status,0);
                    // Unlink a pending bulk read. A successful unlink must not also submit a completion.
                    send(out,submit(5,1,1,24,new byte[8]),false);
                    ByteBuffer unlink=header(2,6,0,0);unlink.putInt(5);send(out,unlink.array(),false);
                    reply=reply(in,false);eq(reply.command,4);eq(reply.status,-104);
                    send(out,submit(7,1,1,0,new byte[8]),false);eq(reply(in,true).length,0);
                    // Transparent AUTH packet and payload: no synthesized authorization or local key.
                    byte[] authentication=packet(0x48545541,1,0,new byte[]{9,8,7,6});
                    byte[] request=submit(8,0,2,authentication.length,new byte[8]);
                    out.write(request);out.write(authentication);eq(reply(in,false).length,authentication.length);
                    byte[] received=new byte[authentication.length];new DataInputStream(device.getInputStream()).readFully(received);
                    equal(received,authentication);
                    // Reverse traffic: fragmented TCP header/payload, exact 512 bytes, then next packet.
                    byte[] payload=new byte[512];new Random(3).nextBytes(payload);
                    byte[] message=packet(0x45545257,1,1,payload);
                    send(out,submit(9,1,1,24,new byte[8]),false);
                    send(device.getOutputStream(),message,true);
                    equal(reply(in,true).data,Arrays.copyOf(message,24));
                    send(out,submit(10,1,1,256,new byte[8]),false);equal(reply(in,true).data,Arrays.copyOf(payload,256));
                    send(out,submit(11,1,1,1024,new byte[8]),false);equal(reply(in,true).data,Arrays.copyOfRange(payload,256,512));
                    // Sustained transfer with independently checked content; handles all 24+payload boundaries.
                    for(int i=0;i<64;i++) {
                        byte[] block=new byte[64*1024];Arrays.fill(block,(byte)i);
                        device.getOutputStream().write(packet(0x45545257,i,0,block));
                        send(out,submit(20+i*2,1,1,24,new byte[8]),false);eq(reply(in,true).length,24);
                        send(out,submit(21+i*2,1,1,block.length,new byte[8]),false);equal(reply(in,true).data,block);
                    }
                    // Reject unbounded allocation and terminate the matching backend connection.
                    send(out,submit(999,1,1,Integer.MAX_VALUE,new byte[8]),false);
                    eq(in.read(),-1);eq(device.getInputStream().read(),-1);
                }
            }
        }
        System.out.println("USB/IP wire checks passed: "+checked+"; 4 MiB verified, AUTH, fragmentation, cancellation, bounded input");
    }
    private static Socket connect(int port)throws IOException {Socket s=new Socket(LOCAL,port);s.setSoTimeout(3000);return s;}
    private static void negotiate(OutputStream out,int op)throws IOException {out.write(ByteBuffer.allocate(8).putShort((short)0x111).putShort((short)op).putInt(0).array());}
    private static void list(int port)throws Exception {
        try(Socket socket=connect(port)) {
            negotiate(socket.getOutputStream(),0x8005);DataInputStream in=new DataInputStream(socket.getInputStream());
            eq(in.readUnsignedShort(),0x111);eq(in.readUnsignedShort(),5);eq(in.readInt(),0);eq(in.readInt(),1);
            byte[] record=new byte[316];in.readFully(record);
            eq(ByteBuffer.wrap(record).getInt(288),99);eq(ByteBuffer.wrap(record).getShort(300)&65535,0x18d1);
            eq(record[312]&255,255);eq(record[313]&255,0x42);eq(in.read(),-1);
        }
    }
    private static void badImport(int port)throws Exception {
        try(Socket socket=connect(port)) {
            negotiate(socket.getOutputStream(),0x8003);socket.getOutputStream().write(new byte[32]);
            DataInputStream in=new DataInputStream(socket.getInputStream());in.readInt();eq(in.readInt(),1);
        }
    }
    private static void badImportBusy(int port)throws Exception {
        try(Socket socket=connect(port)) {
            negotiate(socket.getOutputStream(),0x8003);socket.getOutputStream().write(bus());
            DataInputStream in=new DataInputStream(socket.getInputStream());in.readInt();eq(in.readInt(),1);
        }
    }
    private static byte[] bus(){byte[] bus=new byte[32];System.arraycopy("99-1".getBytes(StandardCharsets.US_ASCII),0,bus,0,4);return bus;}
    private static void importDevice(Socket socket)throws Exception {
        negotiate(socket.getOutputStream(),0x8003);socket.getOutputStream().write(bus());
        DataInputStream in=new DataInputStream(socket.getInputStream());eq(in.readUnsignedShort(),0x111);eq(in.readUnsignedShort(),3);eq(in.readInt(),0);
        byte[] record=new byte[312];in.readFully(record);
    }
    private static ByteBuffer header(int cmd,int seq,int direction,int ep) {
        return ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN).putInt(cmd).putInt(seq).putInt(AdbUsbDevice.DEVICE_ID).putInt(direction).putInt(ep);
    }
    private static byte[] submit(int seq,int direction,int ep,int size,byte[] setup) {
        return header(1,seq,direction,ep).putInt(0).putInt(size).putInt(0).putInt(0).putInt(0).put(setup).array();
    }
    private static byte[] setup(int type,int req,int value,int index,int size) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put((byte)type).put((byte)req).putShort((short)value).putShort((short)index).putShort((short)size).array();
    }
    private static byte[] packet(int command,int a,int b,byte[] payload) {
        int checksum=0;for(byte value:payload)checksum+=value&255;
        return ByteBuffer.allocate(24+payload.length).order(ByteOrder.LITTLE_ENDIAN).putInt(command).putInt(a).putInt(b)
            .putInt(payload.length).putInt(checksum).putInt(command^0xffffffff).put(payload).array();
    }
    private static void send(OutputStream out,byte[] bytes,boolean fragment)throws IOException {
        if(fragment){for(int i=0;i<bytes.length;i+=7)out.write(bytes,i,Math.min(7,bytes.length-i));}
        else out.write(bytes);
    }
    private static Reply reply(DataInputStream in,boolean data)throws IOException {
        byte[] header=new byte[48];in.readFully(header);ByteBuffer b=ByteBuffer.wrap(header);
        Reply r=new Reply();r.command=b.getInt();r.seq=b.getInt();eq(b.getInt(),0);eq(b.getInt(),0);eq(b.getInt(),0);
        r.status=b.getInt();r.length=b.getInt();r.data=new byte[data?r.length:0];in.readFully(r.data);return r;
    }
    private static final class Reply {int command,seq,status,length;byte[] data;}
    private static void eq(int a,int b){checked++;if(a!=b)throw new AssertionError(a+" != "+b);}
    private static void equal(byte[] a,byte[] b){checked++;if(!Arrays.equals(a,b))throw new AssertionError("Byte content mismatch");}
}
