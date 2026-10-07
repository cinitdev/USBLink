package io.usblink.mobile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Real sockets and shared Windows vectors; no Android, ADB or mesh process. */
public final class PresenceTestMain {
    private static final String SECRET="0123456789abcdef".repeat(4);
    private static final byte[] REQUEST="USBLink presence request v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESPONSE="USBLink presence response v1\0".getBytes(StandardCharsets.US_ASCII);
    private static int checks;
    public static void main(String[] args)throws Exception {
        if(args.length==1 && (args[0].equals("--serve") || args[0].equals("--serve-usb"))){serve(args[0].equals("--serve-usb"));return;}
        byte[] key=PresenceServer.key(SECRET),nonce=new byte[16];for(int i=0;i<16;i++)nonce[i]=(byte)i;
        check(hex(key).equals("7e9a8a4e70233fa17d8b20a9cac62f594b4993e08b93b86ef7cf0ecfac3911e9"),"Windows key derivation");
        check(hex(PresenceServer.tag(key,REQUEST,nonce,new byte[0])).equals("ed02256e94319d33347f2bfd42b3c26fff1fbb340ed2694fe1a2e07ed6f5c9eb"),"Windows request vector");
        check(hex(PresenceServer.tag(key,RESPONSE,nonce,new byte[]{5})).equals("d07cecc77d74e12e65581e052bfc3f85543aaf511f3fb2a7c5fbb4de4a416af0"),"Windows response vector");
        AtomicLong time=new AtomicLong(100);
        try(PresenceServer server=new PresenceServer(0,time::get)) {
            server.configure("127.0.0.1",key,PresenceServer.OFF);int port=server.boundPort();
            for(int phase=4;phase<=11;phase++) {
                server.configure("127.0.0.1",key,phase);
                check(port==server.boundPort(),"Phase updates keep listener bound");
                check(query(port,key,REQUEST)==phase,"Authenticated phase "+phase);
                check(query(port,new byte[32],REQUEST)==-1,"Other network cannot observe phase");
            }
            check(query(port,key,RESPONSE)==-1,"Reject reflected/domain-confused request");
            time.set(10101);check(query(port,key,REQUEST)==-1,"Stalled observation cannot remain online");
            check(!server.active(),"Expired observation is not reported ready");
            server.phase(PresenceServer.OFF);check(query(port,key,REQUEST)==PresenceServer.OFF,"Fresh off is online without ADB");
            try(Socket held=new Socket("127.0.0.1",port)) {
                held.setSoTimeout(4000);held.getOutputStream().write('U');
                check(query(port,key,REQUEST)==PresenceServer.OFF,"Slow client does not block another probe");
                byte[] replacement=PresenceServer.key("a".repeat(64));
                server.configure("127.0.0.1",replacement,PresenceServer.READY);port=server.boundPort();
                check(query(port,key,REQUEST)==-1,"Key rotation rejects old network");
                check(query(port,replacement,REQUEST)==PresenceServer.READY,"New network authenticates");
                check(closed(held),"Rotation revokes pending old clients");
            }
            server.stop();check(!server.active(),"Stop revokes presence");
            try(Socket unexpected=new Socket("127.0.0.1",port)){throw new AssertionError("Stopped listener accepted connection");}
            catch(ConnectException expected){checks++;}
            server.configure("127.0.0.1",key,PresenceServer.OFF);
            check(query(server.boundPort(),key,REQUEST)==PresenceServer.OFF,"Explicit restart works");
        }
        System.out.println("PASS: "+checks+" mobile presence checks");
    }
    static int query(int port,byte[] key,byte[] domain)throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(4000);byte[] nonce=new byte[16];new java.security.SecureRandom().nextBytes(nonce);
            OutputStream out=socket.getOutputStream();out.write("USBLINK3".getBytes(StandardCharsets.US_ASCII));out.write(nonce);
            out.write(PresenceServer.tag(key,domain,nonce,new byte[0]));out.flush();
            DataInputStream in=new DataInputStream(socket.getInputStream());int phase=in.read();if(phase<0)return -1;
            byte[] auth=new byte[32];in.readFully(auth);
            check(Arrays.equals(auth,PresenceServer.tag(key,RESPONSE,nonce,new byte[]{(byte)phase})),"Response authenticates phase and nonce");
            return phase;
        }
    }
    private static boolean closed(Socket socket)throws IOException {try{return socket.getInputStream().read()==-1;}catch(SocketException expected){return true;}}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format("%02x",b&255));return result.toString();}
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static void serve(boolean usb)throws Exception {
        // Bounded host-only fixture for Rust's ignored cross-language integration test.
        Thread deadline=new Thread(()->{try{Thread.sleep(15000);}catch(InterruptedException ignored){}System.exit(0);});deadline.setDaemon(true);deadline.start();
        try(PresenceServer server=new PresenceServer(0)) {
            server.configure("127.0.0.1",PresenceServer.key(SECRET),usb?PresenceServer.USB_READY:PresenceServer.READY);
            if(usb)server.export(Json.map("bus_id","99-1","vid_pid","18d1:4ee7","path","/usblink/experimental/adb/01234567-89ab-cdef-0123-456789abcdef","name","测试手机 · USB ADB（实验）"));
            System.out.println(server.boundPort());System.out.flush();
            BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.US_ASCII));
            while(input.readLine()!=null){server.phase(usb?PresenceServer.USB_OFF:PresenceServer.OFF);System.out.println("off");System.out.flush();}
        }
    }
}
