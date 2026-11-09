package io.usblink.mobile.usbip;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Simulates an updater killing the UI while the old USB transport stays open. */
public final class OwnerLifecycleTest {
    private static final InetAddress LOCAL=InetAddress.getLoopbackAddress();
    private static int checks;
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    private static UsbIpServer.Owner owner(int generation,int phase) {
        byte[] id=new byte[16];id[0]=(byte)generation;return new UsbIpServer.Owner(id,phase);
    }
    private static Socket request(int port,boolean success)throws Exception {
        Socket socket=new Socket(LOCAL,port);socket.setSoTimeout(5000);
        ByteBuffer request=ByteBuffer.allocate(40).putShort((short)0x111).putShort((short)0x8003).putInt(0);
        request.put("99-1".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(request.array());
        DataInputStream in=new DataInputStream(socket.getInputStream());in.readInt();
        int status=in.readInt();
        if(success){check(status==0,"Single manual import must succeed");in.readFully(new byte[312]);}
        else check(status!=0,"A valid current owner must retain exclusive import");
        return socket;
    }
    private static void closed(Socket socket)throws Exception {
        socket.setSoTimeout(3000);
        try{check(socket.getInputStream().read()==-1,"Old socket is closed");}
        catch(SocketException e){check(true,"Old socket is reset");}
    }
    public static void main(String[] args)throws Exception {
        AtomicReference<UsbIpServer.Owner> observation=new AtomicReference<>(owner(1,1));
        AtomicReference<String> reason=new AtomicReference<>();
        try(ServerSocket backend=new ServerSocket(0,4,LOCAL);
            UsbIpServer server=new UsbIpServer(LOCAL,0,peer->true,
                new AdbUsbDevice("test-phone","Test Phone",UUID.randomUUID().toString()),
                ()->new Socket(LOCAL,backend.getLocalPort()),line->{},
                event->reason.set(event.get("code").toString()),peer->bytes->{},peer->observation.get());
            Socket old=request(server.port(),true);Socket oldBackend=backend.accept()) {
            long until=System.nanoTime()+1_000_000_000L;
            while(server.sessions().isEmpty() && System.nanoTime()<until)Thread.sleep(5);
            String original=server.sessions().get(0).get("id").toString();
            // Same app, unknown/timeout, replacement still starting or failed:
            // none is evidence that a healthy idle mount can be displaced.
            for(UsbIpServer.Owner observed:new UsbIpServer.Owner[]{owner(1,1),null,owner(2,0),owner(2,3)}) {
                observation.set(observed);
                try(Socket rejected=request(server.port(),false)) {
                    check(server.sessions().get(0).get("id").equals(original),"Keep the exact existing session");
                }
            }
            observation.set(owner(2,1));
            // The old transport is deliberately left open. This is ONE new
            // import, not a failed import followed by an automatic retry.
            try(Socket replacement=request(server.port(),true);Socket replacementBackend=backend.accept()) {
                closed(old);closed(oldBackend);
                check("DESKTOP_REPLACED".equals(reason.get()),"Record confirmed app replacement");
                until=System.nanoTime()+1_000_000_000L;
                while(server.sessions().isEmpty() && System.nanoTime()<until)Thread.sleep(5);
                check(!server.sessions().get(0).get("id").equals(original),"New import has its own session");
                try(Socket rejected=request(server.port(),false)){}
                observation.set(owner(2,2));
                // Background observation also releases an explicitly closing
                // owner without needing a second client or any attach command.
                until=System.nanoTime()+8_000_000_000L;
                while(!server.sessions().isEmpty() && System.nanoTime()<until)Thread.sleep(10);
                check(server.sessions().isEmpty(),"Closing application releases session in background");
                closed(replacement);closed(replacementBackend);
                check(server.active(),"Sharing stays enabled and ready for a manual connection");
            }
        }
        System.out.println("PASS: "+checks+" desktop replacement lifecycle checks");
    }
}
