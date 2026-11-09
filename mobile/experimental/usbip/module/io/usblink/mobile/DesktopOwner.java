package io.usblink.mobile;

import io.usblink.mobile.usbip.UsbIpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

/** Read-only paired application observation; a timeout is never proof of exit. */
final class DesktopOwner {
    static final byte[] MAGIC="USBLINK5".getBytes(StandardCharsets.US_ASCII);
    static final byte[] REQUEST="USBLink desktop owner request v1\0".getBytes(StandardCharsets.US_ASCII);
    static final byte[] RESPONSE="USBLink desktop owner response v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM=new SecureRandom();

    static UsbIpServer.Owner query(InetAddress peer,int port,byte[] key) {
        if(key==null || key.length!=32)return null;
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(peer,port),900);
            socket.setSoTimeout(1500);
            byte[] nonce=new byte[16];RANDOM.nextBytes(nonce);
            OutputStream out=socket.getOutputStream();
            out.write(MAGIC);out.write(nonce);out.write(PresenceServer.tag(key,REQUEST,nonce,new byte[0]));
            DataInputStream in=new DataInputStream(socket.getInputStream());
            byte[] payload=new byte[17],tag=new byte[32];in.readFully(payload);in.readFully(tag);
            if(!MessageDigest.isEqual(tag,PresenceServer.tag(key,RESPONSE,nonce,payload)))return null;
            int phase=payload[16]&255;
            if(phase>3)return null;
            return new UsbIpServer.Owner(Arrays.copyOf(payload,16),phase);
        }catch(IOException | RuntimeException ignored){return null;}
    }
}
