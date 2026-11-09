package io.usblink.mobile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** Owns only a temporary service property, with a durable recovery record before mutation. */
final class TcpAdb {
    static final int PORT=15558;
    interface SystemAccess {
        String property(String name) throws IOException;
        void tcpProperty(String value) throws IOException;
        void restart() throws IOException;
        boolean listening(int port) throws IOException;
        boolean ownedListener(int port) throws IOException;
        void protect() throws IOException;
        void verifyProtection() throws IOException;
        default void maintainProtection() throws IOException{verifyProtection();}
        void unprotect() throws IOException;
        void requireAuthorization() throws IOException;
        String bootId() throws IOException;
        long elapsed();
        void pause() throws IOException;
    }
    private final Path journal;
    private final SystemAccess system;
    private long startDeadline;
    TcpAdb(Path directory,SystemAccess system) {journal=directory.resolve("adbd-tcp.json");this.system=system;}
    synchronized void start() throws IOException {
        if(Files.exists(journal))throw new IOException("上次调试配置尚未恢复，请先关闭共享重试");
        system.requireAuthorization();
        String original=system.property("service.adb.tcp.port");
        String persistent=system.property("persist.adb.tcp.port");
        if(!validProperty(original) || original.equals(String.valueOf(PORT)))throw new IOException("现有调试端口与模块冲突，已取消切换");
        if(!validProperty(persistent) || persistent.equals(String.valueOf(PORT)))throw new IOException("已有持久调试端口与模块冲突，已取消切换");
        if(!system.property("service.adb.listen_addrs").isEmpty())throw new IOException("系统已有自定义调试监听地址，已取消接管");
        if(!system.property("init.svc.adbd").equals("running"))throw new IOException("请先在开发者选项开启 USB 调试，等待系统调试服务运行");
        if(system.listening(PORT))throw new IOException("模块内部端口已被占用，已取消切换");
        save(Json.map("version",1,"boot",system.bootId(),"original",original,"persistent",persistent));
        // A crash at any following point leaves the record and restrictive rules for recovery.
        system.protect();system.verifyProtection();
        if(!original.equals(system.property("service.adb.tcp.port")) || !persistent.equals(system.property("persist.adb.tcp.port")) || !system.property("service.adb.listen_addrs").isEmpty())
            throw new IOException("调试配置已被其他程序修改，已取消切换");
        system.tcpProperty(String.valueOf(PORT));
        if(!String.valueOf(PORT).equals(system.property("service.adb.tcp.port")))throw new IOException("系统未接受调试端口配置");
        system.restart();startDeadline=system.elapsed()+10_000;
    }
    synchronized int port() throws IOException {
        return port(false);
    }
    synchronized int maintainPort() throws IOException {
        return port(true);
    }
    private int port(boolean maintain) throws IOException {
        if(!Files.exists(journal))return 0;
        system.requireAuthorization();
        if(!String.valueOf(PORT).equals(system.property("service.adb.tcp.port")) || !system.property("service.adb.listen_addrs").isEmpty())
            throw new IOException("系统调试配置已变化，共享已暂停；请关闭共享检查");
        if(maintain)system.maintainProtection();else system.verifyProtection();
        if(system.ownedListener(PORT))return PORT;
        if(system.elapsed()<startDeadline)return 0;
        throw new IOException("系统 adbd 未建立预期监听，请关闭共享后检查 ROM 和 Root 权限");
    }
    synchronized void restore() throws IOException {
        if(!Files.exists(journal))return;
        Map<String,Object> entry=read();
        String original=Json.string(entry,"original");
        String current=system.property("service.adb.tcp.port");
        String boot=system.bootId();
        if(!boot.equals(Json.string(entry,"boot"))) {
            // Non-persistent properties reset at reboot. Never replay a previous boot's config.
            if(current.equals(String.valueOf(PORT)) || system.listening(PORT))throw new IOException("重启后内部调试端口仍被占用，保留防护等待人工检查");
        } else {
            if(!system.property("service.adb.listen_addrs").isEmpty() || !system.property("persist.adb.tcp.port").equals(Json.string(entry,"persistent")) || (!current.equals(String.valueOf(PORT)) && !current.equals(original)))
                throw new IOException("其他程序已修改调试配置，未覆盖其设置；端口防护仍保留，请恢复冲突后重试关闭");
            if(current.equals(String.valueOf(PORT))) {
                system.protect();
                system.tcpProperty(original);
                if(!original.equals(system.property("service.adb.tcp.port")))throw new IOException("原调试配置恢复失败，端口防护仍保留");
                system.restart();
            } else if(system.listening(PORT)) {
                // Recovery after a crash between restoring the property and restarting adbd.
                if(!system.ownedListener(PORT))throw new IOException("内部端口由其他进程占用，已停止恢复");
                system.protect();system.restart();
            }
            long deadline=system.elapsed()+8_000;
            while(system.listening(PORT) && system.elapsed()<deadline)system.pause();
            if(system.listening(PORT))throw new IOException("无法确认内部调试监听已停止，端口防护仍保留，请重试关闭");
        }
        system.unprotect();
        Files.delete(journal);startDeadline=0;
    }
    private Map<String,Object> read() throws IOException {
        try {
            if(Files.size(journal)>1024)throw new IllegalArgumentException();
            Map<String,Object> data=Json.object(Json.parse(new String(Files.readAllBytes(journal),StandardCharsets.UTF_8)));
            if(Json.integer(data,"version")!=1 || !validProperty(Json.string(data,"persistent")) || !validProperty(Json.string(data,"original")) || Json.string(data,"original").equals(String.valueOf(PORT)) || !Json.string(data,"boot").matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException();
            return data;
        }catch(IllegalArgumentException e){throw new IOException("调试恢复记录损坏，已保留端口防护，请检查模块数据");}
    }
    private void save(Map<String,Object> data) throws IOException {
        Path temp=journal.resolveSibling("adbd-tcp.json.new");
        byte[] bytes=Json.stringify(data).getBytes(StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);
        }
        try{Files.move(temp,journal,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp,journal,StandardCopyOption.REPLACE_EXISTING);}
    }
    static boolean validProperty(String value) {
        if(value.isEmpty() || value.equals("-1"))return true;
        if(!value.matches("[0-9]{1,5}"))return false;
        return Integer.parseInt(value)<=65535;
    }
}
