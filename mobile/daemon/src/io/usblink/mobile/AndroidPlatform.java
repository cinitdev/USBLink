package io.usblink.mobile;

import android.os.Build;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class AndroidPlatform implements Platform {
    private final TcpAdb tcp;
    private final AdbRoot adbRoot;
    private final String serial;
    private final DeviceNames names;
    private final SharingStartup startup;
    AndroidPlatform(Path state) {
        String id="";
        if(TransportFactory.USB)try{id=command("/system/bin/getprop","ro.serialno").trim();}catch(IOException ignored){}
        serial=id;
        Map<String,String> properties=new java.util.LinkedHashMap<>();
        for(String key:DeviceNames.MARKET_PROPERTIES) {
            try{String value=command("/system/bin/getprop",key).trim();properties.put(key,value);if(!DeviceNames.clean(value).isEmpty())break;}
            catch(IOException ignored){ /* Read-only naming must not prevent service startup. */ }
        }
        String configured="";
        try{configured=command("/system/bin/settings","get","global","device_name").trim();}catch(IOException ignored){}
        names=new DeviceNames(properties,configured,Build.MODEL);
        TcpFirewall firewall=new TcpFirewall(AndroidPlatform::command);
        startup=new SharingStartup(new SharingStartup.SystemState(){
            public boolean bootCompleted()throws IOException{return command("/system/bin/getprop","sys.boot_completed").trim().equals("1");}
            public String firewallSnapshot()throws IOException{return firewall.snapshot();}
            public long elapsed(){return android.os.SystemClock.elapsedRealtime();}
        });
        tcp=new TcpAdb(state,new TcpAdb.SystemAccess() {
            public String property(String name)throws IOException{return command("/system/bin/getprop",name).trim();}
            public void tcpProperty(String value)throws IOException{command("/system/bin/setprop","service.adb.tcp.port",value);}
            public void restart()throws IOException{command("/system/bin/setprop","ctl.restart","adbd");}
            public boolean listening(int port)throws IOException{return !listenerInodes(port).isEmpty();}
            public boolean ownedListener(int port)throws IOException{return systemOwnsListener(port);}
            public void protect()throws IOException{try{firewall.install();}catch(IOException e){throw new IOException("无法建立 IPv4 / IPv6 调试端口防护，已取消共享");}}
            public void verifyProtection()throws IOException{try{firewall.verify();}catch(IOException e){throw new IOException("调试端口防护已丢失，共享已暂停，请关闭共享重试");}}
            public void maintainProtection()throws IOException{try{firewall.maintain();}catch(IOException e){throw new IOException("调试端口防护无法确认，共享已暂停，请关闭共享后检查系统网络组件");}}
            public void unprotect()throws IOException{firewall.remove();}
            public void requireAuthorization()throws IOException {
                if(!property("ro.adb.secure").equals("1"))throw new IOException("系统未启用 ADB 身份认证，无法安全共享；模块不会关闭认证");
                if(!command("/system/bin/settings","get","global","adb_enabled").trim().equals("1"))throw new IOException("请在开发者选项开启 USB 调试，用于电脑 RSA 授权");
            }
            public String bootId()throws IOException{return new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),StandardCharsets.US_ASCII).trim();}
            public long elapsed(){return android.os.SystemClock.elapsedRealtime();}
            public void pause()throws IOException{try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("等待调试服务恢复已中断");}}
        });
        adbRoot=new AdbRoot(state,new AdbRoot.SystemAccess(){
            public String property(String name)throws IOException{return readProperty(name);}
            public void rootProperty(String value)throws IOException{command("/system/bin/setprop","service.adb.root",value);}
            public void restart()throws IOException{command("/system/bin/setprop","ctl.restart","adbd");}
            public void beforeRestart()throws IOException{tcp.prepareRestart();}
            public void requireAuthorization()throws IOException{
                if(!property("ro.adb.secure").equals("1"))throw new IOException("系统 ADB 认证未启用，未切换权限；模块不会关闭认证");
                if(!command("/system/bin/settings","get","global","adb_enabled").trim().equals("1"))throw new IOException("请先开启普通 USB 调试再切换 ADB Root");
            }
            public String bootId()throws IOException{return new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),StandardCharsets.US_ASCII).trim();}
            public AdbRoot.ProcessState process()throws IOException{return adbdProcess();}
            public boolean ready()throws IOException{return property("sys.boot_completed").equals("1") && property("init.svc.adbd").equals("running");}
            public long elapsed(){return android.os.SystemClock.elapsedRealtime();}
            public void pause()throws IOException{try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("等待 ADB Root 恢复已中断");}}
        });
    }
    @Override public Map<String,Object> device() {
        return Json.map("model",names.model,"name",names.deviceName,"modelCode",names.modelCode,"serial",serial,"androidVersion",Build.VERSION.RELEASE,"sdk",Build.VERSION.SDK_INT,"root",Process.myUid()==0);
    }
    public void recoverTcp()throws IOException{tcp.restore();}
    public void startTcp()throws IOException{tcp.start();}
    public int tcpPort()throws IOException{return tcp.port();}
    public String sharingStartupProblem()throws IOException{return startup.pending();}
    public int maintainTcpPort()throws IOException{return tcp.maintainPort();}
    public void stopTcp()throws IOException{tcp.restore();}
    public void tickAdbRoot(Runnable revoke){adbRoot.tick(revoke);}
    public void setAdbRoot(boolean enabled,Runnable revoke)throws IOException{adbRoot.set(enabled,revoke);}
    public Map<String,Object> adbRootStatus(){return adbRoot.status();}
    public boolean adbRootChanging(){return adbRoot.changing();}
    public boolean adbRootAllowsSharing(){return adbRoot.allowsSharing();}
    public void restoreAdbRoot()throws IOException{adbRoot.restore();}
    // app_process can use the system property reader without starting a subprocess per observation.
    private static String readProperty(String name)throws IOException {
        try{return (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,name);}
        catch(ReflectiveOperationException | RuntimeException e){return command("/system/bin/getprop",name).trim();}
    }
    private static AdbRoot.ProcessState adbdProcess()throws IOException {
        String pid=readProperty("init.svc_debug_pid.adbd");
        if(pid.matches("[0-9]+") && !pid.equals("0")) {
            try{return identifyAdbd(Paths.get("/proc",pid));}catch(IOException e){return null;}
        }
        AdbRoot.ProcessState found=null;
        try(DirectoryStream<Path> processes=Files.newDirectoryStream(Paths.get("/proc"))) {
            for(Path process:processes) {
                if(!process.getFileName().toString().matches("[0-9]+"))continue;
                AdbRoot.ProcessState candidate;
                try{candidate=identifyAdbd(process);}catch(IOException ignored){continue;}
                if(candidate!=null){if(found!=null)throw new IOException("发现多个系统 adbd，无法确认权限来源");found=candidate;}
            }
        }
        return found;
    }
    private static AdbRoot.ProcessState identifyAdbd(Path process)throws IOException {
        if(!new String(Files.readAllBytes(process.resolve("comm")),StandardCharsets.UTF_8).trim().equals("adbd"))return null;
        boolean executable=false;
        for(String path:new String[]{"/apex/com.android.adbd/bin/adbd","/system/bin/adbd"}) {
            try{if(Files.isSameFile(process.resolve("exe"),Paths.get(path))){executable=true;break;}}catch(IOException ignored){}
        }
        if(!executable)return null;
        String context=new String(Files.readAllBytes(process.resolve("attr/current")),StandardCharsets.UTF_8).trim();
        String status=new String(Files.readAllBytes(process.resolve("status")),StandardCharsets.UTF_8);
        String stat=new String(Files.readAllBytes(process.resolve("stat")),StandardCharsets.US_ASCII);
        return AdbdIdentity.parse(process.getFileName().toString(),status,stat,context);
    }
    @Override public void openDebugSettings() throws IOException {
        String output=command("/system/bin/am","start","--user","0","-a","android.settings.APPLICATION_DEVELOPMENT_SETTINGS");
        if(output.contains("Error:") || output.contains("Exception"))throw new IOException("无法打开开发者选项，请在系统设置中手动进入 USB 调试");
    }
    static String command(String... args) throws IOException {
        java.lang.Process child=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        Thread reader=new Thread(() -> {
            try { byte[] b=new byte[1024];int n;while((n=child.getInputStream().read(b))!=-1) { if(out.size()+n<=8192)out.write(b,0,n); } }catch(IOException ignored){}
        },"usblink-command-output"); reader.setDaemon(true);reader.start();
        try {
            if(!child.waitFor(5,TimeUnit.SECONDS)) {child.destroyForcibly();throw new IOException("系统命令超时");}
            reader.join(500);
            if(child.exitValue()!=0)throw new IOException("系统拒绝了操作，请检查模块 Root 权限");
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }catch(InterruptedException e) {Thread.currentThread().interrupt();child.destroyForcibly();throw new IOException("操作已取消");}
    }
    private static Set<String> listenerInodes(int port) throws IOException {
        Set<String> inodes=new HashSet<>();
        for(String table:new String[]{"/proc/net/tcp","/proc/net/tcp6"}) {
            Path path=Paths.get(table);if(!Files.exists(path))continue;
            for(String line:Files.readAllLines(path,StandardCharsets.US_ASCII)) {
                String[] columns=line.trim().split("\\s+");
                if(columns.length<10 || !columns[3].equals("0A"))continue;
                String local=columns[1];int colon=local.lastIndexOf(':');if(colon<0)continue;
                try {if(Integer.parseInt(local.substring(colon+1),16)==port)inodes.add("socket:["+columns[9]+"]");}
                catch(NumberFormatException ignored){}
            }
        }
        return inodes;
    }
    private static boolean systemOwnsListener(int port) throws IOException {
        Set<String> inodes=listenerInodes(port);
        if(inodes.isEmpty())return false;
        try(DirectoryStream<Path> processes=Files.newDirectoryStream(Paths.get("/proc"))) {
            for(Path process:processes) {
                if(!process.getFileName().toString().matches("[0-9]+"))continue;
                try {
                    if(identifyAdbd(process)==null)continue;
                    try(DirectoryStream<Path> descriptors=Files.newDirectoryStream(process.resolve("fd"))) {
                        for(Path descriptor:descriptors) {
                            try {inodes.remove(Files.readSymbolicLink(descriptor).toString());}
                            catch(IOException ignored){}
                        }
                    }
                }catch(IOException ignored) { /* Processes can exit while enumerating /proc. Fail closed below. */ }
            }
        }
        // Do not accept one adbd socket if a different process owns another address on this port.
        return inodes.isEmpty();
    }
}
