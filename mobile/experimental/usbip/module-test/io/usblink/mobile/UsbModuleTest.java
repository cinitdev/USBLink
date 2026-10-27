package io.usblink.mobile;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Ordinary loopback sockets only; never imports a Windows virtual device. */
public final class UsbModuleTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        preference();lifecycle();diagnostics();metadata();history();networkObservation();
        System.out.println("PASS: "+checks+" experimental module lifecycle/authentication checks");
    }
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static void networkObservation()throws Exception {
        Phone phone=new Phone();
        try(MeshObservationTestMain.Fixture f=new MeshObservationTestMain.Fixture();
            ServerSocket backend=new ServerSocket(0,4,InetAddress.getLoopbackAddress());UsbTransport usb=new UsbTransport(phone);
            Controller controller=new Controller(phone,f.loopback(),new SharingStore(f.dir,3),usb,0)) {
            phone.port=backend.getLocalPort();controller.tick();controller.handle("set-sharing",Json.map("enabled",true));
            controller.tick();controller.tick();int port=usb.boundPort("adb");String path=usb.export().get("path").toString();
            // Exactly one simulated manual import; later observations must keep this same socket.
            try(Socket client=mount(port);Socket target=backend.accept()) {
                waitSession(usb);String id=usb.snapshot().get(0).get("id").toString();
                f.rpc="timeout";
                for(int i=0;i<20;i++)controller.tick();
                check(usb.boundPort("adb")==port && usb.export().get("path").equals(path),"Repeated RPC failures preserve the exact listener and USB identity");
                check(usb.snapshot().size()==1 && usb.snapshot().get(0).get("id").equals(id),"An idle imported USB session survives failed status queries without re-import");
                check(usb.lastDisconnect()==null && phone.starts==1,"RPC failures do not emit a disconnect or restart adbd");
                check(Boolean.TRUE.equals(Json.object(controller.status().get("appSession")).get("ready")),"Authenticated presence remains available on the verified live local interface");
                // Confirm real USB I/O still works over the original TCP connection.
                client.getOutputStream().write(usbOut(1,0,0,new byte[]{0,9,1,0,0,0,0,0}));
                byte[] reply=new byte[48];new DataInputStream(client.getInputStream()).readFully(reply);
                check(ByteBuffer.wrap(reply).getInt(20)==0,"Original USB session still responds after degraded observations");
                f.link=null;controller.tick();
                check(closed(client) && closed(target) && !usb.active("adb"),"Actual interface loss revokes the same session immediately");
                waitDiagnostic(usb,"NETWORK_INTERFACE_LOST");
                f.link=new MeshManager.InterfaceState(8,true,List.of(MeshObservationTestMain.Fixture.IP));f.rpc="ok";controller.tick();
                check(usb.active("adb") && usb.snapshot().isEmpty() && phone.starts==1,"Network restoration prepares a listener but never imports or restarts adbd");
            }
        }
    }
    private static void history()throws Exception {
        Path dir=Files.createTempDirectory("usblink-usb-history-");Phone phone=new Phone();
        try(AdbHistory history=new AdbHistory(dir);ServerSocket backend=new ServerSocket(0);UsbTransport usb=new UsbTransport(phone,history)) {
            usb.configure("adb","127.0.0.1",0,backend.getLocalPort());
            try(Socket client=mount(usb.boundPort("adb"));Socket target=backend.accept()) {
                target.setSoTimeout(3000);waitSession(usb);
                OutputStream out=client.getOutputStream();DataInputStream in=new DataInputStream(client.getInputStream());
                byte[] setup=new byte[]{0,9,1,0,0,0,0,0};out.write(usbOut(1,0,0,setup));byte[] reply=new byte[48];in.readFully(reply);
                check(ByteBuffer.wrap(reply).getInt(20)==0,"USB configured for history test");
                byte[] command=AdbHistoryTestMain.packet("OPEN",1,"shell,v2,raw:echo history\0".getBytes(StandardCharsets.UTF_8));
                // The ADB header itself crosses two USB OUT requests.
                out.write(usbOut(2,2,9,new byte[8]));out.write(command,0,9);in.readFully(reply);
                out.write(usbOut(3,2,command.length-9,new byte[8]));out.write(command,9,command.length-9);in.readFully(reply);
                byte[] received=new byte[command.length];new DataInputStream(target.getInputStream()).readFully(received);
                check(Arrays.equals(command,received),"History observes USB upload without altering bytes");
                List<?> entries=(List<?>)history.page(0).get("entries");
                check(entries.size()==1 && Json.string(Json.object(entries.get(0)),"command").equals("shell,v2,raw:echo history"),"Module records one request across USB fragments");
                try(Controller controller=new Controller(phone,new TestMain.FakeMesh(),new SharingStore(dir,3),usb,0)) {
                    Map<String,Object> state=controller.handle("clear-history",Collections.emptyMap());
                    check((Integer)Json.object(state.get("history")).get("count")==0,"Root control clear returns confirmed empty history");
                    check(usb.snapshot().size()==1 && usb.active("adb") && phone.starts==0,"History clear neither revokes USB nor restarts adbd");
                }
            }
        }
    }
    private static byte[] usbOut(int seq,int endpoint,int length,byte[] setup) {
        return ByteBuffer.allocate(48).putInt(1).putInt(seq).putInt(0x00630001).putInt(0).putInt(endpoint)
            .putInt(0).putInt(length).putInt(0).putInt(0).putInt(0).put(setup).array();
    }
    private static void preference()throws Exception {
        Path dir=Files.createTempDirectory("usblink-usb-pref-");
        new SharingStore(dir).save(true);
        SharingStore usb=new SharingStore(dir,3);
        check(!usb.load(),"TCP enabled does not authorize the experimental USB interface");
        usb.save(true);check(new SharingStore(dir,3).load(),"USB preference survives restart");
        usb.save(false);check(!new SharingStore(dir,3).load(),"Explicit off persists");
    }
    static final class Phone implements Platform {
        int port,starts,stops;
        public Map<String,Object> device(){return Json.map("serial","test-phone","model","Test Phone");}
        public void recoverTcp(){stops++;}public void startTcp(){starts++;}public int tcpPort(){return port;}
        public void stopTcp(){stops++;}public void openDebugSettings(){}
    }
    private static void lifecycle()throws Exception {
        Phone phone=new Phone();TestMain.FakeMesh mesh=new TestMain.FakeMesh();mesh.ip="127.0.0.1";
        try(ServerSocket backend=new ServerSocket(0);UsbTransport usb=new UsbTransport(phone);
            Controller controller=new Controller(phone,mesh,new SharingStore(Files.createTempDirectory("usblink-usb-lifecycle-"),3),usb,0)) {
            phone.port=backend.getLocalPort();controller.tick();
            check(!usb.active("adb"),"Fresh module does not listen");
            controller.handle("set-sharing",Json.map("enabled",true));
            check(usb.active("adb"),"Explicit enable starts USB server");
            Map<String,Object> export=usb.export();
            check(export.get("name").equals("Test Phone"),"Device title contains only the actual model, without transport suffix");
            check(Json.object(controller.status().get("debug")).get("mode").equals("usb-adb-experimental"),"WebUI receives correct transport");
            int port=usb.boundPort("adb");
            try(Socket client=mount(port);Socket target=backend.accept()) {
                waitSession(usb);String id=usb.snapshot().get(0).get("id").toString();
                for(int i=0;i<3;i++)controller.tick();
                check(usb.boundPort("adb")==port && usb.export().get("path").equals(export.get("path")),"Routine observations reuse the existing listener and export identity");
                check(usb.snapshot().size()==1 && usb.snapshot().get(0).get("id").equals(id) && phone.starts==1,"Observations keep the active USB session without restarting adbd");
                try{usb.disconnect("other-session");throw new AssertionError("Wrong identity disconnected");}catch(IOException expected){checks++;}
                check(usb.snapshot().size()==1,"Wrong ID preserves the imported session");
                controller.handle("disconnect",Json.map("id",id));
                check(closed(client) && closed(target),"Targeted detach closes both ends");
                check(usb.active("adb"),"Disconnect preserves availability for a future manual import");
            }
            // A separate manually requested import on a new sharing session, never an automatic retry.
            controller.handle("set-sharing",Json.map("enabled",false));
            check(!usb.active("adb") && usb.snapshot().isEmpty() && usb.export()==null,"Off revokes listener and export");
            controller.handle("set-sharing",Json.map("enabled",true));
            check(!export.get("path").equals(usb.export().get("path")),"A new share gets a new source identity");
            try(Socket client=mount(usb.boundPort("adb"));Socket target=backend.accept()) {
                waitSession(usb);
                controller.handle("set-sharing",Json.map("enabled",false));
                check(closed(client) && closed(target),"Off interrupts idle USB and backend reads");
            }
            for(int i=0;i<3;i++)controller.tick();
            check(!usb.active("adb") && phone.starts==2,"Polling cannot undo off or restart adbd");
            controller.handle("set-sharing",Json.map("enabled",true));mesh.ip=null;controller.tick();
            check(!usb.active("adb") && usb.export()==null,"Network loss revokes USB sessions and export");
            int starts=phone.starts;mesh.ip="127.0.0.1";controller.tick();
            check(usb.active("adb") && Json.object(controller.status().get("sharing")).get("state").equals("sharing"),"Returning network restores the listener without a blocked bind state");
            check(usb.snapshot().isEmpty() && phone.starts==starts,"Returning network does not import USB or restart adbd");
        }
    }
    private static Socket mount(int port)throws Exception {
        Socket socket=new Socket("127.0.0.1",port);socket.setSoTimeout(3000);
        byte[] bus=new byte[32];System.arraycopy("99-1".getBytes(StandardCharsets.US_ASCII),0,bus,0,4);
        socket.getOutputStream().write(ByteBuffer.allocate(8).putShort((short)0x111).putShort((short)0x8003).putInt(0).array());
        socket.getOutputStream().write(bus);
        DataInputStream in=new DataInputStream(socket.getInputStream());
        check(in.readUnsignedShort()==0x111 && in.readUnsignedShort()==3 && in.readInt()==0,"Manual USB import accepted");
        byte[] record=new byte[312];in.readFully(record);return socket;
    }
    private static void diagnostics()throws Exception {
        Phone phone=new Phone();
        try(ServerSocket backend=new ServerSocket(0);UsbTransport usb=new UsbTransport(phone)) {
            usb.configure("adb","127.0.0.1",0,backend.getLocalPort());
            try(Socket client=mount(usb.boundPort("adb"));Socket target=backend.accept()) {
                waitSession(usb);target.close();
                check(closed(client),"adbd EOF closes the matching USB session");
                waitDiagnostic(usb,"ADB_CLOSED");
                check(usb.active("adb"),"Listener availability does not hide a finished session");
                check(usb.lastDisconnect().keySet().equals(new HashSet<>(Arrays.asList("at","code","message","intentional","bytes"))),"Diagnostics expose only fixed reasons, time and byte counts");
            }
            usb.stopAll();usb.configure("adb","127.0.0.1",0,backend.getLocalPort());
            check(usb.lastDisconnect().get("code").equals("ADB_CLOSED"),"Listener replacement retains the recent diagnostic");
            try(Socket client=mount(usb.boundPort("adb"));Socket target=backend.accept()) {
                waitSession(usb);usb.stopAll("network_unavailable");
                check(closed(client) && closed(target),"Network revocation closes both endpoints");
                waitDiagnostic(usb,"NETWORK_UNAVAILABLE");
            }
            usb.configure("adb","127.0.0.1",0,backend.getLocalPort());
            try(Socket client=mount(usb.boundPort("adb"));Socket target=backend.accept()) {
                waitSession(usb);usb.disconnect(usb.snapshot().get(0).get("id").toString());
                check(closed(client) && closed(target),"Manual disconnect still releases the session");
                check(usb.lastDisconnect().get("code").equals("NETWORK_UNAVAILABLE"),"Intentional disconnect does not erase the last unexpected interruption");
            }
        }
    }
    private static void waitDiagnostic(UsbTransport usb,String code)throws Exception {
        long until=System.nanoTime()+3_000_000_000L;
        while((usb.lastDisconnect()==null || !code.equals(usb.lastDisconnect().get("code"))) && System.nanoTime()<until)Thread.sleep(5);
        check(usb.lastDisconnect()!=null && code.equals(usb.lastDisconnect().get("code")),"First disconnect cause survives cascading socket errors: "+code);
    }
    private static void waitSession(UsbTransport usb)throws Exception {
        long until=System.nanoTime()+2_000_000_000L;
        while(usb.snapshot().isEmpty() && System.nanoTime()<until)Thread.sleep(5);
        check(!usb.snapshot().isEmpty(),"Imported session is visible");
    }
    private static boolean closed(Socket socket)throws IOException {socket.setSoTimeout(3000);try{return socket.getInputStream().read()==-1;}catch(SocketException expected){return true;}}
    private static void metadata()throws Exception {
        byte[] key=new byte[32];Arrays.fill(key,(byte)7);AtomicLong now=new AtomicLong();
        Map<String,Object> record=Json.map("bus_id","99-1","vid_pid","18d1:4ee7","path","/usblink/experimental/adb/01234567-89ab-cdef-0123-456789abcdef","name","Test Phone");
        try(PresenceServer server=new PresenceServer(0,now::get)) {
            server.configure("127.0.0.1",key,PresenceServer.USB_READY);server.export(record);int port=server.boundPort();
            check(query(port,key)!=null,"Fresh signed export available");
            check(query(port,new byte[32])==null,"Wrong pairing key cannot read metadata");
            server.phase(PresenceServer.USB_OFF);check(query(port,key)==null,"Off revokes metadata without closing presence");
            check(PresenceTestMain.query(port,key,"USBLink presence request v1\0".getBytes(StandardCharsets.US_ASCII))==8,"USB off remains authenticated online");
            server.phase(PresenceServer.USB_READY);server.export(record);now.set(10_001);
            check(query(port,key)==null,"Expired observation cannot authenticate stale exports");
        }
    }
    private static Map<String,Object> query(int port,byte[] key)throws Exception {
        byte[] nonce=new byte[16];new java.security.SecureRandom().nextBytes(nonce);
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(3000);OutputStream out=socket.getOutputStream();out.write("USBLINK4".getBytes(StandardCharsets.US_ASCII));out.write(nonce);
            out.write(PresenceServer.tag(key,"USBLink USB export request v1\0".getBytes(StandardCharsets.US_ASCII),nonce,new byte[0]));
            DataInputStream in=new DataInputStream(socket.getInputStream());int first=in.read();if(first<0)return null;
            int size=(first<<24)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();
            check(size>0 && size<=2048,"Bounded export payload");byte[] data=new byte[size],tag=new byte[32];in.readFully(data);in.readFully(tag);
            check(java.security.MessageDigest.isEqual(tag,PresenceServer.tag(key,"USBLink USB export response v1\0".getBytes(StandardCharsets.US_ASCII),nonce,data)),"Signed response bound to request nonce");
            return Json.object(Json.parse(new String(data,StandardCharsets.UTF_8)));
        }
    }
}
