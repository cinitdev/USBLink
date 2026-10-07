package io.usblink.mobile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class SharingStore {
    private final Path file;
    private final int version;
    public SharingStore(Path directory) {this(directory,2);}
    public SharingStore(Path directory,int version) { file=directory.resolve("sharing.json");this.version=version; }
    public boolean load() throws IOException {
        if (!Files.exists(file)) return false;
        if (Files.size(file)>1024) throw new IOException("共享设置损坏，请关闭共享以重置");
        try {
            Map<String,Object> value=Json.object(Json.parse(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)));
            // A previous wireless-sharing preference is not consent to restart system adbd.
            long saved=Json.integer(value,"version");
            if(saved>=1 && saved<version)return false;
            if (saved!=version || !(value.get("enabled") instanceof Boolean)) throw new IllegalArgumentException();
            return (Boolean)value.get("enabled");
        } catch (IllegalArgumentException e) { throw new IOException("共享设置损坏，请关闭共享以重置"); }
    }
    public void save(boolean enabled) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp=file.resolveSibling("sharing.json.new");
        byte[] bytes=Json.stringify(Json.map("version",version,"enabled",enabled)).getBytes(StandardCharsets.UTF_8);
        try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
    }
}
