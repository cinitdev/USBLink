package io.usblink.mobile;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class DesktopOwnerTestMain {
    public static void main(String[] args)throws Exception {
        byte[] key=new byte[32];Arrays.fill(key,(byte)7);
        InetAddress source=InetAddress.getByName("127.0.0.2");
        if(args.length==2 && args[0].equals("--query-transport")) {
            if(DesktopOwner.queryTransport(InetAddress.getLoopbackAddress(),source,Integer.parseInt(args[1]),key,51001,3240)==null)
                throw new AssertionError("Rust desktop transport response was rejected");
            System.out.println("PASS: Rust desktop / Java module transport protocol");return;
        }
        if(args.length==2 && args[0].equals("--query")) {
            if(DesktopOwner.query(InetAddress.getLoopbackAddress(),source,Integer.parseInt(args[1]),key)==null)
                throw new AssertionError("Rust desktop owner response was rejected");
            System.out.println("PASS: Rust desktop / Java module owner protocol");return;
        }
        for(String mode:new String[]{"valid","wrong-key","replayed","invalid-phase","legacy"}) {
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
                ExecutorService executor=Executors.newSingleThreadExecutor();
                try {
                    Future<?> responder=executor.submit(()->{
                        try(Socket client=server.accept()) {
                            if(!client.getInetAddress().equals(source))throw new AssertionError("Probe must bind the supplied mesh source");
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
                    boolean found=DesktopOwner.query(InetAddress.getLoopbackAddress(),source,server.getLocalPort(),key)!=null;
                    responder.get(4,TimeUnit.SECONDS);
                    if(found!=mode.equals("valid"))throw new AssertionError(mode);
                }finally{executor.shutdownNow();}
            }
        }
        System.out.println("PASS: 5 desktop owner authentication cases");
        transportAuthentication(key,source);
        sourceFallback(key,source);
    }
    private static void transportAuthentication(byte[] key,InetAddress source)throws Exception {
        for(String mode:new String[]{"valid","wrong-key","replayed","wrong-ports","invalid-phase","invalid-state","legacy"}) {
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
                ExecutorService executor=Executors.newSingleThreadExecutor();
                try {
                    Future<?> responder=executor.submit(()->{
                        try(Socket client=server.accept()) {
                            if(!client.getInetAddress().equals(source))throw new AssertionError("Probe must bind the supplied mesh source");
                            client.setSoTimeout(3000);DataInputStream in=new DataInputStream(client.getInputStream());
                            byte[] magic=new byte[8],nonce=new byte[16],ports=new byte[4],auth=new byte[32];
                            in.readFully(magic);in.readFully(nonce);in.readFully(ports);in.readFully(auth);
                            if(!Arrays.equals(magic,DesktopOwner.TRANSPORT_MAGIC) || !MessageDigest.isEqual(auth,
                                PresenceServer.tag(key,DesktopOwner.TRANSPORT_REQUEST,nonce,ports)))throw new AssertionError("Transport request auth");
                            if(!Arrays.equals(ports,new byte[]{(byte)199,57,12,(byte)168}))throw new AssertionError("Exact old TCP ports");
                            if(mode.equals("legacy"))return;
                            byte[] payload=new byte[18];payload[0]=42;payload[16]=(byte)(mode.equals("invalid-phase")?9:1);payload[17]=(byte)(mode.equals("invalid-state")?3:2);
                            if(mode.equals("replayed"))nonce[0]^=1;
                            if(mode.equals("wrong-ports"))ports[0]^=1;
                            byte[] signed=new byte[22];System.arraycopy(ports,0,signed,0,4);System.arraycopy(payload,0,signed,4,18);
                            byte[] tag=PresenceServer.tag(mode.equals("wrong-key")?new byte[32]:key,DesktopOwner.TRANSPORT_RESPONSE,nonce,signed);
                            client.getOutputStream().write(payload);client.getOutputStream().write(tag);
                        }catch(IOException e){throw new UncheckedIOException(e);}
                    });
                    boolean found=DesktopOwner.queryTransport(InetAddress.getLoopbackAddress(),source,server.getLocalPort(),key,51001,3240)!=null;
                    responder.get(4,TimeUnit.SECONDS);
                    if(found!=mode.equals("valid"))throw new AssertionError(mode);
                }finally{executor.shutdownNow();}
            }
        }
        System.out.println("PASS: 7 transport authentication cases");
    }
    private static void sourceFallback(byte[] key,InetAddress source)throws Exception {
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                Future<?> responder=executor.submit(()->{
                    try {
                        // Old desktop rejects the new query. The legacy query
                        // must still bind the mesh address, never the VPN TUN.
                        try(Socket first=server.accept()) {
                            if(!first.getInetAddress().equals(source))throw new AssertionError("Modern source binding");
                            first.setSoTimeout(2000);new DataInputStream(first.getInputStream()).readFully(new byte[60]);
                        }
                        try(Socket second=server.accept()) {
                            if(!second.getInetAddress().equals(source))throw new AssertionError("Fallback source binding");
                            second.setSoTimeout(2000);byte[] request=new byte[56];new DataInputStream(second.getInputStream()).readFully(request);
                            byte[] nonce=Arrays.copyOfRange(request,8,24),payload=new byte[17];payload[0]=1;payload[16]=1;
                            second.getOutputStream().write(payload);second.getOutputStream().write(PresenceServer.tag(key,DesktopOwner.RESPONSE,nonce,payload));
                        }
                    }catch(IOException e){throw new UncheckedIOException(e);}
                });
                if(DesktopOwner.query(InetAddress.getLoopbackAddress(),source,server.getLocalPort(),key,51001,3240)==null)
                    throw new AssertionError("Bound legacy fallback must authenticate");
                responder.get(4,TimeUnit.SECONDS);
            }finally{executor.shutdownNow();}
            for(InetAddress missing:new InetAddress[]{null,InetAddress.getByName("0.0.0.0"),InetAddress.getByName("192.0.2.123")}) {
                if(DesktopOwner.query(InetAddress.getLoopbackAddress(),missing,server.getLocalPort(),key,51001,3240)!=null)
                    throw new AssertionError("Invalid or lost source must stay unknown");
            }
            server.setSoTimeout(150);
            try(Socket unexpected=server.accept()){throw new AssertionError("Must not fall back to an unbound connection");}
            catch(SocketTimeoutException expected){}
        }
        System.out.println("PASS: bound-source fallback and missing-source checks");
    }
}
