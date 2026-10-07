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
        preference();lifecycle();metadata();
        System.out.println("PASS: "+checks+" experimental module lifecycle/authentication checks");
    }
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
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
