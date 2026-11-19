package io.usblink.mobile;

import java.nio.file.*;
import java.util.*;

/** Run via app_process in an isolated temporary directory; never the module state. */
public final class AndroidHistoryTestMain {
    private static int checks;
    private static void check(boolean valid,String message) {
        if(!valid)throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args)throws Exception {
        Path state=Paths.get(args[0]).toRealPath();
        if(!state.getParent().equals(Paths.get("/data/local/tmp")) || !state.getFileName().toString().startsWith("usblink-history-probe-"))
            throw new IllegalArgumentException("Use an isolated history probe directory");
        Path database=state.resolve("adb-history.db");
        // Exercise the reported zero-byte file with the production Android adapter.
        Files.createFile(database);
        try(HistorySqlStore store=new HistorySqlStore(new AndroidHistoryDatabase(database))) {
            check(store.count()==0,"Zero-byte file initializes without deleting or archiving it");
            check("wal".equals(store.db.query("PRAGMA journal_mode").get(0).get("journal_mode")),"WAL enabled");
            check(((Number)store.db.query("PRAGMA synchronous").get(0).get("synchronous")).intValue()==2,"FULL synchronization");
        }
        try(AdbHistory history=new AdbHistory(state,AndroidHistoryDatabase::new)) {
            check("".equals(history.page(0).get("problem")),"History opens successfully");
            AdbCommandObserver parser=history.parser("probe");
            for(int i=1;i<=2;i++) {
                byte[] request=packet("OPEN",i,0,"shell:echo history-probe\0");
                parser.accept(request,0,request.length);
                for(byte[] response:new byte[][]{packet("OKAY",100+i,i,""),packet("WRTE",100+i,i,"history-probe\n"),packet("CLSE",100+i,i,"")})
                    parser.returned(response,0,response.length);
            }
            Map<String,Object> page=history.page(0);
            check(((Number)page.get("count")).intValue()==1,"Consecutive duplicate remains suppressed");
            Map<String,Object> item=Json.object(((List<?>)page.get("entries")).get(0));
            Map<String,Object> detail=history.page(Json.map("epoch",page.get("epoch"),"id",item.get("id")));
            Map<String,Object> row=Json.object(((List<?>)detail.get("entries")).get(0));
            check("history-probe\n".equals(Json.object(row.get("response")).get("stdout")),"Response persists");
        }
        try(AdbHistory history=new AdbHistory(state,AndroidHistoryDatabase::new)) {
            check(((Number)history.page(0).get("count")).intValue()==1,"Saved history survives reopening");
        }
        Path invalid=state.resolve("invalid.db");byte[] original="not a database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(invalid,original);
        boolean rejected=false;
        try(HistoryDatabase unused=new AndroidHistoryDatabase(invalid)){}catch(java.io.IOException expected){rejected=true;}
        check(rejected && Arrays.equals(original,Files.readAllBytes(invalid)),"Damaged nonempty data is preserved");
        System.out.println("PASS: "+checks+" Android SQLite checks");
    }
    private static byte[] packet(String command,int first,int second,String text) {
        byte[] payload=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int value=java.nio.ByteBuffer.wrap(command.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        int checksum=0;for(byte b:payload)checksum+=b&255;
        return java.nio.ByteBuffer.allocate(24+payload.length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(value).putInt(first).putInt(second).putInt(payload.length).putInt(checksum).putInt(value^0xffffffff).put(payload).array();
    }
}
