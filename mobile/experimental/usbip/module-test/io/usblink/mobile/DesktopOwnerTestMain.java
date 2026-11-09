package io.usblink.mobile;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class DesktopOwnerTestMain {
    public static void main(String[] args)throws Exception {
        byte[] key=new byte[32];Arrays.fill(key,(byte)7);
        if(args.length==2 && args[0].equals("--query")) {
            if(DesktopOwner.query(InetAddress.getLoopbackAddress(),Integer.parseInt(args[1]),key)==null)
                throw new AssertionError("Rust desktop owner response was rejected");
            System.out.println("PASS: Rust desktop / Java module owner protocol");return;
        }
        for(String mode:new String[]{"valid","wrong-key","replayed","invalid-phase","legacy"}) {
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
                ExecutorService executor=Executors.newSingleThreadExecutor();
                try {
                    Future<?> responder=executor.submit(()->{
                        try(Socket client=server.accept()) {
                            client.setSoTimeout(3000);DataInputStream in=new DataInputStream(client.getInputStream());
                            byte[] magic=new byte[8],nonce=new byte[16],auth=new byte[32];
                            in.readFully(magic);in.readFully(nonce);in.readFully(auth);
                            if(!Arrays.equals(magic,DesktopOwner.MAGIC) || !MessageDigest.isEqual(auth,
                                PresenceServer.tag(key,DesktopOwner.REQUEST,nonce,new byte[0])))throw new AssertionError("Request auth");
                            if(mode.equals("legacy"))return;
                            byte[] payload=new byte[17];payload[0]=42;payload[16]=(byte)(mode.equals("invalid-phase")?9:1);
                            if(mode.equals("replayed"))nonce[0]^=1;
                            byte[] tag=PresenceServer.tag(mode.equals("wrong-key")?new byte[32]:key,DesktopOwner.RESPONSE,nonce,payload);
                            client.getOutputStream().write(payload);client.getOutputStream().write(tag);
                        }catch(IOException e){throw new UncheckedIOException(e);}
                    });
                    boolean found=DesktopOwner.query(InetAddress.getLoopbackAddress(),server.getLocalPort(),key)!=null;
                    responder.get(4,TimeUnit.SECONDS);
                    if(found!=mode.equals("valid"))throw new AssertionError(mode);
                }finally{executor.shutdownNow();}
            }
        }
        System.out.println("PASS: 5 desktop owner authentication cases");
    }
}
