package io.usblink.mobile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

public final class SharingStartupTestMain {
    private static int checks;
    public static void main(String[] args)throws Exception {
        readiness(); persistedOn(); cancelledStartup(); unstableSystem();
        System.out.println("PASS: "+checks+" reboot sharing startup checks");
    }
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static final class SystemState implements SharingStartup.SystemState {
        boolean boot,unavailable;long now;String rules="initial";
        public boolean bootCompleted(){return boot;}
        public String firewallSnapshot()throws IOException{if(unavailable)throw new IOException("busy");return rules;}
        public long elapsed(){return now;}
    }
    private static void readiness()throws Exception {
        SystemState system=new SystemState();SharingStartup gate=new SharingStartup(system);
        system.now=16000;check(gate.pending()!=null,"APatch service starts before Android is ready");
        system.now=29000;system.boot=true;check(gate.pending()!=null,"boot_completed alone is insufficient");
        system.now=34000;system.rules="netd rules";check(gate.pending()!=null,"netd rewrites reset stability");
        system.now=39000;system.rules="vendor jump first";check(gate.pending()!=null,"Vendor startup resets stability again");
        system.now=44000;check(gate.pending()!=null,"Wait for consecutive stable observations");
        system.now=45000;check(gate.pending()==null,"Stable completed boot permits first start");
        system.unavailable=true;check(gate.pending()==null,"Startup gate is one-time; runtime protection is checked separately");
    }
    static final class Phone implements Platform {
        final SystemState system=new SystemState();final SharingStartup gate=new SharingStartup(system);
        int starts,stops,maintenance;boolean protectedPort=true;
        public Map<String,Object> device(){return Json.map("model","Test Phone");}
        public void recoverTcp(){}
        public void startTcp(){starts++;}
        public int tcpPort()throws IOException{if(!protectedPort)throw new IOException("lost guard");return starts>0?32123:0;}
        public int maintainTcpPort()throws IOException{maintenance++;return tcpPort();}
        public String sharingStartupProblem()throws IOException{return gate.pending();}
        public void stopTcp(){stops++;}
        public void openDebugSettings(){}
    }
    private static void persistedOn()throws Exception {
        SharingStore store=new SharingStore(Files.createTempDirectory("usblink-reboot-on-"),3);store.save(true);
        Phone phone=new Phone();TestMain.FakeMesh mesh=new TestMain.FakeMesh();mesh.ip="127.0.0.1";
        try(ProxyPool proxy=new ProxyPool();Controller c=new Controller(phone,mesh,store,proxy,0)) {
            c.tick();check(store.load() && enabled(c) && state(c).equals("waiting_system"),"Persisted on stays on while boot is pending");
            check(!proxy.active("adb") && phone.starts==0,"No port mutation before boot readiness");
            for(int i=0;i<3;i++)c.handle("status",Json.map());
            check(phone.starts==0 && phone.maintenance==0,"Status reads do not start or repair anything");
            phone.system.boot=true;c.tick();phone.system.now=10_000;c.tick();
            check(state(c).equals("sharing") && proxy.active("adb") && phone.starts==1,"Boot readiness automatically restores saved sharing once");
            for(int i=0;i<3;i++)c.tick();
            check(phone.starts==1,"Subsequent ticks never restart adbd");
            phone.protectedPort=false;c.tick();
            check(state(c).equals("error") && !proxy.active("adb") && enabled(c),"Genuine missing protection still revokes sharing without falsifying preference");
            phone.protectedPort=true;c.tick();check(phone.starts==1 && !proxy.active("adb"),"Real failure is not hidden by an automatic retry");
        }
    }
    private static void cancelledStartup()throws Exception {
        SharingStore store=new SharingStore(Files.createTempDirectory("usblink-reboot-off-"),3);store.save(true);
        Phone phone=new Phone();TestMain.FakeMesh mesh=new TestMain.FakeMesh();mesh.ip="127.0.0.1";
        try(Controller c=new Controller(phone,mesh,store,new ProxyPool(),0)) {
            c.tick();c.handle("set-sharing",Json.map("enabled",false));
            phone.system.boot=true;phone.system.now=100000;c.tick();
            check(!store.load() && !enabled(c) && state(c).equals("off") && phone.starts==0,"Off during boot cancels pending sharing durably");
        }
        try(Controller c=new Controller(phone,mesh,store,new ProxyPool(),0)) {
            c.tick();check(!enabled(c) && phone.starts==0,"A second daemon retains the user's saved off");
        }
    }
    private static void unstableSystem()throws Exception {
        SystemState system=new SystemState();system.boot=true;system.unavailable=true;
        SharingStartup gate=new SharingStartup(system);
        check(gate.pending()!=null,"Temporary firewall command failure is pending during boot");
        system.now=2000;system.unavailable=false;check(gate.pending()!=null,"Recovered firewall starts a new stability window");
        system.now=10000;check(gate.pending()==null,"Readiness recovers without retrying a sharing mutation");
        system=new SystemState();system.boot=true;gate=new SharingStartup(system);
        for(int i=0;i<30;i++){system.now=i*2000;system.rules="generation-"+i;check(gate.pending()!=null,"Wait during network reconfiguration");}
        system.now=60000;system.rules="still changing";
        try{gate.pending();throw new AssertionError("Unbounded waiting");}catch(IOException expected){checks++;}
    }
    private static boolean enabled(Controller c)throws IOException{return (Boolean)Json.object(c.status().get("sharing")).get("enabled");}
    private static String state(Controller c)throws IOException{return (String)Json.object(c.status().get("sharing")).get("state");}
}
