package io.usblink.mobile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** Owns service.adb.root only. Never patches adbd, auth properties, SELinux or USB configuration. */
final class AdbRoot {
    interface SystemAccess {
        String property(String name)throws IOException;
        void rootProperty(String value)throws IOException;
        void restart()throws IOException;
        void beforeRestart()throws IOException;
        void requireAuthorization()throws IOException;
        String bootId()throws IOException;
        ProcessState process()throws IOException;
        boolean ready()throws IOException;
        long elapsed();
        void pause()throws IOException;
    }
    static final class ProcessState {
        final String identity;final int uid;
        ProcessState(String identity,int uid){this.identity=identity;this.uid=uid;}
    }
    private final Path preference,journal;
    private final SystemAccess system;
    private boolean enabled=true,initialized,attempted,pending,supported,knownRoot;
    private Boolean active;
    private String state="waiting_system",problem="等待系统调试服务就绪",storeProblem="",beforeIdentity="";
    private long deadline;
    AdbRoot(Path directory,SystemAccess system) {
        preference=directory.resolve("adb-root-preference.json");journal=directory.resolve("adb-root-recovery.json");this.system=system;
        try{if(Files.exists(preference)){Map<String,Object> saved=read(preference);if(Json.integer(saved,"version")!=1 || !(saved.get("enabled") instanceof Boolean))throw new IOException();enabled=(Boolean)saved.get("enabled");}}
        catch(Exception e){enabled=false;attempted=true;state="error";storeProblem=problem="ADB Root 开关保存内容损坏，请重新设置开关";}
    }
    synchronized Map<String,Object> status(){return Json.map("enabled",enabled,"active",active,"supported",supported,"state",state,"problem",problem);}
    synchronized boolean changing(){return pending;}
    synchronized boolean allowsSharing(){return enabled || Boolean.FALSE.equals(active);}
    synchronized void tick(Runnable revoke) {
        try {
            if(!initialized){initialize();initialized=true;}
            observe();
            if(!attempted){if(system.ready())apply(revoke);else {state="waiting_system";problem="等待系统启动并开启普通 USB 调试";}}
        }catch(IOException e){active=null;pending=false;attempted=true;state="error";problem=e.getMessage();}
    }
    private void initialize()throws IOException {
        if(!Files.exists(journal))return;
        Map<String,Object> entry=recovery();
        if(!system.bootId().equals(Json.string(entry,"boot"))){Files.delete(journal);return;}
        knownRoot=Json.integer(entry,"uid")==0;
        // A supervisor restart must never replay an uncertain property write/restart.
        attempted=true;
        String applied=Json.string(entry,"applied");
        if(!system.property("service.adb.root").equals(applied))throw new IOException("ADB Root 配置已变化，未覆盖外部设置；请检查其他调试工具");
        beforeIdentity=Json.string(entry,"before");deadline=system.elapsed()+10_000;pending=true;
    }
    private void observe()throws IOException {
        ProcessState process=system.process();active=process==null?null:process.uid==0;
        knownRoot|=Boolean.TRUE.equals(active);supported=system.property("ro.debuggable").equals("1") || knownRoot;
        if(!storeProblem.isEmpty()){state="error";problem=storeProblem;return;}
        if(pending) {
            String target=enabled?"1":"0";
            if(!system.property("service.adb.root").equals(target))throw new IOException("系统未保留请求的 ADB Root 配置，切换失败；请检查 ROM 支持情况");
            if(process!=null && !process.identity.equals(beforeIdentity) && process.uid==(enabled?0:2000)) {
                pending=false;state=enabled?"root":"shell";problem="";return;
            }
            if(system.elapsed()>=deadline)throw new IOException("未确认 adbd 切换到请求的权限，未自动补试；请检查 ROM 是否支持 ADB Root");
            state="changing";problem="正在等待 adbd 重启并确认实际 UID";return;
        }
        if(attempted && (state.equals("error") || state.equals("conflict")))return;
        if(Files.exists(journal) && !system.property("service.adb.root").equals(Json.string(recovery(),"applied"))) {
            state="conflict";problem="其他工具已修改 ADB Root 设置，模块不会自动改回";return;
        }
        if(process==null){state="waiting_system";problem="等待系统启动并开启普通 USB 调试";return;}
        if(enabled && !supported){state="unsupported";problem="当前 ROM 的 adbd 不支持 Root；手机有 Root 权限不代表 adbd 支持，未修改系统二进制";return;}
        if(attempted && process.uid!=(enabled?0:2000)){state="error";problem="adbd 实际权限与开关不一致，未自动重启或重新挂载";return;}
        state=enabled?"root":"shell";problem="";
    }
    synchronized void set(boolean next,Runnable revoke)throws IOException {
        if(pending)throw new IOException("上次 adbd 权限切换尚未确认，请稍候再操作");
        save(preference,Json.map("version",1,"enabled",next));enabled=next;storeProblem="";
        pending=false;attempted=false;state="waiting_system";problem="等待系统调试服务就绪";
        try {
            if(!initialized){initialize();initialized=true;}
            observe();
            if(system.ready())apply(revoke);else {state="waiting_system";problem="开关已保存，等待系统启动并开启普通 USB 调试";}
        }catch(IOException e){active=null;pending=false;attempted=true;state="error";problem=e.getMessage();throw e;}
    }
    private void apply(Runnable revoke)throws IOException {
        attempted=true;ProcessState process=system.process();
        if(process==null)throw new IOException("系统调试服务已运行，但无法核验 adbd 进程权限；请检查 ROM 与模块访问权限");
        active=process.uid==0;
        knownRoot|=Boolean.TRUE.equals(active);supported=system.property("ro.debuggable").equals("1") || knownRoot;
        if(enabled && !supported){state="unsupported";problem="当前 ROM 的 adbd 不支持 Root；保持原有调试权限";return;}
        String current=system.property("service.adb.root");
        if(!valid(current))throw new IOException("现有 ADB Root 属性无法识别，已取消切换");
        Map<String,Object> entry=Files.exists(journal)?recovery():null;
        if(entry!=null && !current.equals(Json.string(entry,"applied")))throw new IOException("其他工具已修改 ADB Root 设置，未覆盖其配置");
        String target=enabled?"1":"0";
        if(process.uid==(enabled?0:2000) && (current.equals(target) || (!enabled && !current.equals("1")))) {state=enabled?"root":"shell";problem="";return;}
        system.requireAuthorization();system.beforeRestart();
        if(!system.property("service.adb.root").equals(current))throw new IOException("ADB Root 配置在切换前发生变化，操作已取消");
        beforeIdentity=process.identity;
        save(journal,Json.map("version",1,"boot",system.bootId(),"original",entry==null?current:Json.string(entry,"original"),
            "uid",entry==null?process.uid:Json.integer(entry,"uid"),"applied",target,"before",beforeIdentity));
        revoke.run();
        system.rootProperty(target);
        if(!system.property("service.adb.root").equals(target))throw new IOException("系统拒绝 ADB Root 配置，切换未完成");
        system.restart();pending=true;deadline=system.elapsed()+10_000;state="changing";problem="正在等待 adbd 重启并确认实际 UID";
    }
    synchronized void restore()throws IOException {
        if(!Files.exists(journal))return;
        Map<String,Object> entry=recovery();
        if(!system.bootId().equals(Json.string(entry,"boot"))){Files.delete(journal);return;}
        String current=system.property("service.adb.root"),original=Json.string(entry,"original"),applied=Json.string(entry,"applied");
        if(!current.equals(applied) && !current.equals(original))throw new IOException("外部 ADB Root 配置已变化，恢复记录已保留，未覆盖外部设置");
        ProcessState before=system.process();
        if(!current.equals(original) || (before!=null && before.uid!=Json.integer(entry,"uid"))) {
            system.beforeRestart();
            if(!current.equals(system.property("service.adb.root")))throw new IOException("恢复时 ADB Root 配置发生变化，已取消覆盖");
            system.rootProperty(original);
            if(!original.equals(system.property("service.adb.root")))throw new IOException("原 ADB Root 配置恢复失败");
            if(before!=null) {
                system.restart();long until=system.elapsed()+8000;
                boolean confirmed=false;
                do {ProcessState after=system.process();if(after!=null && !after.identity.equals(before.identity) && after.uid==Json.integer(entry,"uid")){confirmed=true;break;}system.pause();}while(system.elapsed()<until);
                if(!confirmed)throw new IOException("未确认原 adbd 权限已恢复，恢复记录仍保留");
            }
        }
        Files.delete(journal);pending=false;
    }
    private Map<String,Object> recovery()throws IOException {
        Map<String,Object> entry=read(journal);
        try{if(Json.integer(entry,"version")!=1 || !valid(Json.string(entry,"original")) || !Json.string(entry,"applied").matches("[01]") || !Json.string(entry,"boot").matches("[a-fA-F0-9-]{36}") || !Json.string(entry,"before").matches("[0-9]+:[0-9]+") || (Json.integer(entry,"uid")!=0 && Json.integer(entry,"uid")!=2000))throw new IllegalArgumentException();return entry;}
        catch(IllegalArgumentException e){throw new IOException("ADB Root 恢复记录损坏，请检查模块数据");}
    }
    private static boolean valid(String value){return value.isEmpty() || value.equals("0") || value.equals("1");}
    private static Map<String,Object> read(Path path)throws IOException {
        try{if(Files.size(path)>2048)throw new IllegalArgumentException();return Json.object(Json.parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)));}
        catch(IllegalArgumentException e){throw new IOException("ADB Root 配置文件损坏");}
    }
    private static void save(Path path,Map<String,Object> data)throws IOException {
        Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".new");
        try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes=ByteBuffer.wrap(Json.stringify(data).getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
        try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
}
