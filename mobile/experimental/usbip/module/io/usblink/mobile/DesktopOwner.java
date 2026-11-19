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
    static final byte[] TRANSPORT_MAGIC="USBLINK6".getBytes(StandardCharsets.US_ASCII);
    static final byte[] TRANSPORT_REQUEST="USBLink desktop transport request v1\0".getBytes(StandardCharsets.US_ASCII);
    static final byte[] TRANSPORT_RESPONSE="USBLink desktop transport response v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM=new SecureRandom();

    static UsbIpServer.Owner query(InetAddress peer,InetAddress local,int port,byte[] key,int clientPort,int serverPort) {
        UsbIpServer.Owner current=queryTransport(peer,local,port,key,clientPort,serverPort);
        // Older desktops still report process identity, never transport absence.
        return current!=null?current:query(peer,local,port,key);
    }

    private static void bindSource(Socket socket,InetAddress local)throws IOException {
        if(local==null || local.isAnyLocalAddress() || local.isMulticastAddress())throw new IOException("Invalid shared source address");
        // An unbound root socket may select an Android VPN's TUN address. Use
        // the accepted USB connection's mesh address, including legacy probes.
        // If the address disappeared, remain Unknown; never fall back to VPN.
        socket.bind(new InetSocketAddress(local,0));socket.setTcpNoDelay(true);
    }

    static UsbIpServer.Owner queryTransport(InetAddress peer,InetAddress local,int port,byte[] key,int clientPort,int serverPort) {
        if(key==null || key.length!=32 || clientPort<1 || clientPort>65535 || serverPort<1 || serverPort>65535)return null;
        try(Socket socket=new Socket()) {
            bindSource(socket,local);
            socket.connect(new InetSocketAddress(peer,port),900);socket.setSoTimeout(1500);
            byte[] nonce=new byte[16];RANDOM.nextBytes(nonce);
            byte[] ports=java.nio.ByteBuffer.allocate(4).putShort((short)clientPort).putShort((short)serverPort).array();
            OutputStream out=socket.getOutputStream();
            out.write(TRANSPORT_MAGIC);out.write(nonce);out.write(ports);out.write(PresenceServer.tag(key,TRANSPORT_REQUEST,nonce,ports));
            DataInputStream in=new DataInputStream(socket.getInputStream());
            byte[] payload=new byte[18],tag=new byte[32];in.readFully(payload);in.readFully(tag);
            byte[] signed=new byte[22];System.arraycopy(ports,0,signed,0,4);System.arraycopy(payload,0,signed,4,18);
            if(!MessageDigest.isEqual(tag,PresenceServer.tag(key,TRANSPORT_RESPONSE,nonce,signed)))return null;
            int phase=payload[16]&255,transport=payload[17]&255;
            if(phase>3 || transport>2)return null;
            return new UsbIpServer.Owner(Arrays.copyOf(payload,16),phase,transport);
        }catch(IOException | RuntimeException ignored){return null;}
    }

    static UsbIpServer.Owner query(InetAddress peer,InetAddress local,int port,byte[] key) {
        if(key==null || key.length!=32)return null;
        try(Socket socket=new Socket()) {
            bindSource(socket,local);
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
