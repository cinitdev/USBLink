package io.usblink.mobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
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
    interface InterfaceProbe {InterfaceState read() throws IOException;}
    interface ProcessStarter {Process start(Path module) throws IOException;}
    static final class InterfaceState {
        final int index;final boolean up;final List<String> addresses;
        InterfaceState(int index,boolean up,List<String> addresses){this.index=index;this.up=up;this.addresses=new ArrayList<>(addresses);}
        boolean has(String address){return index>0 && up && addresses.contains(address);}
    }
    private final CommandRunner runner;
    private final InterfaceProbe interfaceProbe;
    private final ProcessStarter processStarter;
    private MeshProfile profile;
    private Process process;
    private String ip,problem="尚未创建或加入加密连接",route;
    private boolean relayConnected,leaveRequested,enabled;
    private boolean statusDegraded;
    private int interfaceIndex;
    private String unavailableReason="network_unavailable";
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
        this(stateDir,moduleDir,runner,hostname,MeshManager::readInterface,MeshManager::startProcess);
    }
    MeshManager(Path stateDir,Path moduleDir,CommandRunner runner,InterfaceProbe probe,ProcessStarter starter) throws IOException {
        this(stateDir,moduleDir,runner,"USBLink-Android",probe,starter);
    }
    private MeshManager(Path stateDir,Path moduleDir,CommandRunner runner,String hostname,InterfaceProbe probe,ProcessStarter starter) throws IOException {
        this.runner=runner;this.interfaceProbe=probe;this.processStarter=starter;
        String name=DeviceNames.clean(hostname);this.hostname=name.isEmpty()?"USBLink-Android":name;
        state=stateDir;module=moduleDir;
        Files.createDirectories(state);
        privateMode(state,true);
        Path saved=state.resolve("mesh.json");
        if(Files.exists(saved)) {
            if(Files.size(saved)>4096)throw new IOException("网络配置文件无效");
            privateMode(saved,false);
            profile=MeshProfile.parse(new String(Files.readAllBytes(saved),StandardCharsets.UTF_8));
            problem=null;
        }
    }
    @Override public synchronized Map<String,Object> status() {
        return Json.map("configured",profile!=null,"running",ip!=null && relayConnected,"paused",!enabled,"localIp",ip,
            "networkName",profile==null?null:profile.name,"relay",relay(),"peers",new ArrayList<>(peers),
            "problem",problem,"statusDegraded",statusDegraded,"unavailableReason",ip==null?unavailableReason:null);
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
        boolean resume=enabled;
        stop();
        if(process!=null && process.isAlive())throw new IOException("旧网络进程尚未退出，请稍后重试");
        if(route!=null)throw new IOException("旧网络规则尚未清理，请稍后重试");
        writePrivate(state.resolve("mesh.json"),Json.stringify(replacement.json()));
        profile=replacement;leaveRequested=false;enabled=resume;retryAt=0;delaySeconds=2;problem=enabled?"正在启动加密网络":null;
    }
    @Override public synchronized void setEnabled(boolean next) throws IOException {
        if(next) {
            if(leaveRequested)throw new IOException("退出网络尚未完成，请先完成清理");
            if(!enabled){enabled=true;retryAt=0;delaySeconds=2;problem=profile==null?"请先创建或加入设备网络":"正在启动加密网络";}
        } else {
            stop();
            if(process!=null && process.isAlive())throw new IOException(problem);
            if(route!=null)throw new IOException(problem);
            problem=null;
        }
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
        if(!enabled || profile==null)return;
        long now=System.nanoTime()/1_000_000;
        if(process==null || !process.isAlive()) {
            unavailable("network_process_exited","加密网络进程已退出，正在等待恢复");
            if(!clearRoute()) {problem="旧网络规则尚未清理，请检查 Root 权限后重试";return;}
            if(process!=null) {process=null;retryAt=now+delaySeconds*1000L;delaySeconds=Math.min(60,delaySeconds*2);problem="加密网络进程已退出，正在等待恢复";}
            if(now<retryAt)return;
            try {
                writePrivate(state.resolve("mesh.toml"),toml(profile,hostname));
                process=processStarter.start(module);startedAt=now;problem="正在连接公共中继";
            }catch(IOException e) {retryAt=now+delaySeconds*1000L;delaySeconds=Math.min(60,delaySeconds*2);throw new IOException("加密网络启动失败，请检查模块组件和 Root 权限");}
            return;
        }
        String candidate=null;boolean handshake=false,observed=false;List<Object> fresh=new ArrayList<>();
        try {
            String text=command(3000,module.resolve("bin/easytier-cli").toString(),"-p",RPC,"-o","json","peer","list");
            Object decoded=Json.parse(text);
            if(decoded instanceof Map)decoded=Json.object(decoded).get("result");
            if(!(decoded instanceof List))throw new IOException("加密网络状态格式无效");
            for(Object value:(List<?>)decoded) {
                Map<String,Object> item=Json.object(value);
                String address=string(item,"ipv4"),name=string(item,"hostname"),cost=string(item,"cost");
                if(cost.equalsIgnoreCase("local")) {
                    if(validIpv4(address)) {
                        if(candidate!=null && !candidate.equals(address))throw new IOException("本机网络地址不明确");
                        candidate=address;
                    }
                    continue;
                }
                if(name.toLowerCase(java.util.Locale.ROOT).startsWith("publicserver")) {handshake=true;continue;}
                if(validIpv4(address))fresh.add(Json.map("name",name,"ip",address,"latency",string(item,"lat_ms")));
            }
            observed=candidate!=null;
        }catch(IOException|IllegalArgumentException e) {
            candidate=null;
        }
        // RPC describes peers/relay health, not the lifetime of an established USB socket.
        // On a failed/incomplete observation, reuse an address only after independent live
        // verification of this child, the same TUN interface/address and the owned route.
        if(!process.isAlive()){unavailable("network_process_exited","加密网络进程已退出，共享已暂停");return;}
        String address=observed?candidate:ip;
        InterfaceState link;
        try{link=interfaceProbe.read();}
        catch(IOException e){unavailable("network_interface_lost","无法读取设备网络接口，共享已暂停");return;}
        if(link==null || !link.up || link.index<=0 || (interfaceIndex!=0 && link.index!=interfaceIndex)) {
            unavailable("network_interface_lost","设备网络接口已断开或重建，共享已暂停");return;
        }
        if(address==null){unavailable("network_status_unconfirmed","等待确认本机虚拟网络地址");return;}
        if(!link.has(address)){unavailable("network_interface_lost","设备网络地址已移除或改变，共享已暂停");return;}
        try{ensureRoute(address);}
        catch(IOException e){
            unavailable(e instanceof RouteCheckException?((RouteCheckException)e).reason:"network_route_unavailable",
                e instanceof RouteCheckException?e.getMessage():"网络规则本地记录无法读取或更新，共享已暂停");return;
        }
        // Route/RPC commands may take time. Do not publish success if the owner or
        // interface disappeared while they ran; never treat a replaced interface as old.
        try {
            InterfaceState confirmed=interfaceProbe.read();
            if(!process.isAlive()){unavailable("network_process_exited","加密网络进程已退出，共享已暂停");return;}
            if(confirmed==null || confirmed.index!=link.index || !confirmed.has(address)) {
                unavailable("network_interface_lost","设备网络接口已断开或重建，共享已暂停");return;
            }
        }catch(IOException e){unavailable("network_interface_lost","无法读取设备网络接口，共享已暂停");return;}
        ip=address;interfaceIndex=link.index;statusDegraded=!observed;
        peers=observed?fresh:Collections.emptyList();relayConnected=observed && handshake;
        problem=!observed?"网络状态查询暂不可用；本机接口与路由正常，共享保持运行":
            handshake?null:now-startedAt>20000?"公共中继尚未连接，请检查网络或更换中继":"正在连接公共中继";
        if(now-startedAt>60000)delaySeconds=2;
    }
    private void unavailable(String reason,String message) {
        ip=null;interfaceIndex=0;relayConnected=false;statusDegraded=false;peers=Collections.emptyList();
        unavailableReason=reason;problem=message;
    }
    @Override public synchronized String localIp() {return ip;}
    @Override public synchronized String unavailableReason(){return unavailableReason;}
    @Override public synchronized byte[] presenceKey()throws IOException{return profile==null?null:PresenceServer.key(profile.secret);}
    @Override public synchronized String relay() {return profile==null?MeshProfile.DEFAULT_RELAY:profile.relay;}
    @Override public synchronized void stop() {
        enabled=false;
        unavailable("network_unavailable",null);
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
        String prefix=matchingRoute(readRouteState("route","show","dev","usblink0"),address);
        // Android can briefly expose an empty table while rebuilding its routes.
        if(prefix==null)prefix=matchingRoute(readRouteState("route","show","dev","usblink0"),address);
        if(prefix==null)throw new RouteCheckException("network_route_missing","设备网络缺少匹配的子网路由，共享已暂停");
        if(route!=null && !prefix.equals(route) && !clearRoute())throw new RouteCheckException("network_route_conflict","旧网络规则尚未清理，已取消更改路由");
        String rules=readRouteState("rule","show");
        boolean found=false;
        for(String line:rules.split("\\r?\\n")) {
            if(line.trim().startsWith("17891:")) {
                Path record=state.resolve("mesh.route");
                if(!exactRule(line,prefix) || !Files.exists(record) || Files.size(record)>64 ||
                    !new String(Files.readAllBytes(record),StandardCharsets.UTF_8).trim().equals(prefix))
                    throw new RouteCheckException("network_route_conflict","虚拟网络规则优先级或所有权记录冲突，共享已暂停");
                found=true;
            }
        }
        if(found) {route=prefix;return;}
        // Android networking and VPN changes can remove a previously installed rule.
        // Reconcile actual rules on every tick, even while the interface/IP is unchanged.
        writePrivate(state.resolve("mesh.route"),prefix);
        route=prefix; // Retain the cleanup identity even if add or confirmation fails.
        // An add command can time out after the kernel has applied it. Submit once,
        // then confirm actual state; never add again just because its exit was uncertain.
        try{command(4000,"/system/bin/ip","-4","rule","add","priority","17891","to",prefix,"lookup","main");}
        catch(IOException ignored){ /* Confirmation below remains mandatory. */ }
        String confirmed=readRouteState("rule","show");
        boolean ready=false;
        for(String line:confirmed.split("\\r?\\n"))if(line.trim().startsWith("17891:")) {
            if(!exactRule(line,prefix))throw new RouteCheckException("network_route_conflict","虚拟网络规则与其他程序冲突，共享已暂停");
            ready=true;
        }
        if(!ready)throw new RouteCheckException("network_route_missing","无法确认虚拟网络规则已生效，共享已暂停");
    }
    private static boolean exactRule(String line,String prefix) {
        // iproute2 builds can print table 254 instead of its alias main. Only
        // accept equivalent selectors; marks, interfaces and suppression remain conflicts.
        return line.trim().replaceAll("\\s+"," ").matches("17891: from (?:all|0\\.0\\.0\\.0/0) to "+
            java.util.regex.Pattern.quote(prefix)+" (?:lookup|table) (?:main|254)(?: (?:proto|protocol) [A-Za-z0-9_-]+)?");
    }
    private static String matchingRoute(String routes,String address) {
        for(String line:routes.split("\\r?\\n")) {
            String first=line.trim().split("\\s+")[0];
            if(validMeshRoute(first,address))return first;
        }
        return null;
    }
    private static final class RouteCheckException extends IOException {
        final String reason;
        RouteCheckException(String reason,String message){super(message);this.reason=reason;}
    }
    private String readRouteState(String... operation)throws IOException {
        String[] args=new String[operation.length+2];args[0]="/system/bin/ip";args[1]="-4";
        System.arraycopy(operation,0,args,2,operation.length);
        // One additional read-only observation resolves a transient command failure.
        // This neither repeats a route mutation nor imports/reconnects a USB device.
        try{return command(4000,args);}
        catch(IOException first) {
            try{return command(4000,args);}
            catch(IOException second){throw new RouteCheckException("network_route_query_failed","连续两次读取网络路由或规则失败／超时，共享已暂停");}
        }
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
    private static InterfaceState readInterface() throws IOException {
        NetworkInterface link=NetworkInterface.getByName("usblink0");
        if(link==null)return null;
        List<String> addresses=new ArrayList<>();
        for(InetAddress address:Collections.list(link.getInetAddresses()))if(address instanceof Inet4Address)addresses.add(address.getHostAddress());
        return new InterfaceState(link.getIndex(),link.isUp(),addresses);
    }
    private static Process startProcess(Path module) throws IOException {
        // Do not query another instance's RPC after a bind failure.
        try(ServerSocket probe=new ServerSocket()) {probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),15891));}
        ProcessBuilder builder=new ProcessBuilder("/system/bin/sh",module.resolve("bin/mesh-run.sh").toString(),module.toString());
        // EasyTier may print its parsed configuration on startup. Never retain that output.
        builder.redirectOutput(new File("/dev/null")).redirectError(new File("/dev/null"));
        builder.environment().keySet().removeIf(key->key.startsWith("ET_"));
        return builder.start();
    }
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
