package io.usblink.mobile;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.Os;
import io.usblink.mobile.usbip.AdbUsbDevice;
import io.usblink.mobile.usbip.UsbIpServer;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit, temporary real-phone experiment. Not included in the production module. */
public final class UsbProbeMain {
    private static final Path DIR=Paths.get("/data/adb/usblink-usb-probe");
    private static final int PORT=3240;
    public static void main(String[] args)throws Exception {
        if(Process.myUid()!=0 || args.length!=1 || !args[0].matches("10\\.126\\.126\\.[0-9]{1,3}"))
            throw new IOException("Root and a single paired test address are required");
        Files.createDirectories(DIR);Os.chmod(DIR.toString(),0700);
        try(FileChannel file=FileChannel.open(DIR.resolve("probe.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=file.tryLock()) {
            if(lock==null)throw new IOException("Probe already running");
            if(Files.exists(DIR.resolve("restore-sharing")) || Files.exists(DIR.resolve("restore-whitelist")))
                throw new IOException("Previous probe needs recovery before another run");
            run(args[0]);
        }
    }
    private static void run(String peer)throws Exception {
        Map<String,Object> initial=control("status",Json.map());
        if(!Json.string(Json.object(initial.get("device")),"model").equals("M2012K11AC"))
            throw new IOException("This reviewed experiment targets the connected K40 only");
        String ip=Json.string(Json.object(initial.get("mesh")),"localIp");
        InetAddress local=InetAddress.getByName(ip),remote=InetAddress.getByName(peer);
        if(local.equals(remote) || !meshHas(local))throw new IOException("Mesh address is not confirmed");
        String serial=AndroidPlatform.command("/system/bin/getprop","ro.serialno").trim();
        String model=AndroidPlatform.command("/system/bin/getprop","ro.product.model").trim();
        AdbUsbDevice device=new AdbUsbDevice(serial,model,UUID.randomUUID().toString());
        boolean wasEnabled=Boolean.TRUE.equals(Json.object(initial.get("sharing")).get("enabled"));
        if(!(initial.get("sessions") instanceof List) || !((List<?>)initial.get("sessions")).isEmpty())
            throw new IOException("Existing debug sessions must not be interrupted");
        // This marker also lets the supplied recovery script restore only this experiment's change.
        Files.write(DIR.resolve("restore-sharing"),(wasEnabled?"keep":"off").getBytes(StandardCharsets.US_ASCII));
        Files.deleteIfExists(DIR.resolve("stop"));
        AndroidPlatform platform=new AndroidPlatform(Paths.get("/data/adb/usblink"));
        UsbIpServer server=null;
        try {
            if(!wasEnabled)control("set-sharing",Json.map("enabled",true));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(!Boolean.TRUE.equals(Json.object(control("status",Json.map()).get("sharing")).get("active"))) {
                if(System.nanoTime()>deadline)throw new IOException("adbd not ready");Thread.sleep(250);
            }
            if(platform.tcpPort()!=TcpAdb.PORT)throw new IOException("adbd identity not confirmed");
            firewall("-I");
            changeWhitelist(true);
            server=new UsbIpServer(local,PORT,remote,device,()->{
                if(platform.tcpPort()!=TcpAdb.PORT)throw new IOException("Shared adbd is not ready");
                Socket socket=new Socket();
                try{socket.connect(new InetSocketAddress("127.0.0.1",TcpAdb.PORT),3000);return socket;}
                catch(IOException e){socket.close();throw e;}
            },System.out::println);
            Files.write(DIR.resolve("record.bin"),device.record());
            Files.write(DIR.resolve("ready"),"ADB-only; MTP is not implemented\n".getBytes(StandardCharsets.UTF_8));
            System.out.println("Ready: ADB-only USB experiment; 15 minute setup / 30 minute transfer lease");
            long readyAt=System.nanoTime();
            while(!Files.exists(DIR.resolve("stop"))) {
                long attachedAt=server.firstImportNanos();
                long expires=(attachedAt<0?readyAt:attachedAt)+java.util.concurrent.TimeUnit.MINUTES.toNanos(attachedAt<0?15:30);
                if(System.nanoTime()>=expires) {System.out.println("Probe lease expired; deliberate shutdown and restoration");break;}
                Thread.sleep(2000);
                if(!meshHas(local) || platform.tcpPort()!=TcpAdb.PORT)throw new IOException("Sharing revoked");
                firewall("-C");
            }
        } finally {
            System.out.println("Stopping probe and restoring temporary settings");
            if(server!=null)server.close();Files.deleteIfExists(DIR.resolve("ready"));
            try {
                if(!wasEnabled)control("set-sharing",Json.map("enabled",false));
                Files.deleteIfExists(DIR.resolve("restore-sharing"));
            } finally {try{changeWhitelist(false);}finally{removeFirewall();}}
        }
    }
    private static boolean meshHas(InetAddress ip)throws SocketException {
        NetworkInterface mesh=NetworkInterface.getByName("usblink0");
        return mesh!=null && mesh.isUp() && Collections.list(mesh.getInetAddresses()).contains(ip);
    }
    private static void firewall(String op)throws IOException {
        for(String binary:new String[]{"/system/bin/iptables","/system/bin/ip6tables"}) {
            List<String> args=new ArrayList<>(Arrays.asList(binary,"-w","2",op,"INPUT"));
            // Preserve the existing module's top two INPUT protections and their verification.
            if(op.equals("-I"))args.add("3");
            args.addAll(Arrays.asList("!","-i","usblink0","-p","tcp","--dport",String.valueOf(PORT),
                "-m","comment","--comment","usblink-usb-probe","-j","DROP"));
            AndroidPlatform.command(args.toArray(new String[0]));
        }
    }
    private static void removeFirewall()throws IOException {
        IOException failure=null;
        for(String binary:new String[]{"/system/bin/iptables","/system/bin/ip6tables"}) {
            try {
                String rules=AndroidPlatform.command(binary,"-w","2","-S","INPUT");
                if(rules.contains("usblink-usb-probe"))AndroidPlatform.command(binary,"-w","2","-D","INPUT",
                    "!","-i","usblink0","-p","tcp","--dport","3240","-m","comment","--comment","usblink-usb-probe","-j","DROP");
            }catch(IOException e){failure=e;}
        }
        if(failure!=null)throw failure;
    }
    private static void changeWhitelist(boolean enable)throws IOException {
        String cli="/data/adb/modules/usblink-mobile/bin/easytier-cli";
        String current=AndroidPlatform.command(cli,"-p","127.0.0.1:15891","whitelist","show");
        Path journal=DIR.resolve("restore-whitelist");
        if(enable) {
            if(!current.contains("TCP Whitelist: 3241-3242") || Files.exists(journal))throw new IOException("Unexpected mesh whitelist");
            Files.write(journal,"3241-3242".getBytes(StandardCharsets.US_ASCII));
            AndroidPlatform.command(cli,"-p","127.0.0.1:15891","whitelist","set-tcp","3240-3242");
        } else if(Files.exists(journal)) {
            if(current.contains("TCP Whitelist: 3240-3242"))
                AndroidPlatform.command(cli,"-p","127.0.0.1:15891","whitelist","set-tcp","3241-3242");
            else if(!current.contains("TCP Whitelist: 3241-3242"))throw new IOException("External mesh whitelist change; recovery record retained");
            Files.delete(journal);
        }
    }
    private static Map<String,Object> control(String action,Map<String,Object> input)throws IOException {
        try(LocalSocket socket=new LocalSocket()) {
            socket.connect(new LocalSocketAddress("usblink.control.v1",LocalSocketAddress.Namespace.ABSTRACT));
            if(socket.getPeerCredentials().getUid()!=0)throw new IOException("Control identity mismatch");
            socket.setSoTimeout(30000);
            socket.getOutputStream().write((Json.stringify(Json.map("action",action,"input",input))+"\n").getBytes(StandardCharsets.UTF_8));
            Map<String,Object> response=Json.object(Json.parse(Main.readLine(socket.getInputStream(),65536)));
            if(!Boolean.TRUE.equals(response.get("ok")))throw new IOException("Module control failed: "+Json.string(response,"error"));
            return Json.object(response.get("data"));
        }
    }
}
