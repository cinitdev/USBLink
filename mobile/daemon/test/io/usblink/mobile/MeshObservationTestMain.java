package io.usblink.mobile;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** No Android/EasyTier commands: simulate observations of an owned live child and TUN. */
public final class MeshObservationTestMain {
    private static int checks;
    public static void main(String[] args)throws Exception {
        metadataFailures();initialObservation();networkLoss();races();routeObservations();
        System.out.println("PASS: "+checks+" network observation checks");
    }
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static void metadataFailures()throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready();String code=f.mesh.pairingCode();
            for(String failure:new String[]{"timeout","malformed","incomplete","wrong-shape"}) {
                f.rpc=failure;
                for(int i=0;i<20;i++)f.mesh.tick();
                check(Fixture.IP.equals(f.mesh.localIp()),failure+" preserves a freshly verified local transport");
                Map<String,Object> status=f.mesh.status();
                check(Boolean.TRUE.equals(status.get("statusDegraded")),failure+" is visible as degraded observation");
                check(Boolean.FALSE.equals(status.get("running")) && ((List<?>)status.get("peers")).isEmpty(),failure+" never keeps stale relay or peer success");
                check(f.starts==1 && f.child.alive && f.added==1,failure+" never restarts the mesh or duplicates routes");
            }
            check(f.mesh.pairingCode().equals(code),"Observation failures preserve the pairing code");
            f.rpc="ok";f.mesh.tick();
            check(Boolean.TRUE.equals(f.mesh.status().get("running")) && Boolean.FALSE.equals(f.mesh.status().get("statusDegraded")),"Fresh metadata clears the degraded state");
            f.rpc="no-relay";f.mesh.tick();
            check(Fixture.IP.equals(f.mesh.localIp()) && Boolean.FALSE.equals(f.mesh.status().get("running")),"Relay loss alone does not revoke a live direct transport or claim relay success");
            f.rpc="timeout";f.rule="";f.mesh.tick();
            check(Fixture.IP.equals(f.mesh.localIp()) && f.added==2,"Independent route reconciliation still restores an Android-removed rule");
            f.mesh.setEnabled(false);int calls=f.calls;
            for(int i=0;i<20;i++)f.mesh.tick();
            check(f.mesh.localIp()==null && !f.child.alive && f.calls==calls,"Explicit off stays off without polling/restarting");
            check(f.mesh.pairingCode().equals(code),"Off preserves exact pairing");
        }
    }
    private static void initialObservation()throws Exception {
        try(Fixture f=new Fixture()) {
            f.rpc="timeout";f.start();f.mesh.tick();
            check(f.mesh.localIp()==null,"A live interface alone cannot establish an unknown initial address");
            check(f.added==0 && f.mesh.unavailableReason().equals("network_status_unconfirmed"),"No route is authorized by a saved profile or failed first RPC");
            f.rpc="ok";f.mesh.tick();check(Fixture.IP.equals(f.mesh.localIp()),"A fresh matching RPC/interface may establish readiness");
        }
    }
    private static void networkLoss()throws Exception {
        for(String loss:new String[]{"missing","down","address","replacement","probe-error","route-error","conflict","process"}) {
            try(Fixture f=new Fixture()) {
                f.ready();f.rpc="timeout";
                switch(loss) {
                    case "missing":f.link=null;break;
                    case "down":f.link=new MeshManager.InterfaceState(7,false,List.of(Fixture.IP));break;
                    case "address":f.link=new MeshManager.InterfaceState(7,true,List.of("10.126.126.3"));break;
                    case "replacement":f.link=new MeshManager.InterfaceState(8,true,List.of(Fixture.IP));break;
                    case "probe-error":f.probeError=true;break;
                    case "route-error":f.routeError=true;break;
                    case "conflict":f.rule="17891: from all to 192.168.5.0/24 lookup main\n";break;
                    case "process":f.child.alive=false;break;
                }
                f.mesh.tick();
                check(f.mesh.localIp()==null,loss+" revokes availability even during an RPC failure");
                String expected=loss.equals("process")?"network_process_exited":loss.equals("route-error")?"network_route_query_failed":loss.equals("conflict")?"network_route_conflict":"network_interface_lost";
                check(f.mesh.unavailableReason().equals(expected),loss+" reports its concrete cause");
                check(Boolean.FALSE.equals(f.mesh.status().get("running")) && ((List<?>)f.mesh.status().get("peers")).isEmpty(),loss+" clears peer/relay claims");
                check(f.starts==1,loss+" does not start a replacement process in this observation");
            }
        }
        try(Fixture f=new Fixture()) {
            f.ready();f.link=new MeshManager.InterfaceState(8,true,List.of(Fixture.IP));f.mesh.tick();
            check(f.mesh.localIp()==null,"Even successful RPC cannot reuse an old session after same-IP interface replacement");
            f.mesh.tick();check(Fixture.IP.equals(f.mesh.localIp()),"Next fresh observation can prepare a new listener for a manual connection");
        }
    }
    private static void races()throws Exception {
        for(String race:new String[]{"rpc-exit","route-exit","route-interface"}) {
            try(Fixture f=new Fixture()) {
                f.ready();f.race=race;f.mesh.tick();
                check(f.mesh.localIp()==null,race+" cannot publish cached success after blocking commands");
                check(f.mesh.unavailableReason().equals(race.equals("route-interface")?"network_interface_lost":"network_process_exited"),race+" records actual loss");
            }
        }
    }
    private static void routeObservations()throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready();
            f.routeFailures=1;f.mesh.tick();
            check(Fixture.IP.equals(f.mesh.localIp()) && f.routeFailures==0,"One route read failure is confirmed without dropping the session");
            f.emptyRoutes=1;f.mesh.tick();
            check(Fixture.IP.equals(f.mesh.localIp()),"One incomplete route snapshot is confirmed read-only");
            f.ruleFailures=1;f.mesh.tick();
            check(Fixture.IP.equals(f.mesh.localIp()) && f.added==1,"One rule read failure never adds a duplicate rule");
            f.rule="17891: from 0.0.0.0/0 to 10.126.126.0/24 lookup 254 proto static\n";f.mesh.tick();
            check(Fixture.IP.equals(f.mesh.localIp()) && f.added==1,"Equivalent table/protocol output is accepted without weakening selectors");
            f.rule="";f.addErrorAfterCommit=true;f.mesh.tick();
            check(Fixture.IP.equals(f.mesh.localIp()) && f.added==2,"Uncertain rule add is confirmed, not resubmitted");
            f.rule="17891: from all to 10.126.126.0/24 lookup main suppress_prefixlength 0\n";f.mesh.tick();
            check(f.mesh.localIp()==null && f.mesh.unavailableReason().equals("network_route_conflict"),"Additional routing selectors remain a conflict");
        }
        try(Fixture f=new Fixture()) {
            f.routeFailures=2;f.start();f.mesh.tick();
            check(f.mesh.localIp()==null && f.mesh.unavailableReason().equals("network_route_query_failed"),"Initial readiness cannot use stale state after two failed reads");
            f.rpc="ok";f.emptyRoutes=2;f.mesh.tick();
            check(f.mesh.localIp()==null && f.mesh.unavailableReason().equals("network_route_missing"),"Confirmed route absence remains unavailable");
        }
    }
    static final class Fixture implements AutoCloseable,MeshManager.CommandRunner,MeshManager.InterfaceProbe,MeshManager.ProcessStarter {
        static final String IP="10.126.126.2";
        final Path dir;final MeshManager mesh;final Child child=new Child();
        MeshManager.InterfaceState link=new MeshManager.InterfaceState(7,true,List.of(IP));
        String rpc="ok",rule="",race="";int starts,added,calls,routeFailures,ruleFailures,emptyRoutes;boolean probeError,routeError,addErrorAfterCommit;
        Fixture()throws IOException {dir=Files.createTempDirectory("usblink-mesh-observation-");mesh=new MeshManager(dir,dir,this,this,this);mesh.create();}
        void start()throws IOException{mesh.setEnabled(true);mesh.tick();}
        void ready()throws IOException{start();mesh.tick();check(IP.equals(mesh.localIp()),"Fixture starts with a verified owned mesh");}
        public Process start(Path module){starts++;child.alive=true;return child;}
        public MeshManager.InterfaceState read()throws IOException{if(probeError)throw new IOException("fixture");return link;}
        public String run(int timeout,String... args)throws IOException {
            calls++;
            if(args[0].endsWith("easytier-cli")) {
                if(race.equals("rpc-exit"))child.alive=false;
                if(rpc.equals("timeout"))throw new IOException("fixture RPC timeout");
                if(rpc.equals("malformed"))return "{";
                if(rpc.equals("incomplete"))return "[]";
                if(rpc.equals("wrong-shape"))return "{\"result\":{}}";
                List<Object> peers=new ArrayList<>();peers.add(Json.map("ipv4",IP,"hostname","Test phone","cost","Local"));
                peers.add(Json.map("ipv4","10.126.126.9","hostname","Test PC","cost",1));
                if(!rpc.equals("no-relay"))peers.add(Json.map("hostname","PublicServer-test","cost",1));
                return Json.stringify(peers);
            }
            if(args[2].equals("route")) {
                if(routeFailures>0){routeFailures--;throw new IOException("fixture one-shot read failure");}
                if(emptyRoutes>0){emptyRoutes--;return "";}
                if(routeError)throw new IOException("fixture route query failure");
                if(race.equals("route-exit"))child.alive=false;
                if(race.equals("route-interface"))link=new MeshManager.InterfaceState(9,true,List.of(IP));
                return "10.126.126.0/24 dev usblink0 proto kernel scope link\n";
            }
            if(args[3].equals("show")){if(ruleFailures>0){ruleFailures--;throw new IOException("fixture one-shot rule failure");}return "0: from all lookup local\n"+rule;}
            if(args[3].equals("add")){added++;rule="17891: from all to "+args[7]+" lookup main\n";if(addErrorAfterCommit)throw new IOException("fixture uncertain add");return "";}
            if(args[3].equals("del")){rule="";return "";}
            throw new IOException("Unexpected fixture command");
        }
        // Keep integration sockets strictly on loopback; only the verified readiness is mapped.
        MeshNetwork loopback(){return new MeshNetwork(){
            public Map<String,Object> status(){return mesh.status();}public String create()throws IOException{return mesh.create();}
            public void join(String code)throws IOException{mesh.join(code);}public String pairingCode()throws IOException{return mesh.pairingCode();}
            public void leave()throws IOException{mesh.leave();}public void setRelay(String relay)throws IOException{mesh.setRelay(relay);}
            public void setEnabled(boolean enabled)throws IOException{mesh.setEnabled(enabled);}public void tick()throws IOException{mesh.tick();}
            public void stop(){mesh.stop();}public String localIp(){return mesh.localIp()==null?null:"127.0.0.1";}
            public String unavailableReason(){return mesh.unavailableReason();}public String relay(){return mesh.relay();}
            public byte[] presenceKey()throws IOException{return mesh.presenceKey();}
        };}
        public void close()throws IOException {
            mesh.stop();
            try(java.util.stream.Stream<Path> files=Files.list(dir)){for(Path file:(Iterable<Path>)files::iterator)Files.delete(file);}
            Files.delete(dir);
        }
    }
    private static final class Child extends Process {
        boolean alive=true;
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public int waitFor(){alive=false;return 0;}public int exitValue(){if(alive)throw new IllegalThreadStateException();return 0;}
        public void destroy(){alive=false;}public boolean isAlive(){return alive;}
    }
}
