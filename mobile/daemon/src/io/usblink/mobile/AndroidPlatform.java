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
                    String name=new String(Files.readAllBytes(process.resolve("comm")),StandardCharsets.UTF_8).trim();
                    if(!name.equals("adbd"))continue;
                    String context=new String(Files.readAllBytes(process.resolve("attr/current")),StandardCharsets.UTF_8).trim();
                    if(!context.equals("u:r:adbd:s0"))continue;
                    String expectedUid="(?:0|2000)";
                    String status=new String(Files.readAllBytes(process.resolve("status")),StandardCharsets.UTF_8);
                    if(!java.util.regex.Pattern.compile("(?m)^Uid:\\s+"+expectedUid+"\\s+"+expectedUid+"\\s").matcher(status).find())continue;
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
