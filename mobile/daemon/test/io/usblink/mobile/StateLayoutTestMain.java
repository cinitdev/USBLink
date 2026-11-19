package io.usblink.mobile;

import java.nio.file.*;

/** Layout resolution must never hide existing data or fail on an unknown name. */
public final class StateLayoutTestMain {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("usblink-layout-");
        Files.writeString(root.resolve("mesh.json"),"flat-pairing");
        check(StateLayout.file(root,"mesh.json").equals(root.resolve("mesh.json")),"A legacy layout stays readable");
        check(StateLayout.file(root,"adbd-tcp.json").equals(root.resolve("adbd-tcp.json")),"A legacy journal stays readable");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("history"));
        Files.createDirectories(root.resolve("runtime"));
        check(StateLayout.file(root,"sharing.json").equals(root.resolve("config/sharing.json")),"A new preference uses config");
        check(StateLayout.file(root,"adb-history.db").equals(root.resolve("history/adb-history.db")),"A new database uses history");
        check(StateLayout.runtimeFile(root,"status.snapshot").equals(root.resolve("runtime/status.snapshot")),"A new snapshot uses runtime");
        Files.writeString(root.resolve("sharing.json"),"flat-on");
        check(StateLayout.file(root,"sharing.json").equals(root.resolve("sharing.json")),"A legacy preference keeps winning");
        Files.writeString(root.resolve("status.snapshot"),"legacy-snapshot");
        Files.writeString(root.resolve("runtime/status.snapshot"),"organized-snapshot");
        check(StateLayout.runtimeFile(root,"status.snapshot").equals(root.resolve("runtime/status.snapshot")),"An organized snapshot wins");
        Path database=root.resolve("adb-history.db");
        Files.writeString(database,"database");
        Files.writeString(root.resolve("history/adb-history.db-wal"),"wal");
        StateLayout.adoptCompanions(database);
        check(Files.readString(root.resolve("adb-history.db-wal")).equals("wal"),"A WAL follows a flat database");
        check(!Files.exists(root.resolve("history/adb-history.db-wal")),"A WAL is not duplicated");
        Path organized=root.resolve("history/adb-history.db");
        Files.delete(database);
        Files.writeString(organized,"organized-database");
        Files.writeString(root.resolve("adb-history.db-shm"),"shm");
        StateLayout.adoptCompanions(organized);
        check(Files.readString(root.resolve("history/adb-history.db-shm")).equals("shm"),"An SHM follows an organized database");
        check(!Files.exists(root.resolve("adb-history.db-shm")),"An SHM is not duplicated");
        Files.writeString(database,"second-generation");
        Files.writeString(root.resolve("adb-history.db-journal"),"journal");
        StateLayout.adoptCompanions(organized);
        check(!Files.exists(root.resolve("history/adb-history.db-journal")),"Two databases never swap journals");
        check(Files.readString(root.resolve("adb-history.db-journal")).equals("journal"),"A journal stays with its database");
        check(StateLayout.file(root,"custom.bin").equals(root.resolve("custom.bin")),"An unknown name stays at the root");
        check(StateLayout.runtimeFile(root,"stopping").equals(root.resolve("runtime/stopping")),"A runtime flag uses runtime");
        System.out.println("PASS: "+checks+" state layout checks");
    }
}
