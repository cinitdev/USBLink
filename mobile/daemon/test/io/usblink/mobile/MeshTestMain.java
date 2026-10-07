package io.usblink.mobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/** Runs without Android or launching EasyTier. Uses the Windows USBLINK1 schema. */
public final class MeshTestMain {
    private static int checks;
    public static void main(String[] args) throws Exception {
        String secret="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String wire="{\"version\":1,\"network_name\":\"usblink-0123456789ab\",\"network_secret\":\""+secret+"\",\"relay\":\"tcp://183.230.36.171:11010\"}";
        String code="USBLINK1-"+Base64.getUrlEncoder().withoutPadding().encodeToString(wire.getBytes(StandardCharsets.UTF_8));
        MeshProfile imported=MeshProfile.decode(code);
        check(imported.name.equals("usblink-0123456789ab"),"Windows network name");
        check(imported.secret.equals(secret),"Windows secret");
        check(MeshManager.toml(imported).contains("tcp_whitelist = [\""+(TransportFactory.USB?"3240-3241":"3241-3242")+"\"]"),"Mesh permits authenticated presence and ADB proxy");
        check(imported.encode().equals(code),"Windows wire format roundtrip");
        reject(()->MeshProfile.decode("USBLINK1-not-valid"),"malformed base64/JSON");
        reject(()->MeshProfile.parse(wire.replace("\"version\":1","\"version\":2")),"version gate");
        reject(()->new MeshProfile("usblink-0123456789ab",secret,"tcp://a:0"),"zero port");
        reject(()->new MeshProfile("usblink-0123456789ab",secret,"tcp://a:65536"),"port overflow");
        reject(()->new MeshProfile("usblink-0123456789ab",secret,"tcp://a:12\"\nnetwork_secret=\"x"),"TOML injection");
        check(MeshProfile.validateRelay("tcp://public.easytier.top:11010/").equals(MeshProfile.DEFAULT_RELAY),"retired relay migration");
        check(MeshManager.validMeshRoute("10.126.126.0/24","10.126.126.2"),"matching private route");
        check(!MeshManager.validMeshRoute("10.126.126.0/24","10.126.127.2"),"reject unrelated route");
        check(!MeshManager.validMeshRoute("0.0.0.0/0","10.126.126.2"),"reject default route");
        check(!MeshManager.validMeshRoute("10.0.0.0/99","10.0.0.2"),"reject invalid netmask");
        check(!MeshManager.validMeshRoute("192.168.1.0/8","192.168.1.2"),"reject broad route");
        check(!MeshManager.validMeshRoute("8.8.8.0/24","8.8.8.8"),"reject public route");
        check(!MeshManager.validMeshRoute("10.126.126.1/24","10.126.126.2"),"reject noncanonical subnet");
        Path state=Files.createTempDirectory("usblink-mesh-test-");
        try {
            MeshManager manager=new MeshManager(state,state);
            check(Boolean.FALSE.equals(manager.status().get("configured")),"fresh config disabled");
            manager.join(code);
            check(java.util.Arrays.equals(manager.presenceKey(),PresenceServer.key(secret)),"Presence uses joined network key");
            check(Boolean.TRUE.equals(manager.status().get("configured")),"join persisted");
            check(!Json.stringify(manager.status()).contains(secret),"status never exposes secret");
            check(!Json.stringify(manager.status()).contains(code),"status never exposes code");
            MeshManager restored=new MeshManager(state,state);
            check(restored.pairingCode().equals(code),"pairing restored without starting process");
            check(restored.localIp()==null,"saved profile does not fake running network");
            restored.leave();
            check(restored.presenceKey()==null,"Leave removes presence key");
            check(!Files.exists(state.resolve("mesh.json")),"leave removes saved profile");
            check(Boolean.FALSE.equals(restored.status().get("configured")),"leave resets configured");
        } finally {
            try(java.util.stream.Stream<Path> entries=Files.list(state)) {for(Path file:(Iterable<Path>)entries::iterator)Files.delete(file);}
            Files.delete(state);
        }
        routeReconciliation();
        pendingLeave();
        System.out.println("PASS: "+checks+" mobile mesh checks");
    }
    private static void pendingLeave() throws Exception {
        Path state=Files.createTempDirectory("usblink-leave-test-");
        FakeIp ip=new FakeIp();MeshManager manager=new MeshManager(state,state,ip);
        try {
            manager.create();manager.ensureRoute("10.126.126.2");
            ip.failDelete=true;
            reject(manager::leave,"leave reports pending route cleanup");
            check(Boolean.TRUE.equals(manager.status().get("configured")),"failed leave retains profile for cleanup UI");
            reject(manager::tick,"pending leave tick only retries failed cleanup");
            ip.failDelete=false;manager.tick();
            check(Boolean.FALSE.equals(manager.status().get("configured")),"recovered cleanup completes leave instead of restarting");
            check(!Files.exists(state.resolve("mesh.json")),"completed leave deletes saved profile");
            manager.create();
            check(Boolean.TRUE.equals(manager.status().get("configured")),"explicit new network may replace completed leave");
            // A nonempty directory simulates an OS refusing deletion of the saved config.
            Files.delete(state.resolve("mesh.json"));Files.createDirectory(state.resolve("mesh.json"));
            Files.write(state.resolve("mesh.json/blocker"),new byte[]{1});
            reject(manager::leave,"config deletion failure reported");
            check(String.valueOf(manager.status().get("problem")).contains("配置删除失败"),"config failure remains visible");
            reject(manager::tick,"config cleanup failure does not start old network");
            Files.delete(state.resolve("mesh.json/blocker"));Files.delete(state.resolve("mesh.json"));
            manager.tick();
            check(Boolean.FALSE.equals(manager.status().get("configured")),"config cleanup can recover without reboot");
            // Successful replacement clears the pending leave intent.
            manager.create();manager.ensureRoute("10.126.126.2");
            ip.failDelete=true;
            manager.tick();
            check(Boolean.TRUE.equals(manager.status().get("configured")),"replacement retains new network during route reconciliation");
            check(!String.valueOf(manager.status().get("problem")).startsWith("退出连接"),"new network no longer treated as pending exit");
            ip.failDelete=false;manager.leave();
        } finally {
            Files.deleteIfExists(state.resolve("mesh.json/blocker"));
            try(java.util.stream.Stream<Path> entries=Files.list(state)) {for(Path file:(Iterable<Path>)entries::iterator)Files.delete(file);}
            Files.delete(state);
        }
    }
    private static void routeReconciliation() throws Exception {
        Path state=Files.createTempDirectory("usblink-route-test-");
        FakeIp ip=new FakeIp();
        MeshManager manager=new MeshManager(state,state,ip);
        try {
            manager.ensureRoute("10.126.126.2");
            check(ip.added==1,"initial route installed");
            manager.ensureRoute("10.126.126.2");
            check(ip.added==1,"existing owned route is not duplicated");
            ip.rule=""; // Simulates Android/VPN removing a live policy rule.
            manager.ensureRoute("10.126.126.2");
            check(ip.added==2,"missing cached rule is restored");
            ip.rule="17891: from all to 192.168.5.0/24 lookup main\n";
            reject(()->manager.ensureRoute("10.126.126.2"),"conflicting priority rejected despite cached prefix");
            check(ip.added==2,"conflict does not add a second rule");
            ip.rule="17891: from all to 10.126.126.0/24 lookup main\n";
            ip.prefix="10.126.127.0/24";ip.failDelete=true;
            reject(()->manager.ensureRoute("10.126.127.2"),"failed old route removal blocks new route");
            check(new String(Files.readAllBytes(state.resolve("mesh.route")),StandardCharsets.UTF_8).equals("10.126.126.0/24"),"old cleanup identity retained");
            check(ip.added==2,"no new rule after cleanup failure");
            manager.stop();
            check(Files.exists(state.resolve("mesh.route")),"stop failure retains cleanup file");
            check(String.valueOf(manager.status().get("problem")).contains("规则尚未清理"),"stop reports route cleanup failure");
            ip.failDelete=false;manager.stop();
            check(!Files.exists(state.resolve("mesh.route")),"retry confirms old rule removed");
            manager.ensureRoute("10.126.127.2");
            check(ip.added==3,"new route may start after confirmed cleanup");
            ip.rule="";manager.stop();
            check(!Files.exists(state.resolve("mesh.route")),"already absent rule cleans saved identity");
        } finally {
            try(java.util.stream.Stream<Path> entries=Files.list(state)) {for(Path file:(Iterable<Path>)entries::iterator)Files.delete(file);}
            Files.delete(state);
        }
    }
    private static final class FakeIp implements MeshManager.CommandRunner {
        String prefix="10.126.126.0/24",rule="";int added;boolean failDelete;
        public String run(int timeout,String... args) throws IOException {
            if(args[2].equals("route"))return prefix+" dev usblink0 proto kernel scope link\n";
            if(args[3].equals("show"))return "0: from all lookup local\n"+rule;
            if(args[3].equals("add")) {added++;rule="17891: from all to "+args[7]+" lookup main\n";return "";}
            if(args[3].equals("del")) {if(failDelete)throw new IOException("模拟规则删除失败");rule="";return "";}
            throw new IOException("不支持的测试命令");
        }
    }
    private static void check(boolean value,String name) {checks++;if(!value)throw new AssertionError(name);}
    private interface Failing {void run() throws Exception;}
    private static void reject(Failing action,String name) throws Exception {boolean failed=false;try {action.run();}catch(IOException e){failed=true;}check(failed,name);}
}
