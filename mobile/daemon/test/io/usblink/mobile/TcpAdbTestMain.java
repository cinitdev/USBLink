package io.usblink.mobile;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class TcpAdbTestMain {
    private static int checks;
    public static void main(String[] args)throws Exception {
        lifecycle();failureRecovery();conflicts();firewall();firewallMaintenance();
        System.out.println("PASS: "+checks+" system adbd TCP safety checks");
    }
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Task{void run()throws Exception;}
    static void reject(Task task,String message)throws Exception{boolean failed=false;try{task.run();}catch(IOException e){failed=true;}check(failed,message);}
    static Path dir()throws IOException{return Files.createTempDirectory("usblink-tcp-test-");}
    static void lifecycle()throws Exception {
        FakeSystem system=new FakeSystem();Path path=dir();TcpAdb tcp=new TcpAdb(path,system);system.path=path;
        tcp.restore();check(system.events.isEmpty(),"No saved operation never touches existing adbd");
        tcp.start();check(system.events.equals(Arrays.asList("protect","set:15558","restart")),"Firewall before port/restart");
        check(tcp.port()==15558,"Verified adbd listener is ready");
        for(int i=0;i<5;i++)tcp.port();check(system.restarts==1,"Read-only confirmation never restarts adbd");
        reject(tcp::start,"No second start while record exists");
        tcp.restore();check(system.tcp.equals("") && !system.guard && !Files.exists(path.resolve("adbd-tcp.json")),"Stop restores empty original property and removes owned rules");
        check(system.restarts==2,"One restart for enabling and one restoring");
        tcp.start();tcp.restore();check(system.restarts==4,"Re-enable without reboot");
        system.tcp="5555";tcp.start();tcp.restore();check(system.tcp.equals("5555"),"Existing user TCP setting preserved");
        system.delay=true;tcp.start();check(tcp.port()==0,"Wait for actual listener after restart");
        system.now=11000;reject(tcp::port,"Listener timeout is failure, never endless success");system.delay=false;tcp.restore();
        check(!system.guard,"Pending start may still be stopped safely");
    }
    static void failureRecovery()throws Exception {
        for(String failure:Arrays.asList("guard","property","restart")) {
            FakeSystem system=new FakeSystem();Path path=dir();system.path=path;TcpAdb tcp=new TcpAdb(path,system);system.fail=failure;
            reject(tcp::start,"Start failure at "+failure);
            check(Files.exists(path.resolve("adbd-tcp.json")),"Recovery identity retained at "+failure);
            if(failure.equals("guard"))check(system.tcp.equals("") && system.restarts==0,"Unsupported firewall never opens adbd");
            system.fail="";new TcpAdb(path,system).restore();check(!Files.exists(path.resolve("adbd-tcp.json")),"Crash recovery at "+failure);
        }
        FakeSystem system=new FakeSystem();Path path=dir();system.path=path;TcpAdb tcp=new TcpAdb(path,system);tcp.start();
        system.fail="restart";reject(tcp::restore,"Restart failure during restore");check(system.guard,"Failed restore retains protection");
        system.fail="";new TcpAdb(path,system).restore();check(!system.guard && system.tcp.equals(""),"Recovery handles restored property with old live listener");
        tcp.start();system.keepListening=true;reject(tcp::restore,"Do not remove guard before listener is gone");check(system.guard && Files.exists(path.resolve("adbd-tcp.json")),"Stuck adbd keeps journal and firewall");
        system.keepListening=false;tcp.restore();check(!system.guard,"Retry cleanup succeeds");
        tcp.start();system.boot="22222222-2222-2222-2222-222222222222";system.tcp="7777";system.listening=false;int before=system.restarts;tcp.restore();
        check(system.tcp.equals("7777") && system.restarts==before,"New boot never replays stale service property");
    }
    static void conflicts()throws Exception {
        FakeSystem system=new FakeSystem();Path path=dir();system.path=path;TcpAdb tcp=new TcpAdb(path,system);
        system.auth=false;reject(tcp::start,"Authentication disabled is blocked");check(!Files.exists(path.resolve("adbd-tcp.json")),"Auth check before any mutation");system.auth=true;
        system.address="tcp:0.0.0.0:5555";reject(tcp::start,"Custom listen address not overwritten");system.address="";
        system.persistent="15558";reject(tcp::start,"Persistent fixed port conflict rejected");system.persistent="";
        system.listening=true;reject(tcp::start,"Occupied port not stolen");system.listening=false;
        system.changePersistentOnProtect=true;reject(tcp::start,"External persistent change during protection cancels start");
        check(system.restarts==0 && system.tcp.equals(""),"Late conflict never changes service property or restarts adbd");
        system.changePersistentOnProtect=false;system.persistent="";tcp.restore();
        tcp.start();system.tcp="8888";reject(tcp::port,"External property change revokes readiness");reject(tcp::restore,"External property not overwritten during cleanup");check(system.tcp.equals("8888") && system.guard,"External configuration and restrictive rules preserved");system.tcp="15558";tcp.restore();
        tcp.start();system.persistent="6666";reject(tcp::restore,"Persistent config change is not silently activated");system.persistent="";tcp.restore();
        tcp.start();system.guard=false;reject(tcp::port,"Lost firewall fails closed");tcp.restore();check(!system.guard && !system.listening,"Recovery reinstalls guard before stopping adbd");
        Files.writeString(path.resolve("adbd-tcp.json"),"broken");reject(tcp::restore,"Malformed recovery record does not guess prior state");
        check(!TcpAdb.validProperty("1;reboot") && !TcpAdb.validProperty("65536") && TcpAdb.validProperty("-1"),"Only valid fixed property values");
    }
    static void firewall()throws Exception {
        FirewallRunner runner=new FirewallRunner();TcpFirewall firewall=new TcpFirewall(runner);
        firewall.install();check(runner.rules.size()==8,"Protect internal, mesh, local clients in both IP families");
        int inserted=runner.inserts;firewall.install();check(runner.inserts==inserted,"Stable rules not duplicated");
        runner.insertAccept=true;reject(firewall::verify,"Earlier ACCEPT rules invalidate protection");firewall.install();firewall.verify();check(runner.inserts>inserted,"Reestablish precedence without removing old protection first");
        firewall.remove();check(runner.rules.isEmpty(),"Only exact tagged module rules removed including duplicates");
        runner.failIpv6=true;reject(firewall::install,"No IPv6 protection means no shared listener");
        check(runner.calls.stream().noneMatch(s->s.contains(" -F ")||s.contains(" -P ")),"Never flush tables or change chain policy");
    }
    static void firewallMaintenance()throws Exception {
        FirewallRunner runner=new FirewallRunner();TcpFirewall firewall=new TcpFirewall(runner);firewall.install();
        FakeSystem system=new FakeSystem();Path path=dir();system.path=path;
        system.firewall=firewall;TcpAdb tcp=new TcpAdb(path,system);tcp.start();
        int starts=system.restarts,inserted=runner.inserts;
        firewall.maintain();check(runner.inserts==inserted,"Unchanged firewall is not rewritten");
        runner.insertAccept=true;reject(tcp::port,"Observation cannot silently fix rule displacement");
        check(runner.inserts==inserted,"Read-only observation does not write iptables");
        check(tcp.maintainPort()==15558,"Service maintenance restores priority before confirming readiness");
        check(system.restarts==starts && system.events.equals(Arrays.asList("protect","set:15558","restart")),"Order repair never restarts adbd or changes the port");
        firewall.verify();check(runner.inserts>inserted,"Restrictive replacements precede vendor ACCEPT rules");
        runner.formatted=true;firewall.verify();check(true,"Real iptables formatting preserves exact rule verification");
        runner.spoofTags=true;reject(firewall::verify,"Matching comments on ACCEPT rules do not prove protection");runner.spoofTags=false;
        runner.insertAccept=true;runner.rules.removeIf(rule->rule.startsWith("/system/bin/ip6tables "));
        inserted=runner.inserts;reject(firewall::maintain,"IPv4 reordering cannot hide missing IPv6 protection");
        check(runner.inserts==inserted,"Genuinely missing rules are never automatically recreated");
        runner.insertAccept=false;firewall.install();
        runner.insertAccept=true;runner.dropIpv6OnList=true;inserted=runner.inserts;
        reject(firewall::maintain,"Rules lost between observation and repair still fail closed");
        check(runner.inserts==inserted,"Late protection loss does not fall through to initial installation");
        runner.insertAccept=false;firewall.install();
        // One completed repair above; permit two more, then surface persistent interference.
        for(int i=0;i<2;i++){runner.insertAccept=true;firewall.maintain();}
        runner.insertAccept=true;inserted=runner.inserts;reject(firewall::maintain,"Repeated external rewrites are bounded");
        check(runner.inserts==inserted,"No unbounded rule accumulation");runner.insertAccept=false;
        tcp.restore();check(runner.rules.isEmpty(),"All exact owned duplicates removed on stop");
    }
    static final class FakeSystem implements TcpAdb.SystemAccess {
        Path path;String tcp="",persistent="",address="",boot="11111111-1111-1111-1111-111111111111",fail="";
        boolean auth=true,guard,listening,delay,keepListening,changePersistentOnProtect;int restarts;long now;List<String> events=new ArrayList<>();TcpFirewall firewall;
        public String property(String name){switch(name){case "service.adb.tcp.port":return tcp;case "persist.adb.tcp.port":return persistent;case "service.adb.listen_addrs":return address;case "init.svc.adbd":return "running";default:return "";}}
        public void tcpProperty(String value)throws IOException{check(Files.exists(path.resolve("adbd-tcp.json")),"Journal durable before property changes");if(fail.equals("property"))throw new IOException("模拟属性失败");events.add("set:"+value);tcp=value;}
        public void restart()throws IOException{if(fail.equals("restart"))throw new IOException("模拟重启失败");check(guard,"Never restart without guard");events.add("restart");restarts++;if(!keepListening)listening=tcp.equals("15558")&&!delay;}
        public boolean listening(int port){return listening;}
        public boolean ownedListener(int port){return listening;}
        public void protect()throws IOException{if(fail.equals("guard"))throw new IOException("模拟防护失败");events.add("protect");guard=true;if(changePersistentOnProtect)persistent="6666";}
        public void verifyProtection()throws IOException{if(!guard)throw new IOException("模拟防护丢失");if(firewall!=null)firewall.verify();}
        public void maintainProtection()throws IOException{if(firewall!=null)firewall.maintain();else verifyProtection();}
        public void unprotect()throws IOException{events.add("unprotect");guard=false;if(firewall!=null)firewall.remove();}
        public void requireAuthorization()throws IOException{if(!auth)throw new IOException("模拟认证关闭");}
        public String bootId(){return boot;}public long elapsed(){return now;}public void pause(){now+=100;}
    }
    static final class FirewallRunner implements TcpFirewall.Runner {
        List<String> calls=new ArrayList<>(),rules=new ArrayList<>();int inserts;boolean insertAccept,failIpv6,formatted,spoofTags,dropIpv6OnList;
        public String run(String... args)throws IOException {
            calls.add(String.join(" ",args));String binary=args[0],op=args[3],chain=args[4];
            if(failIpv6&&binary.endsWith("ip6tables"))throw new IOException("IPv6 unavailable");
            if(op.equals("-S")) {
                if(dropIpv6OnList){dropIpv6OnList=false;rules.removeIf(rule->rule.startsWith("/system/bin/ip6tables "));}
                List<String> selected=new ArrayList<>();
                for(String rule:rules)if(rule.startsWith(binary+" "+chain+" "))selected.add("-A "+rule.substring(binary.length()+1));
                if(insertAccept)selected.add(0,"-A "+chain+" -j ACCEPT");
                String text=String.join("\n",selected);
                if(formatted)text=text.replace(" -p tcp "," -p tcp -m tcp ").replaceAll("--comment (usblink-[a-z-]+)","--comment \"$1\"").replace("-j REJECT","-j REJECT --reject-with "+(binary.endsWith("ip6tables")?"icmp6-port-unreachable":"icmp-port-unreachable"));
                if(spoofTags)text=text.replace("-j DROP","-j ACCEPT");
                return text;
            }
            int start=op.equals("-I")?6:5;
            String key=binary+" "+chain+" "+String.join(" ",Arrays.copyOfRange(args,start,args.length));
            if(op.equals("-C")){if(!rules.contains(key))throw new IOException("absent");}
            else if(op.equals("-I")){rules.add(0,key);inserts++;insertAccept=false;}
            else if(op.equals("-D")){if(!rules.remove(key))throw new IOException("absent");}
            else throw new IOException("unexpected command");return "";
        }
    }
}
