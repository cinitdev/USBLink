package io.usblink.mobile;

import io.usblink.mobile.usbip.AdbUsbDevice;
import io.usblink.mobile.usbip.UsbIpServer;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Module lifecycle for the tested ADB-only USB device. No desktop ADB and no re-imports. */
final class UsbTransport implements SharingTransport {
    private final Platform platform;
    private UsbIpServer server;
    private AdbUsbDevice device;
    private String ip,model;
    private int port,targetPort;
    private boolean closed;
    UsbTransport(Platform platform){this.platform=platform;}
    public boolean usb(){return true;}
    public synchronized void configure(String kind,String bindIp,int bindPort,int target)throws IOException {
        if(closed)throw new IOException("USB 共享服务已关闭");
        if(!kind.equals("adb"))throw new IOException("不支持的 USB 接口");
        if(server!=null && bindIp.equals(ip) && bindPort==port && target==targetPort) {
            if(!server.active())throw new IOException("USB 共享监听已中断，请关闭后重新开启共享");
            return;
        }
        stopAll();
        Map<String,Object> info=platform.device();
        String serial=String.valueOf(info.getOrDefault("serial",""));
        if(!serial.matches("[A-Za-z0-9_-]{1,64}") || serial.equalsIgnoreCase("unknown"))throw new IOException("无法读取本机 USB 序列号，未启动实验共享");
        model=String.valueOf(info.getOrDefault("model","Android"));
        device=new AdbUsbDevice(serial,model,UUID.randomUUID().toString());
        InetAddress address=InetAddress.getByName(bindIp);
        byte[] subnet=address.getAddress();
        server=new UsbIpServer(address,bindPort,peer->{byte[] p=peer.getAddress();return p.length==4 && p[0]==subnet[0] && p[1]==subnet[1] && p[2]==subnet[2];},device,()->{
            Socket socket=new Socket();
            try{socket.connect(new InetSocketAddress("127.0.0.1",target),3000);return socket;}
            catch(IOException e){socket.close();throw e;}
        },message->{});
        ip=bindIp;port=bindPort;targetPort=target;
    }
    public synchronized boolean active(String kind){return server!=null && server.active();}
    public synchronized int boundPort(String kind){return server==null?0:server.port();}
    public synchronized Map<String,Object> export(){
        return !active("adb")?null:Json.map("bus_id",AdbUsbDevice.BUS_ID,"vid_pid","18d1:4ee7","path",device.path(),"name",model);
    }
    public synchronized List<Map<String,Object>> snapshot(){return server==null?Collections.emptyList():server.sessions();}
    public synchronized void disconnect(String id)throws IOException {
        if(server==null)throw new IOException("该连接已经结束，请刷新列表");server.disconnect(id);
    }
    public synchronized void stopAll(){if(server!=null)server.close();server=null;device=null;}
    public synchronized void close(){closed=true;stopAll();}
}
