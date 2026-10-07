package io.usblink.mobile;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
    private static final String SOCKET="usblink.control.v1";
    private static final int MAX_REQUEST=16384;
    public static void main(String[] args) {
        try {
            if(Process.myUid()!=0)throw new IOException("请从 APatch 或 KernelSU 模块 WebUI 启动，需要 Root 权限");
            if(args.length==3 && args[0].equals("serve"))serve(Paths.get(args[1]),Paths.get(args[2]));
            else if(args.length==2 && args[0].equals("restore-adb"))restoreAdb(Paths.get(args[1]));
            else if((args.length==2 || args.length==3) && args[0].equals("ctl"))client(args[1],args.length==3?args[2]:"e30=");
            else throw new IOException("命令参数无效");
        }catch(Exception e) {
            System.out.println(Json.stringify(Json.map("ok",false,"error",safeError(e))));
            System.exit(1);
        }
    }
    private static void serve(Path module,Path state) throws Exception {
        module=module.toRealPath();
        Files.createDirectories(state);state=state.toRealPath();Os.chmod(state.toString(),0700);
        try(FileChannel lockFile=FileChannel.open(state.resolve("daemon.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock lock=lockFile.tryLock();if(lock==null)throw new IOException("控制服务已经运行");
            Platform platform=new AndroidPlatform(state);
            try(LocalServerSocket server=new LocalServerSocket(SOCKET);
                StatusSnapshot snapshot=new StatusSnapshot(state,Process.myPid(),selfStartTicks(),
                    new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),StandardCharsets.US_ASCII).trim(),
                    ()->android.os.SystemClock.elapsedRealtime()/1000);
                Controller controller=new Controller(platform,new MeshManager(state,module,Json.string(platform.device(),"name")),new SharingStore(state,TransportFactory.USB?3:2),TransportFactory.create(platform))) {
                ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"usblink-state");t.setDaemon(true);return t;});
                Runtime.getRuntime().addShutdownHook(new Thread(()->{try{snapshot.close();}catch(IOException ignored){}controller.close();scheduler.shutdownNow();},"usblink-shutdown"));
                scheduler.scheduleWithFixedDelay(()->{
                    synchronized(controller){controller.tick();publish(snapshot,controller);}
                },0,2,TimeUnit.SECONDS);
                try {
                    while(true) {
                        try(LocalSocket socket=server.accept()) {
                            if(socket.getPeerCredentials().getUid()!=0)continue;
                            socket.setSoTimeout(8000);
                            Map<String,Object> response;
                            try {
                                Map<String,Object> request=Json.object(Json.parse(readLine(socket.getInputStream(),MAX_REQUEST)));
                                String action=Json.string(request,"action");
                                Map<String,Object> input=Json.object(request.get("input"));
                                synchronized(controller) {
                                    // A second WebUI must not see the pre-operation state as confirmed.
                                    if(!action.equals("status") && !action.equals("pairing-code"))snapshot.invalidate();
                                    try{response=Json.map("ok",true,"data",controller.handle(action,input));}
                                    finally{publish(snapshot,controller);}
                                }
                            }catch(Exception e) {response=Json.map("ok",false,"error",safeError(e));}
                            writeLine(socket.getOutputStream(),Json.stringify(response));
                        }catch(IOException ignored) { /* A cancelled WebUI request must not kill the control service. */ }
                    }
                }finally {scheduler.shutdownNow();}
            }finally {lock.release();}
        }
    }
    private static String selfStartTicks()throws IOException {
        String stat=new String(Files.readAllBytes(Paths.get("/proc/self/stat")),StandardCharsets.US_ASCII);
        int end=stat.lastIndexOf(") ");
        if(end<0)throw new IOException("无法读取状态进程身份");
        String[] fields=stat.substring(end+2).trim().split("\\s+");
        if(fields.length<20)throw new IOException("无法读取状态进程身份");
        return fields[19];
    }
    private static void publish(StatusSnapshot snapshot,Controller controller) {
        try{snapshot.publish(controller.status());}
        catch(IOException e){try{snapshot.invalidate();}catch(IOException ignored){ /* Reader also enforces expiration. */ }}
    }
    private static void restoreAdb(Path state)throws Exception {
        if(!state.toAbsolutePath().normalize().equals(Paths.get("/data/adb/usblink")))throw new IOException("恢复目录无效");
        if(!Files.exists(state.resolve("adbd-tcp.json")))return;
        try(FileChannel file=FileChannel.open(state.resolve("daemon.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock lock=file.tryLock();if(lock==null)throw new IOException("控制服务尚未退出，暂不恢复调试配置");
            try{new AndroidPlatform(state).stopTcp();}finally{lock.release();}
        }
    }
    private static void client(String action,String encoded) throws Exception {
        if(!action.matches("[a-z-]{1,40}") || encoded.length()>12000)throw new IOException("请求格式无效");
        Map<String,Object> input;
        try {input=Json.object(Json.parse(new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8)));}
        catch(IllegalArgumentException e) {throw new IOException("请求数据无效");}
        try(LocalSocket socket=new LocalSocket()) {
            try {socket.connect(new LocalSocketAddress(SOCKET,LocalSocketAddress.Namespace.ABSTRACT));}
            catch(IOException e) {throw new IOException("模块控制服务未运行，请在 APatch 模块页点击操作启动");}
            if(socket.getPeerCredentials().getUid()!=0)throw new IOException("控制服务身份无效，已拒绝发送配对信息");
            socket.setSoTimeout(25000);
            writeLine(socket.getOutputStream(),Json.stringify(Json.map("action",action,"input",input)));
            System.out.println(readLine(socket.getInputStream(),65536));
        }
    }
    static String readLine(InputStream in,int max) throws IOException {
        InputStream buffered=new BufferedInputStream(in,4096);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        int b;
        while((b=buffered.read())!=-1) {
            if(b=='\n')return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            if(bytes.size()>=max)throw new IOException("请求过大");bytes.write(b);
        }
        throw new IOException("控制连接意外中断");
    }
    private static void writeLine(OutputStream out,String text) throws IOException {out.write((text+"\n").getBytes(StandardCharsets.UTF_8));out.flush();}
    private static String safeError(Exception e) {
        // Only our fixed messages reach the WebUI. Never forward arbitrary subprocess output or profile data.
        String message=e.getMessage();
        if((e instanceof IOException || e instanceof IllegalArgumentException) && message!=null && message.matches("[\\p{IsHan}].*") && message.length()<180)return message;
        return "操作失败，请检查模块状态后重试";
    }
}
