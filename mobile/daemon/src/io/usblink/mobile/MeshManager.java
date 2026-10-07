package io.usblink.mobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Owns one EasyTier child; never manages adbd or changes Android debugging settings. */
public final class MeshManager implements MeshNetwork {
    private static final String RPC="127.0.0.1:15891";
    private final Path state,module;
    private final String hostname;
    interface CommandRunner {String run(int timeout,String... args) throws IOException;}
    private final CommandRunner runner;
    private MeshProfile profile;
    private Process process;
    private String ip,problem="尚未创建或加入加密连接",route;
    private boolean relayConnected,leaveRequested;
    private List<Object> peers=Collections.emptyList();
    private long retryAt,startedAt;
    private int delaySeconds=2;
    public MeshManager(Path stateDir,Path moduleDir) throws IOException {
        this(stateDir,moduleDir,MeshManager::runCommand);
    }
    public MeshManager(Path stateDir,Path moduleDir,String hostname) throws IOException {
        this(stateDir,moduleDir,MeshManager::runCommand,hostname);
    }
    MeshManager(Path stateDir,Path moduleDir,CommandRunner runner) throws IOException {
        this(stateDir,moduleDir,runner,"USBLink-Android");
    }
    private MeshManager(Path stateDir,Path moduleDir,CommandRunner runner,String hostname) throws IOException {
        this.runner=runner;
        String name=DeviceNames.clean(hostname);this.hostname=name.isEmpty()?"USBLink-Android":name;
        state=stateDir;module=moduleDir;
        Files.createDirectories(state);
        privateMode(state,true);
        Path saved=state.resolve("mesh.json");
        if(Files.exists(saved)) {
            if(Files.size(saved)>4096)throw new IOException("网络配置文件无效");
            privateMode(saved,false);
            profile=MeshProfile.parse(new String(Files.readAllBytes(saved),StandardCharsets.UTF_8));
            problem="正在启动加密网络";
        }
    }
    @Override public synchronized Map<String,Object> status() {
        return Json.map("configured",profile!=null,"running",ip!=null && relayConnected,"localIp",ip,
            "networkName",profile==null?null:profile.name,"relay",relay(),"peers",new ArrayList<>(peers),
            "problem",problem);
    }
    @Override public synchronized String create() throws IOException {replace(MeshProfile.create());return profile.encode();}
    @Override public synchronized void join(String code) throws IOException {replace(MeshProfile.decode(code));}
    @Override public synchronized String pairingCode() throws IOException {
        if(profile==null)throw new IOException("请先创建或加入连接");return profile.encode();
    }
    @Override public synchronized void setRelay(String relay) throws IOException {
        if(profile==null)throw new IOException("请先创建或加入连接");
        replace(new MeshProfile(profile.name,profile.secret,MeshProfile.validateRelay(relay.trim())));
    }
    private void replace(MeshProfile replacement) throws IOException {
        stop();
        if(process!=null && process.isAlive())throw new IOException("旧网络进程尚未退出，请稍后重试");
        if(route!=null)throw new IOException("旧网络规则尚未清理，请稍后重试");
        writePrivate(state.resolve("mesh.json"),Json.stringify(replacement.json()));
        profile=replacement;leaveRequested=false;retryAt=0;delaySeconds=2;problem="正在启动加密网络";
    }
    @Override public synchronized void leave() throws IOException {
        leaveRequested=true;
        finishLeave();
    }
    private void finishLeave() throws IOException {
        stop();
        if(process!=null && process.isAlive()) {problem="退出连接未完成，旧网络进程尚未退出，请重试";throw new IOException(problem);}
        if(route!=null) {problem="退出连接未完成，旧网络规则尚未清理，请重试";throw new IOException(problem);}
        try {Files.deleteIfExists(state.resolve("mesh.json"));Files.deleteIfExists(state.resolve("mesh.toml"));}
        catch(IOException e) {problem="退出连接的配置删除失败，请检查数据目录后重试";throw new IOException(problem);}
        profile=null;problem="尚未创建或加入加密连接";
    }
    @Override public synchronized void tick() throws IOException {
        // A failed exit is a cleanup request, never permission to restart the old mesh.
        if(leaveRequested) {finishLeave();return;}
        if(profile==null)return;
        long now=System.nanoTime()/1_000_000;
        if(process==null || !process.isAlive()) {
            ip=null;relayConnected=false;peers=Collections.emptyList();
            if(!clearRoute()) {problem="旧网络规则尚未清理，请检查 Root 权限后重试";return;}
            if(process!=null) {process=null;retryAt=now+delaySeconds*1000L;delaySeconds=Math.min(60,delaySeconds*2);problem="加密网络进程已退出，正在等待恢复";}
            if(now<retryAt)return;
            try {
                // Do not query another instance's RPC after a bind failure.
                try(ServerSocket probe=new ServerSocket()) {probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),15891));}
                writePrivate(state.resolve("mesh.toml"),toml(profile,hostname));
                ProcessBuilder builder=new ProcessBuilder("/system/bin/sh",module.resolve("bin/mesh-run.sh").toString(),module.toString());
                // EasyTier may print its parsed configuration on startup. Never retain that output.
                builder.redirectOutput(new File("/dev/null")).redirectError(new File("/dev/null"));
                builder.environment().keySet().removeIf(key->key.startsWith("ET_"));
                process=builder.start();startedAt=now;problem="正在连接公共中继";
            }catch(IOException e) {retryAt=now+delaySeconds*1000L;delaySeconds=Math.min(60,delaySeconds*2);throw new IOException("加密网络启动失败，请检查模块组件和 Root 权限");}
            return;
        }
        try {
            String text=command(3000,module.resolve("bin/easytier-cli").toString(),"-p",RPC,"-o","json","peer","list");
            Object decoded=Json.parse(text);
            if(decoded instanceof Map)decoded=Json.object(decoded).get("result");
            if(!(decoded instanceof List))throw new IOException("加密网络状态格式无效");
            String candidate=null;boolean handshake=false;List<Object> fresh=new ArrayList<>();
            for(Object value:(List<?>)decoded) {
                Map<String,Object> item=Json.object(value);
                String address=string(item,"ipv4"),name=string(item,"hostname"),cost=string(item,"cost");
                if(cost.equalsIgnoreCase("local")) {if(validIpv4(address))candidate=address;continue;}
                if(name.toLowerCase(java.util.Locale.ROOT).startsWith("publicserver")) {handshake=true;continue;}
                if(validIpv4(address))fresh.add(Json.map("name",name,"ip",address,"latency",string(item,"lat_ms")));
            }
            if(candidate==null) {ip=null;clearRoute();problem="本机网络服务已响应，等待分配虚拟地址";}
            else {
                ensureRoute(candidate);ip=candidate;
                problem=handshake?null:now-startedAt>20000?"公共中继尚未连接，请检查网络或更换中继":"正在连接公共中继";
            }
            peers=fresh;relayConnected=handshake;
            if(now-startedAt>60000)delaySeconds=2;
        }catch(IOException|IllegalArgumentException e) {
            ip=null;relayConnected=false;peers=Collections.emptyList();
            problem="无法确认加密网络状态，请检查 Root 权限、TUN 或端口占用";
        }
    }
    @Override public synchronized String localIp() {return ip;}
    @Override public synchronized byte[] presenceKey()throws IOException{return profile==null?null:PresenceServer.key(profile.secret);}
    @Override public synchronized String relay() {return profile==null?MeshProfile.DEFAULT_RELAY:profile.relay;}
    @Override public synchronized void stop() {
        ip=null;relayConnected=false;peers=Collections.emptyList();
        if(process!=null) {
            process.destroy();
            try {if(!process.waitFor(1500,TimeUnit.MILLISECONDS)) {process.destroyForcibly();process.waitFor(1000,TimeUnit.MILLISECONDS);}}
            catch(InterruptedException e) {Thread.currentThread().interrupt();process.destroyForcibly();}
            if(process.isAlive()) {problem="网络进程未能停止，请在模块管理器重试停止";return;}
            process=null;
        }
        if(!clearRoute())problem="旧网络规则尚未清理，请检查 Root 权限后重试";
    }
    static String toml(MeshProfile profile) {
        return toml(profile,"USBLink-Android");
    }
    static String toml(MeshProfile profile,String hostname) {
        String name=DeviceNames.clean(hostname);if(name.isEmpty())name="USBLink-Android";
        return "instance_name = \"usblink-mobile\"\nhostname = "+Json.stringify(name)+"\ndhcp = true\n"+
            "listeners = [\"tcp://0.0.0.0:11010\", \"udp://0.0.0.0:11010\"]\n"+
            "tcp_whitelist = [\""+(TransportFactory.USB?"3240-3241":"3241-3242")+"\"]\n"+
            "[network_identity]\nnetwork_name = \""+profile.name+"\"\nnetwork_secret = \""+profile.secret+"\"\n"+
            "[[peer]]\nuri = \""+profile.relay+"\"\n[[peer]]\nuri = \""+MeshProfile.FALLBACK_RELAY+"\"\n"+
            "[flags]\nforeign_network_whitelist = \""+profile.name+"\"\n";
    }
    void ensureRoute(String address) throws IOException {
        String routes=command(1500,"/system/bin/ip","-4","route","show","dev","usblink0");
        String prefix=null;
        for(String line:routes.split("\\r?\\n")) {
            String first=line.trim().split("\\s+")[0];
            if(validMeshRoute(first,address)) {prefix=first;break;}
        }
        if(prefix==null)throw new IOException("虚拟网络路由尚未就绪");
        if(route!=null && !prefix.equals(route) && !clearRoute())throw new IOException("旧网络规则尚未清理，已取消更改路由");
        String rules=command(1500,"/system/bin/ip","-4","rule","show");
        boolean found=false;
        for(String line:rules.split("\\r?\\n")) {
            if(line.trim().startsWith("17891:")) {
                Path record=state.resolve("mesh.route");
                if(!exactRule(line,prefix) || !Files.exists(record) || Files.size(record)>64 ||
                    !new String(Files.readAllBytes(record),StandardCharsets.UTF_8).trim().equals(prefix))
                    throw new IOException("虚拟网络规则优先级已被其他程序使用");
                found=true;
            }
        }
        if(found) {route=prefix;return;}
        // Android networking and VPN changes can remove a previously installed rule.
        // Reconcile actual rules on every tick, even while the interface/IP is unchanged.
        writePrivate(state.resolve("mesh.route"),prefix);
        route=prefix; // Retain the cleanup identity even if add or confirmation fails.
        command(1500,"/system/bin/ip","-4","rule","add","priority","17891","to",prefix,"lookup","main");
        String confirmed=command(1500,"/system/bin/ip","-4","rule","show");
        boolean ready=false;
        for(String line:confirmed.split("\\r?\\n"))if(line.trim().startsWith("17891:")) {
            if(!exactRule(line,prefix))throw new IOException("虚拟网络规则与其他程序冲突");
            ready=true;
        }
        if(!ready)throw new IOException("无法确认虚拟网络规则已生效");
    }
    private static boolean exactRule(String line,String prefix) {
        return line.trim().replaceAll("\\s+"," ").equals("17891: from all to "+prefix+" lookup main");
    }
    private boolean clearRoute() {
        if(route!=null) {
            try {
                try {command(1500,"/system/bin/ip","-4","rule","del","priority","17891","to",route,"lookup","main");}
                catch(IOException ignored) { /* A rule already removed by Android is also clean. Confirm below. */ }
                String rules=command(1500,"/system/bin/ip","-4","rule","show");
                for(String line:rules.split("\\r?\\n"))if(exactRule(line,route))return false;
                Files.deleteIfExists(state.resolve("mesh.route"));route=null;
            }catch(IOException ignored) {return false;}
        }
        return true;
    }
    static boolean validIpv4(String value) {
        if(value==null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;
        String[] parts=value.split("\\.");for(String part:parts)if(Integer.parseInt(part)>255)return false;
        return !parts[0].equals("0") && !parts[0].equals("127") && Integer.parseInt(parts[0])<224;
    }
    static boolean validMeshRoute(String cidr,String address) {
        if(!validIpv4(address) || !cidr.matches("[0-9.]+/[0-9]{1,2}"))return false;
        String network=cidr.substring(0,cidr.indexOf('/'));
        if(!validIpv4(network))return false;
        int prefix=Integer.parseInt(cidr.substring(cidr.indexOf('/')+1));
        if(prefix<16 || prefix>30)return false;
        String[] parts=network.split("\\.");
        int first=Integer.parseInt(parts[0]),second=Integer.parseInt(parts[1]);
        if(!(first==10 || first==172 && second>=16 && second<=31 || first==192 && second==168))return false;
        long mask=(0xffffffffL<<(32-prefix))&0xffffffffL;
        return (ipv4Number(network)&mask)==ipv4Number(network) && (ipv4Number(address)&mask)==ipv4Number(network);
    }
    private static long ipv4Number(String address) {long n=0;for(String part:address.split("\\."))n=(n<<8)|Integer.parseInt(part);return n;}
    private static String string(Map<String,Object> map,String name) {Object v=map.get(name);return v==null?"":String.valueOf(v);}
    private static void privateMode(Path path,boolean directory) throws IOException {
        try {Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}
        catch(UnsupportedOperationException e) {if(File.separatorChar!='/')return;throw new IOException("无法保护网络配置权限");}
    }
    private static void writePrivate(Path target,String text) throws IOException {
        Path temporary=target.resolveSibling(target.getFileName()+".new");
        if(!Files.exists(temporary))Files.createFile(temporary);
        privateMode(temporary,false);Files.write(temporary,text.getBytes(StandardCharsets.UTF_8));
        try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e) {Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    private String command(int timeout,String... args) throws IOException {return runner.run(timeout,args);}
    private static String runCommand(int timeout,String... args) throws IOException {
        Process p=new ProcessBuilder(args).redirectError(new File("/dev/null")).start();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try(InputStream stream=p.getInputStream()) {byte[] b=new byte[2048];int n;while((n=stream.read(b))!=-1) {if(bytes.size()+n>65536){p.destroy();return;}bytes.write(b,0,n);}}catch(IOException ignored) { }},"usblink-mesh-status");
        reader.setDaemon(true);reader.start();
        try {
            if(!p.waitFor(timeout,TimeUnit.MILLISECONDS)) {p.destroyForcibly();throw new IOException("网络组件响应超时");}
            reader.join(500);
            if(reader.isAlive() || p.exitValue()!=0)throw new IOException("网络组件操作失败");
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }catch(InterruptedException e) {Thread.currentThread().interrupt();p.destroyForcibly();throw new IOException("网络操作被中断");}
    }
}
