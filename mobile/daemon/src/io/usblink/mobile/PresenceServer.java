package io.usblink.mobile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Windows USBLINK3 challenge-response; authenticated observations only, no commands. */
final class PresenceServer implements Closeable {
    static final int OFF=4, READY=5, PREPARING=6, FAILED=7;
    static final int USB_OFF=8, USB_READY=9, USB_PREPARING=10, USB_FAILED=11;
    private static final byte[] MAGIC="USBLINK3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] REQUEST="USBLink presence request v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESPONSE="USBLink presence response v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXPORT_MAGIC="USBLINK4".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXPORT_REQUEST="USBLink USB export request v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXPORT_RESPONSE="USBLink USB export response v1\0".getBytes(StandardCharsets.US_ASCII);
    private final int port;
    private final LongSupplier now;
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,4,15,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"usblink-presence-client");t.setDaemon(true);return t;});
    private volatile Listener current;
    private boolean closed;
    private volatile byte[] exportPayload;
    synchronized void export(Map<String,Object> value){exportPayload=value==null?null:Json.stringify(value).getBytes(StandardCharsets.UTF_8);}
    private static final class Observation {
        final int phase;final long updated;
        Observation(int phase,long updated){this.phase=phase;this.updated=updated;}
    }
    private static final class Listener {
        final ServerSocket socket;final String ip;final byte[] key;
        volatile Observation observation;
        Listener(ServerSocket socket,String ip,byte[] key,Observation observation){this.socket=socket;this.ip=ip;this.key=key.clone();this.observation=observation;}
    }
    PresenceServer(int port){this(port,()->System.nanoTime()/1_000_000);}
    PresenceServer(int port,LongSupplier now){this.port=port;this.now=now;}
    static byte[] key(String secret)throws IOException {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            hash.update("USBLink application presence v1\0".getBytes(StandardCharsets.US_ASCII));
            return hash.digest(secret.getBytes(StandardCharsets.UTF_8));
        }catch(GeneralSecurityException e){throw new IOException("会话认证不可用");}
    }
    static byte[] tag(byte[] key,byte[] domain,byte[] nonce,byte[] payload)throws IOException {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            mac.update(domain);mac.update(nonce);return mac.doFinal(payload);
        }catch(GeneralSecurityException e){throw new IOException("会话认证不可用");}
    }
    synchronized void configure(String ip,byte[] key,int phase)throws IOException {
        if(closed)throw new IOException("会话服务已停止");
        if(phase<OFF || phase>USB_FAILED)throw new IllegalArgumentException("会话状态无效");
        if(key==null || key.length!=32 || ip==null || ip.equals("0.0.0.0") || !ip.matches("[0-9.]+"))throw new IOException("会话网络地址无效");
        if(current!=null && current.ip.equals(ip) && Arrays.equals(current.key,key) && !current.socket.isClosed()) {phase(phase);return;}
        stop();ServerSocket socket=new ServerSocket();
        try {socket.setReuseAddress(true);socket.bind(new InetSocketAddress(InetAddress.getByName(ip),port),8);}
        catch(IOException e){try{socket.close();}catch(IOException ignored){}throw new IOException("电脑在线检测端口无法启动，请检查网络地址和端口占用");}
        Listener listener=new Listener(socket,ip,key,new Observation(phase,now.getAsLong()));current=listener;
        Thread thread=new Thread(()->accept(listener),"usblink-presence");thread.setDaemon(true);thread.start();
    }
    synchronized void phase(int phase) {
        if(phase<OFF || phase>USB_FAILED)throw new IllegalArgumentException("会话状态无效");
        if(phase!=USB_READY)exportPayload=null;
        if(current!=null)current.observation=new Observation(phase,now.getAsLong());
    }
    private void accept(Listener listener) {
        try {
            while(current==listener) {
                Socket socket=listener.socket.accept();clients.add(socket);
                if(current!=listener){discard(socket);continue;}
                try{workers.execute(()->respond(socket,listener));}catch(RejectedExecutionException e){discard(socket);}
            }
        }catch(IOException ignored){ /* Closing or rebinding revokes the listener. */ }
        finally{try{listener.socket.close();}catch(IOException ignored){}}
    }
    private void respond(Socket socket,Listener listener) {
        try {
            socket.setSoTimeout(1500);DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            byte[] magic=new byte[8],nonce=new byte[16],auth=new byte[32];
            in.readFully(magic);boolean usb=Arrays.equals(magic,EXPORT_MAGIC);
            if(!usb && !Arrays.equals(magic,MAGIC))return;
            in.readFully(nonce);in.readFully(auth);
            if(!MessageDigest.isEqual(auth,tag(listener.key,usb?EXPORT_REQUEST:REQUEST,nonce,new byte[0])))return;
            Observation observation=listener.observation;
            if(current!=listener || now.getAsLong()-observation.updated>10_000)return;
            byte[] payload=usb?exportPayload:new byte[]{(byte)observation.phase};
            if(usb && (observation.phase!=USB_READY || payload==null || payload.length>2048))return;
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            if(usb)out.writeInt(payload.length);
            out.write(payload);out.write(tag(listener.key,usb?EXPORT_RESPONSE:RESPONSE,nonce,payload));out.flush();
        }catch(IOException ignored){ /* Never echo requests, network keys, or protocol errors. */ }
        finally{discard(socket);}
    }
    private void discard(Socket socket){clients.remove(socket);try{socket.close();}catch(IOException ignored){}}
    synchronized boolean active(){return current!=null && !current.socket.isClosed() && now.getAsLong()-current.observation.updated<=10_000;}
    synchronized int boundPort(){return active()?current.socket.getLocalPort():0;}
    synchronized void stop() {
        Listener old=current;current=null;exportPayload=null;
        if(old!=null)try{old.socket.close();}catch(IOException ignored){}
        for(Socket socket:clients)discard(socket);
    }
    public synchronized void close(){closed=true;stop();workers.shutdownNow();}
}
