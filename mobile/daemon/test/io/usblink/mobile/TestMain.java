package io.usblink.mobile;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class TestMain {
    private static int checks;
    public static void main(String[] args) throws Exception {
        jsonProtocol(); largeTransferAndHalfClose(); revokeSessions(); failedConnectCleanup(); sharingLifecycle(); corruptedStore(); legacyPreference(); statusSnapshot();
        System.out.println("PASS: " + checks + " mobile backend checks");
    }
    private static void check(boolean yes,String message) {checks++;if(!yes)throw new AssertionError(message);}
    private static void expectFailure(Throwing action,String message) throws Exception {
        try {action.run();throw new AssertionError(message);}catch(IOException|IllegalArgumentException expected){checks++;}
    }
    private static void eventually(BooleanSupplier condition,String message) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(10);
        check(condition.getAsBoolean(),message);
    }
    private static void jsonProtocol() throws Exception {
        Map<String,Object> round=Json.object(Json.parse(Json.stringify(Json.map("中文","a\n\"\\手机","enabled",true,"port",3242,"empty",null))));
        check(round.get("中文").equals("a\n\"\\手机"),"Unicode and escaping");
        check(Json.integer(round,"port")==3242,"Integer port");
        for(String invalid:new String[]{"{\"port\":1,\"port\":2}","{\"port\":01}","[1,]","true junk","{\"port\":1e999}","\"a\nb\""})expectFailure(()->Json.parse(invalid),"Reject malformed JSON");
        expectFailure(()->Json.integer(Json.object(Json.parse("{\"port\":3242.0}")),"port"),"No implicit fractional port conversion");
        expectFailure(()->Json.parse("[".repeat(30)+"]".repeat(30)),"Bound recursion");
        expectFailure(()->Json.parse(" ".repeat(65537)),"Bound protocol size");
    }
    private static void largeTransferAndHalfClose() throws Exception {
        byte[] chunk=new byte[65536];for(int i=0;i<chunk.length;i++)chunk[i]=(byte)(i*37);
        int repeats=896; // 56 MiB, comparable to the APK transfer that previously failed through USB/IP.
        MessageDigest expected=MessageDigest.getInstance("SHA-256");for(int i=0;i<repeats;i++)expected.update(chunk);
        AtomicReference<Throwable> serverError=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        try(ServerSocket target=new ServerSocket(0,4,java.net.InetAddress.getLoopbackAddress());ProxyPool proxy=new ProxyPool()) {
            Thread server=new Thread(()->{
                try(Socket s=target.accept()) {
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
                    while((n=s.getInputStream().read(b))!=-1)digest.update(b,0,n);
                    s.getOutputStream().write(digest.digest());s.shutdownOutput();
                }catch(Throwable e){serverError.set(e);}finally{done.countDown();}
            });server.setDaemon(true);server.start();
            proxy.configure("adb","127.0.0.1",0,target.getLocalPort());
            try(Socket client=new Socket("127.0.0.1",proxy.boundPort("adb"))) {
                client.setSoTimeout(15000);
                for(int i=0;i<repeats;i++)client.getOutputStream().write(chunk);
                client.shutdownOutput();
                check(Arrays.equals(client.getInputStream().readAllBytes(),expected.digest()),"56 MiB transfer retains final response after half-close");
            }
            check(done.await(3,TimeUnit.SECONDS) && serverError.get()==null,"Target completed without errors");
            eventually(()->proxy.snapshot().isEmpty(),"Completed session released");
        }
    }
    private static void revokeSessions() throws Exception {
        try(ServerSocket target=new ServerSocket(0);ProxyPool proxy=new ProxyPool()) {
            proxy.configure("adb","127.0.0.1",0,target.getLocalPort());
            try(Socket one=new Socket("127.0.0.1",proxy.boundPort("adb"));Socket t1=target.accept();
                Socket two=new Socket("127.0.0.1",proxy.boundPort("adb"));Socket t2=target.accept()) {
                eventually(()->proxy.snapshot().size()==2,"Two clients registered");
                String id=(String)proxy.snapshot().get(0).get("id");proxy.disconnect(id);
                check(proxy.snapshot().size()==1,"Targeted disconnect preserves other session");
                int port=proxy.boundPort("adb");proxy.stopAll();
                check(!proxy.active("adb") && proxy.snapshot().isEmpty(),"Disable revokes listener and sessions");
                expectFailure(()->{try(Socket ignored=new Socket("127.0.0.1",port)){}},"Disabled listener refuses new clients");
            }
            proxy.configure("adb","127.0.0.1",0,target.getLocalPort());
            check(proxy.active("adb"),"Re-enable without restarting process");
        }
    }
    private static void failedConnectCleanup() throws Exception {
        int unused;try(ServerSocket reserve=new ServerSocket(0)){unused=reserve.getLocalPort();}
        try(ProxyPool proxy=new ProxyPool()) {
            proxy.configure("adb","127.0.0.1",0,unused);
            try(Socket client=new Socket("127.0.0.1",proxy.boundPort("adb"))) {
                client.setSoTimeout(4000);check(client.getInputStream().read()==-1,"Unavailable target closes client");
                eventually(()->proxy.snapshot().isEmpty(),"Failed target does not leak a session");
                check(proxy.active("adb"),"Individual failure does not disable listener");
            }
        }
    }
    private static void sharingLifecycle() throws Exception {
        Path dir=Files.createTempDirectory("usblink-sharing-test-");SharingStore store=new SharingStore(dir);
        FakePlatform platform=new FakePlatform();FakeMesh mesh=new FakeMesh();
        try(ProxyPool proxy=new ProxyPool();Controller control=new Controller(platform,mesh,store,proxy,0)) {
            control.tick();check(!enabled(control),"Fresh install defaults off");
            control.handle("set-sharing",Json.map("enabled",true));
            check(enabled(control) && state(control).equals("waiting_network"),"Saved on differs from readiness");
            check(store.load() && platform.starts==0,"No adbd change until network ready");
            mesh.ip="127.0.0.1";control.tick();check(state(control).equals("starting"),"Wait for adbd actual listener");
            platform.port=32123;control.tick();check(state(control).equals("sharing") && proxy.active("adb"),"Ready creates proxy");
            for(int i=0;i<4;i++)control.tick();check(platform.starts==1,"Polling never restarts adbd");
            mesh.ip=null;control.tick();check(!proxy.active("adb"),"Network loss revokes proxy");
            mesh.ip="127.0.0.1";control.tick();check(proxy.active("adb") && platform.starts==1,"Network restoration does not restart adbd");
            platform.failure=true;control.tick();check(!proxy.active("adb") && state(control).equals("error"),"Adbd/firewall failure revokes proxy");
            platform.failure=false;control.tick();check(!proxy.active("adb") && platform.starts==1,"Failure requires explicit off/on, no restart loop");
            control.handle("set-sharing",Json.map("enabled",false));
            check(!store.load() && !enabled(control) && !proxy.active("adb"),"Off persists and restores");
            check(Boolean.TRUE.equals(Json.object(control.status().get("appSession")).get("ready")),"Sharing off retains authenticated app presence");
            control.tick();check(!proxy.active("adb"),"Polling never undoes explicit off");
            control.handle("set-sharing",Json.map("enabled",true));check(platform.starts==2,"Manual re-enable starts one new operation");
            platform.restoreFailure=true;int stopsBefore=platform.stops;
            expectFailure(()->control.handle("set-sharing",Json.map("enabled",false)),"Restore error not claimed as successful off");
            check(!store.load() && !proxy.active("adb") && state(control).equals("error"),"Restore failure retains saved off and visible error");
            for(int i=0;i<4;i++)control.tick();
            mesh.tickFailure=true;control.tick();mesh.tickFailure=false;
            check(platform.stops==stopsBefore+1,"Failed restore is attempted once; polling and mesh failures cannot retry it");
            platform.restoreFailure=false;control.handle("set-sharing",Json.map("enabled",false));check(state(control).equals("off"),"Restore can be retried without reboot");
            control.handle("open-debug-settings",Json.map());check(platform.settingsOpened,"Settings button works");
            expectFailure(()->control.handle("set-pairing",Json.map("port",-1)),"Removed pairing API rejected");
            expectFailure(()->control.handle("arbitrary-shell",Json.map("command","id")),"Only explicit actions supported");
            int startsBeforeLeave=platform.starts;
            Map<String,Object> left=control.handle("leave-mesh",Json.map());
            check(Boolean.FALSE.equals(Json.object(left.get("appSession")).get("ready")),"Leave response immediately revokes presence");
            check(platform.starts==startsBeforeLeave,"Presence and leave never start adbd");
        }
        try(Controller reopened=new Controller(platform,mesh,store,new ProxyPool(),0)) {reopened.tick();check(!enabled(reopened),"Restart retains off");}
        store.save(true);
        try(Controller reopened=new Controller(platform,mesh,store,new ProxyPool(),0)) {reopened.tick();check(enabled(reopened),"Restart retains TCP on preference");}
    }
    private static void corruptedStore() throws Exception {
        Path dir=Files.createTempDirectory("usblink-corrupt-test-");Files.writeString(dir.resolve("sharing.json"),"broken");
        FakeMesh mesh=new FakeMesh();mesh.ip="127.0.0.1";FakePlatform platform=new FakePlatform();platform.port=32123;
        try(Controller controller=new Controller(platform,mesh,new SharingStore(dir),new ProxyPool(),0)) {
            controller.tick();check(!enabled(controller) && state(controller).equals("error"),"Corrupt config fails closed visibly");
            controller.handle("set-sharing",Json.map("enabled",false));check(state(controller).equals("off"),"Explicit off repairs malformed preference");
        }
    }
    private static void legacyPreference()throws Exception {
        Path dir=Files.createTempDirectory("usblink-legacy-test-");Files.writeString(dir.resolve("sharing.json"),Json.stringify(Json.map("version",1,"enabled",true)));
        check(!new SharingStore(dir).load(),"Old wireless intent does not start TCP adbd");
    }
    private static void statusSnapshot()throws Exception {
        Path dir=Files.createTempDirectory("usblink-status-test-");Path file=dir.resolve("status.snapshot");
        Files.writeString(file,"old boot state");
        StatusSnapshot snapshot=new StatusSnapshot(dir,42,"123","11111111-1111-1111-1111-111111111111",()->100);
        check(!Files.exists(file),"Daemon startup invalidates an older snapshot");
        snapshot.publish(Json.map("sharing",Json.map("enabled",true),"device",Json.map("model","测试手机")));
        java.util.List<String> lines=Files.readAllLines(file);
        check(lines.size()==2 && lines.get(0).equals("42 123 11111111-1111-1111-1111-111111111111 100"),"Status carries process identity, boot and monotonic time");
        check(Boolean.TRUE.equals(Json.object(Json.parse(lines.get(1))).get("ok")),"Single complete JSON envelope survives atomic publication");
        snapshot.invalidate();check(!Files.exists(file),"Pending mutation revokes the previous observation");
        snapshot.publish(Json.map("sharing",Json.map("enabled",false)));snapshot.close();
        check(!Files.exists(file),"Orderly exit removes shared UI status");
        snapshot.publish(Json.map());check(!Files.exists(file),"Late scheduled publication cannot resurrect status after close");
    }
    private static boolean enabled(Controller c)throws IOException{return (Boolean)Json.object(c.status().get("sharing")).get("enabled");}
    private static String state(Controller c)throws IOException{return (String)Json.object(c.status().get("sharing")).get("state");}
    interface Throwing {void run()throws Exception;}
    static final class FakePlatform implements Platform {
        int port,starts,stops;boolean settingsOpened,failure,restoreFailure;
        public Map<String,Object> device(){return Json.map("model","Test Phone","androidVersion","12","sdk",31,"root",true);}
        public void recoverTcp()throws IOException{stopTcp();}
        public void startTcp(){starts++;}
        public int tcpPort()throws IOException{if(failure)throw new IOException("模拟防护故障");return port;}
        public void stopTcp()throws IOException{stops++;if(restoreFailure)throw new IOException("模拟恢复失败");}
        public void openDebugSettings(){settingsOpened=true;}
    }
    static final class FakeMesh implements MeshNetwork {
        String ip;boolean tickFailure;
        public Map<String,Object> status(){return Json.map("configured",true,"running",true,"localIp",ip,"networkName","test","peers",new ArrayList<>(),"problem",null);}
        public String create(){return "test";}public void join(String code){}public String pairingCode(){return "test";}
        public void leave(){ip=null;}public void setRelay(String relay){}public void tick()throws IOException{if(tickFailure)throw new IOException("模拟组网失败");}public void stop(){}
        public String localIp(){return ip;}public String relay(){return "tcp://183.230.36.171:11010";}
        public byte[] presenceKey(){return new byte[32];}
    }
}
