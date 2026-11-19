package io.usblink.mobile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves module state files without a layout marker. A file keeps the location
 * it already has and a new file is created in its group directory when that
 * directory exists, so a failed tidy-up can never hide pairing, history or the
 * adbd recovery journal.
 */
final class StateLayout {
    private StateLayout() {}
    private static String group(String name) {
        switch(name) {
            case "mesh.json":case "sharing.json":return "config";
            case "adb-history.db":case "adb-history.db-wal":case "adb-history.db-shm":case "adb-history.db-journal":return "history";
            case "adbd-tcp.json":case "mesh.toml":case "mesh.route":return "runtime";
            default:return null;
        }
    }
    /** Persistent data: a legacy flat file wins until the supervisor moves it. */
    static Path file(Path root,String name) {
        String group=group(name);
        if(group==null)return root.resolve(name);
        Path flat=root.resolve(name),grouped=root.resolve(group).resolve(name);
        if(Files.exists(flat))return flat;
        if(Files.exists(grouped))return grouped;
        return Files.isDirectory(root.resolve(group))?grouped:flat;
    }
    /** Current runtime state: an organized copy wins over a stale legacy one. */
    static Path runtimeFile(Path root,String name) {
        Path flat=root.resolve(name),grouped=root.resolve("runtime").resolve(name);
        if(Files.exists(grouped))return grouped;
        if(Files.exists(flat))return flat;
        return Files.isDirectory(root.resolve("runtime"))?grouped:flat;
    }
    /**
     * Keep a database next to its WAL/SHM/journal when an interrupted move split
     * them. Only closes the gap before SQLite opens the file; never overwrites.
     */
    static void adoptCompanions(Path database) {
        Path dir=database.getParent();
        if(dir==null)return;
        Path parent=dir.getParent();
        if(parent==null)return;
        Path other=isGroup(dir,"history")?parent:dir.resolve("history");
        // Only complete an interrupted move. Two database files are different
        // generations; mixing their journals could lose or corrupt history.
        if(!Files.exists(database) || Files.exists(other.resolve("adb-history.db")))return;
        for(String suffix:new String[]{"-wal","-shm","-journal"}) {
            Path from=other.resolve("adb-history.db"+suffix),to=dir.resolve("adb-history.db"+suffix);
            if(!Files.exists(from) || Files.exists(to))continue;
            try {Files.move(from,to);}
            catch(IOException ignored) { /* The database stays readable where it is. */ }
        }
    }
    private static boolean isGroup(Path dir,String name) {
        return dir.getFileName()!=null && dir.getFileName().toString().equals(name);
    }
}
