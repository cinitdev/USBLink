package io.usblink.mobile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class AdbRootTestMain {
    static int checks;
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    interface Task{void run()throws Exception;}
    static void reject(Task task,String name)throws Exception{try{task.run();throw new AssertionError(name);}catch(IOException expected){checks++;}}
    static String state(AdbRoot root){return Json.string(root.status(),"state");}
    public static void main(String[] args)throws Exception{lifecycle();unsupported();failures();recovery();identity();controller();System.out.println("PASS: "+checks+" ADB Root checks");}
    static final class SystemState implements AdbRoot.SystemAccess {
        String root="",debuggable="1",boot="11111111-1111-1111-1111-111111111111";
        int uid=2000,restarts,serial=1;long now;boolean ready=true,auth=true,guard=true,ignore,missing,denyProperty,rootCapable,ambiguous;
        Path directory;List<String> events=new ArrayList<>();
        public String property(String name){if(name.equals("ro.debuggable"))return debuggable;if(name.equals("service.adb.root"))return root;throw new AssertionError("Unexpected property "+name);}
        public void rootProperty(String value)throws IOException {
            check(Files.exists(directory.resolve("adb-root-recovery.json")),"Journal exists before root property change");events.add("set:"+value);if(denyProperty)throw new IOException("模拟属性拒绝");root=value;
        }
        public void restart(){events.add("restart");restarts++;if(!ignore){serial++;uid=root.equals("1") && (debuggable.equals("1") || rootCapable)?0:2000;}}
        public void beforeRestart()throws IOException{events.add("guard");if(!guard)throw new IOException("模拟防护丢失");}
        public void requireAuthorization()throws IOException{if(!auth)throw new IOException("模拟认证关闭");}
        public String bootId(){return boot;}
        public AdbRoot.ProcessState process()throws IOException{if(ambiguous)throw new IOException("多个 adbd");return missing?null:new AdbRoot.ProcessState("42:"+serial,uid);}
        public boolean ready(){return ready;}
        public long elapsed(){return now;}
        public void pause(){now+=100;}
        AdbRoot create()throws IOException{if(directory==null)directory=Files.createTempDirectory("usblink-root-");return new AdbRoot(directory,this);}
    }
    static void lifecycle()throws Exception {
        SystemState system=new SystemState();AdbRoot root=system.create();system.ready=false;
        check(Boolean.TRUE.equals(root.status().get("enabled")),"Root preference defaults on");
        root.tick(()->system.events.add("revoke"));check(system.restarts==0 && state(root).equals("waiting_system"),"Boot preparation does not restart early");
        root.set(false,()->{});check(!Boolean.TRUE.equals(root.status().get("enabled")) && system.restarts==0,"Off cancels boot enable before system readiness");
        system.ready=true;root.tick(()->{});check(state(root).equals("shell") && system.restarts==0,"Saved off with normal daemon does not restart");
        root.set(true,()->system.events.add("revoke"));
        check(system.events.equals(Arrays.asList("guard","revoke","set:1","restart")),"Guard and session revocation before one requested restart");
        check(root.changing() && !Boolean.TRUE.equals(root.status().get("active")),"Property accepted is not yet claimed as actual Root");
        AdbRoot pendingRoot=root;reject(()->pendingRoot.set(false,()->{}),"Pending transition rejects a second restart");
        root.tick(()->{});check(Boolean.TRUE.equals(root.status().get("active")) && state(root).equals("root"),"New process and UID zero confirm Root");
        for(int i=0;i<10;i++){root.tick(()->{});root.status();}check(system.restarts==1,"Observation never restarts confirmed adbd");
        root.set(false,()->{});root.tick(()->{});check(system.uid==2000 && state(root).equals("shell"),"Unroot without phone reboot");
        root.set(true,()->{});root.tick(()->{});check(system.uid==0 && system.restarts==3,"Enable again without reboot");
        root.restore();check(system.root.equals("") && system.uid==2000 && !Files.exists(system.directory.resolve("adb-root-recovery.json")),"Stop restores original property and actual UID");
        root=system.create();root.tick(()->{});root.tick(()->{});check(system.uid==0,"Saved on applied on next controlled startup");
        root.set(false,()->{});root.tick(()->{});root.restore();root=system.create();int before=system.restarts;root.tick(()->{});
        check(!Boolean.TRUE.equals(root.status().get("enabled")) && system.restarts==before,"Saved off survives daemon restart");
        SystemState capable=new SystemState();capable.debuggable="0";capable.uid=0;capable.root="1";capable.rootCapable=true;
        AdbRoot existing=capable.create();existing.tick(()->{});existing.set(false,()->{});existing.tick(()->{});
        existing=capable.create();existing.tick(()->{});existing.set(true,()->{});existing.tick(()->{});
        check(state(existing).equals("root") && capable.restarts==2,"Previously verified Root capability survives unroot and daemon recreation on a non-debuggable ROM");
    }
    static void unsupported()throws Exception {
        SystemState system=new SystemState();system.debuggable="0";AdbRoot root=system.create();root.tick(()->{throw new AssertionError("unsupported must not revoke");});
        check(state(root).equals("unsupported") && !Boolean.TRUE.equals(root.status().get("active")),"Production ROM is shown unsupported, not falsely rooted");
        check(system.restarts==0 && system.root.equals("") && root.allowsSharing(),"Unsupported default leaves ordinary USB sharing available");
        root.set(false,()->{});check(state(root).equals("shell") && system.restarts==0,"Unsupported ROM can save off without unnecessary interruption");
        system.missing=true;root.tick(()->{});check(root.status().get("active")==null && !root.allowsSharing(),"Missing process is unknown, never confirmed unrooted");
        system.missing=false;root.tick(()->{});system.ambiguous=true;root.tick(()->{});
        check(root.status().get("active")==null && !root.allowsSharing(),"Ambiguous process cannot retain stale unroot confirmation");
    }
    static void failures()throws Exception {
        for(String fault:Arrays.asList("auth","guard","property","restart")) {
            SystemState system=new SystemState();AdbRoot root=system.create();
            system.auth=!fault.equals("auth");system.guard=!fault.equals("guard");system.denyProperty=fault.equals("property");system.ignore=fault.equals("restart");
            root.tick(()->{});system.now=11000;root.tick(()->{});
            check(state(root).equals("error"),"Failure visible: "+fault);int restarts=system.restarts;
            for(int i=0;i<5;i++)root.tick(()->{});check(system.restarts==restarts,"No automatic retry after "+fault);
            check(fault.equals("restart") || system.restarts==0,"Rejected setup cannot restart daemon");
        }
        SystemState system=new SystemState();AdbRoot root=system.create();root.tick(()->{});root.tick(()->{});system.ignore=true;
        root.set(false,()->{});system.now=11000;root.tick(()->{});
        check(system.uid==0 && state(root).equals("error") && !root.allowsSharing(),"Failed unroot keeps remote sharing revoked");
        system.ignore=false;root.restore();check(system.uid==2000,"Explicit recovery handles failed unroot");
        SystemState corrupt=new SystemState();root=corrupt.create();Files.writeString(corrupt.directory.resolve("adb-root-preference.json"),"broken");root=corrupt.create();root.tick(()->{});
        check(state(root).equals("error") && corrupt.restarts==0,"Corrupt preference never defaults to granting Root");
        root.set(false,()->{});check(state(root).equals("shell"),"Explicit off repairs corrupted preference");
        SystemState inaccessible=new SystemState();inaccessible.missing=true;root=inaccessible.create();root.tick(()->{});
        check(state(root).equals("error") && inaccessible.restarts==0,"Unverifiable running adbd reports an actionable error without mutation");
    }
    static void recovery()throws Exception {
        SystemState system=new SystemState();AdbRoot root=system.create();root.tick(()->{});root.tick(()->{});
        int before=system.restarts;AdbRoot adopted=system.create();adopted.tick(()->{});
        check(state(adopted).equals("root") && system.restarts==before,"Existing journal can be observed without replaying restart");
        system.root="0";system.uid=2000;adopted.tick(()->{});
        check(state(adopted).equals("conflict") && system.restarts==before,"External unroot is not automatically overwritten");
        reject(adopted::restore,"External root property prevents conflicting restoration");
        check(system.root.equals("0") && Files.exists(system.directory.resolve("adb-root-recovery.json")),"Conflict keeps recovery journal");
        system.boot="22222222-2222-2222-2222-222222222222";adopted.restore();
        check(system.root.equals("0") && system.restarts==before,"Previous boot journal never replays properties");
        SystemState stopped=new SystemState();root=stopped.create();root.tick(()->{});root.tick(()->{});stopped.missing=true;before=stopped.restarts;root.restore();
        check(stopped.restarts==before && stopped.root.equals(""),"Restoration never starts disabled adbd");
    }
    static void identity() {
        String stat="42 (adbd) S "+"0 ".repeat(18)+"123";
        AdbRoot.ProcessState found=AdbdIdentity.parse("42","Uid:\t0\t0\t0\t0\n",stat,"u:r:su:s0");
        check(found!=null && found.identity.equals("42:123") && found.uid==0,"Root SELinux domain accepted only after caller executable checks");
        check(AdbdIdentity.parse("42","Uid:\t2000\t2000\t2000\t2000\n",stat,"u:r:adbd:s0")!=null,"Ordinary system adbd recognized");
        check(AdbdIdentity.parse("42","Uid:\t0\t2000\t0\t0\n",stat,"u:r:adbd:s0")==null,"Mixed UID identity not accepted");
        check(AdbdIdentity.parse("42","Uid:\t0\t0\t0\t0\n",stat,"u:r:untrusted_app:s0")==null,"Unexpected domain not accepted");
        check(AdbdIdentity.parse("42","Uid:\t0\t0\t0\t0\n",stat.replace(") S", ") Z"),"u:r:adbd:s0")==null,"Zombie never confirms permission");
    }
    static void controller()throws Exception {
        SystemState system=new SystemState();AdbRoot root=system.create();TestMain.FakeMesh mesh=new TestMain.FakeMesh();mesh.ip="127.0.0.1";
        Platform phone=new Platform(){
            public Map<String,Object> device(){return Json.map("model","Test");}
            public void recoverTcp(){}public void startTcp(){}public int tcpPort(){return 15558;}public void stopTcp(){}public void openDebugSettings(){}
            public void tickAdbRoot(Runnable revoke){root.tick(revoke);}public void setAdbRoot(boolean next,Runnable revoke)throws IOException{root.set(next,revoke);}
            public boolean adbRootChanging(){return root.changing();}public boolean adbRootAllowsSharing(){return root.allowsSharing();}public Map<String,Object> adbRootStatus(){return root.status();}
        };
        try(ProxyPool proxy=new ProxyPool();Controller control=new Controller(phone,mesh,new SharingStore(system.directory),proxy,0)) {
            control.tick();control.tick();check(!mesh.requested,"Default Root does not connect the saved mesh while sharing is off");
            control.handle("set-sharing",Json.map("enabled",true));check(proxy.active("adb"),"Sharing available after confirmed Root");
            system.ignore=true;control.handle("set-adb-root",Json.map("enabled",false));check(!proxy.active("adb"),"Explicit privilege change revokes active listener");
            system.now=11000;control.tick();check(!proxy.active("adb") && Json.string(Json.object(control.status().get("sharing")),"state").equals("error"),"Failed downgrade cannot resume sharing with Root");
            system.ignore=false;control.handle("set-adb-root",Json.map("enabled",true));control.tick();
            check(proxy.active("adb") && proxy.snapshot().isEmpty(),"Listener resumes without automatic client connection");
            system.guard=false;reject(()->control.handle("set-adb-root",Json.map("enabled",false)),"Rejected downgrade remains visible to the caller");
            check(!proxy.active("adb"),"Even a pre-mutation downgrade failure revokes active sharing immediately");
        }
    }
}
