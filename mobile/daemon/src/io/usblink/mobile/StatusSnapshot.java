package io.usblink.mobile;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.function.LongSupplier;

/** Private, short-lived UI observations. Never receives action payloads or pairing codes. */
final class StatusSnapshot implements Closeable {
    private final Path file;
    private final String identity;
    private final LongSupplier seconds;
    private boolean closed;
    StatusSnapshot(Path directory,int pid,String startTicks,String boot,LongSupplier seconds)throws IOException {
        if(pid<=1 || !startTicks.matches("[0-9]+") || !boot.matches("[a-fA-F0-9-]{36}"))throw new IOException("状态进程身份无效");
        file=directory.resolve("status.snapshot");identity=pid+" "+startTicks+" "+boot+" ";this.seconds=seconds;
        invalidate();
    }
    synchronized void publish(Map<String,Object> status)throws IOException {
        if(closed)return;
        String json=Json.stringify(Json.map("ok",true,"data",status));
        if(json.length()>65536)throw new IOException("状态数据过大");
        Path temp=file.resolveSibling("status.snapshot.new");
        Files.write(temp,(identity+seconds.getAsLong()+"\n"+json+"\n").getBytes(StandardCharsets.UTF_8));
        // Ephemeral status needs atomic visibility, not a synchronous disk flush on every poll.
        try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
    }
    synchronized void invalidate()throws IOException {Files.deleteIfExists(file);}
    @Override public synchronized void close()throws IOException {closed=true;invalidate();}
}
