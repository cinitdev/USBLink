package io.usblink.mobile;

import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Real SQLite checks for retention, adjacency suppression, response ownership and paging. */
public final class HistorySqlTestMain {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static void request(AdbCommandObserver parser,int id,String command){byte[] bytes=AdbHistoryTestMain.packet("OPEN",id,AdbHistoryTestMain.utf(command));parser.accept(bytes,0,bytes.length);}
    static void reply(AdbCommandObserver parser,int host,String text) {
        for(String op:new String[]{"OKAY","WRTE","CLSE"}) {
            byte[] bytes=AdbHistoryTestMain.packet(op,host+100,op.equals("WRTE")?AdbHistoryTestMain.utf(text):new byte[0]);
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8,host);parser.returned(bytes,0,bytes.length);
        }
    }
    static List<?> rows(Map<String,Object> page){return (List<?>)page.get("entries");}
    static Map<String,Object> detail(AdbHistory history,long id)throws Exception{return Json.object(rows(history.page(Json.map("epoch",history.page(0).get("epoch"),"id",id))).get(0));}
    public static void main(String[] args)throws Exception {
        protocolCommands();existingSqlite();
        Path dir=Files.createTempDirectory("usblink-sql-dedup-");
        try(AdbHistory history=new AdbHistory(dir,JdbcHistoryDatabase::new)) {
            AdbCommandObserver one=history.parser("peer-one");
            request(one,1,"shell:getprop");reply(one,1,"first reply\n");
            for(int i=2;i<=6;i++){request(one,i,"shell:getprop");reply(one,i,"ignored repeat "+i);}
            check(((Number)history.page(0).get("count")).intValue()==1,"Adjacent identical requests collapse to the first");
            check(Json.object(detail(history,1).get("response")).get("stdout").equals("first reply\n"),"Repeat responses never replace the first response");
            request(one,7,"shell:id");request(one,8,"shell:getprop");
            check(((Number)history.page(0).get("count")).intValue()==3,"A different command breaks the run; A B A remains three rows");
            request(history.parser("peer-two"),1,"shell:getprop");
            request(one,9,"shell:getprop ro.product.model");
            check(((Number)history.page(0).get("count")).intValue()==5,"Different peers and different arguments are not collapsed");
            Map<String,Object> summary=Json.object(rows(history.page(0)).get(4));
            check(Boolean.TRUE.equals(summary.get("partial")) && !Json.object(summary.get("response")).containsKey("stdout"),"List does not load output text");
        }
        try(AdbHistory history=new AdbHistory(dir,JdbcHistoryDatabase::new)) {
            request(history.parser("peer-one"),1,"shell:getprop ro.product.model");
            check(((Number)history.page(0).get("count")).intValue()==5,"Duplicate boundary survives daemon restart");
            String epoch=Json.string(history.page(0),"epoch");history.clear();
            try{history.page(Json.map("epoch",epoch,"id",1));throw new AssertionError("stale detail accepted");}catch(java.io.IOException expected){checks++;}
            request(history.parser("peer-one"),2,"shell:getprop ro.product.model");
            check(((Number)history.page(0).get("count")).intValue()==1,"Clear resets the duplicate boundary");
        }
        Path large=Files.createTempDirectory("usblink-sql-pages-");
        try(AdbHistory history=new AdbHistory(large,JdbcHistoryDatabase::new)) {
            AdbCommandObserver parser=history.parser("peer");
            for(int i=1;i<=230;i++){request(parser,i,"shell:echo "+i+"界".repeat(600));reply(parser,i,(i==44?"FIND_OLDER ":"")+"行".repeat(1300));}
            Map<String,Object> first=history.page(0);String epoch=Json.string(first,"epoch");
            check(((Number)first.get("count")).intValue()==200 && rows(first).size()==50,"Exactly 200 retained and 50 summaries in the first response");
            check(Json.stringify(first).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<40000,"Unicode command summaries stay under the control budget with 4 KiB results");
            Set<Long> ids=new LinkedHashSet<>();Map<String,Object> page=first;int pages=0;
            do {
                pages++;for(Object item:rows(page))check(ids.add(((Number)Json.object(item).get("id")).longValue()),"No overlapping page ID");
                if(!(Boolean)page.get("hasMore"))break;
                long before=((Number)Json.object(rows(page).get(rows(page).size()-1)).get("id")).longValue();
                page=history.page(Json.map("epoch",epoch,"before",before));
            }while(pages<6);
            check(pages==4 && ids.size()==200 && ids.contains(31L) && ids.contains(230L),"Four indexed pages include every retained row once");
            Map<String,Object> found=history.page(Json.map("epoch",epoch,"before",0,"query","find_older","filter","all"));
            check(rows(found).size()==1 && ((Number)Json.object(rows(found).get(0)).get("id")).longValue()==44,"SQL searches results outside the visible page");
            check(Json.object(detail(history,44).get("response")).get("stdout").toString().startsWith("FIND_OLDER"),"Detail returns the correlated original output");
        }
        check(!Files.exists(large.resolve("adb-history.jsonl")),"Recording never creates JSON history");
        check(new String(Files.readAllBytes(large.resolve("adb-history.db")),0,15,java.nio.charset.StandardCharsets.US_ASCII).equals("SQLite format 3"),"Persistence is a real SQLite database");
        try(JdbcHistoryDatabase db=new JdbcHistoryDatabase(large.resolve("adb-history.db"))) {
            check(db.query("EXPLAIN QUERY PLAN SELECT id FROM history WHERE id<? ORDER BY id DESC LIMIT 51",100).toString().contains("INTEGER PRIMARY KEY"),"Older pages use the primary-key index");
            check(db.query("PRAGMA table_info(history)").stream().noneMatch(row->"json".equals(row.get("name"))),"Records and responses are typed SQL columns");
        }
        System.out.println("PASS: "+checks+" SQLite history checks");
    }
    private static void protocolCommands()throws Exception {
        try(AdbHistory history=new AdbHistory(Files.createTempDirectory("usblink-adb-commands-"),JdbcHistoryDatabase::new)) {
            AdbCommandObserver parser=history.parser("peer");
            request(parser,1,"shell:getprop");reply(parser,1,"FIRST_RESULT");
            request(parser,2,"shell,v2,raw:getprop");
            for(String service:new String[]{"track-app","track-app:","track-jdwp","jdwp:123","sync:","localabstract:jdwp-control","vendor-custom:request"})request(parser,20,service);
            request(parser,3,"shell,v2,pty:getprop");
            check(((Number)history.page(0).get("count")).intValue()==1,"shell/v2/pty wrappers and internal services do not split consecutive getprop commands");
            check("FIRST_RESULT".equals(Json.object(detail(history,1).get("response")).get("stdout")),"The first getprop result stays intact");
            request(parser,4,"shell:wm size");request(parser,5,"shell,v2,raw:getprop");
            check(((Number)history.page(0).get("count")).intValue()==3,"An actual different command breaks adjacency: getprop, wm size, getprop");
            request(parser,6,"shell:cmd package install-commit 128");
            request(parser,7,"abb_exec:package\0install-commit\0"+"128\0");
            request(parser,8,"abb:package\0install-commit\0"+"128\0");
            check(((Number)history.page(0).get("count")).intValue()==4,"Equivalent shell and ABB installation stages collapse");
            request(parser,9,"shell:cmd package install-commit 129");
            request(history.parser("other-peer"),1,"shell:cmd package install-commit 129");
            check(((Number)history.page(0).get("count")).intValue()==6,"Different arguments and computers stay separate");
            check(AdbRequestType.recordedCommand("file","STAT /sdcard/test")==null,"File probes do not masquerade as commands");
            check(AdbRequestType.recordedCommand("push","SEND /sdcard/a").equals(AdbRequestType.recordedCommand("push","SND2 /sdcard/a")),"File transfer wrappers normalize to the same adb push");
            check("adb reverse tcp:8080 tcp:8080".equals(AdbRequestType.recordedCommand("service","reverse:forward:tcp:8080;tcp:8080")),"Actual reverse commands get a readable ADB expression");
            request(parser,10,"shell:echo --token first-secret");request(parser,11,"shell,v2,raw:echo --token second-secret");
            check(((Number)history.page(0).get("count")).intValue()==8,"Dedup uses full original arguments before redaction, not identical display text");
        }
    }
    private static void existingSqlite()throws Exception {
        Path dir=Files.createTempDirectory("usblink-command-policy-");
        try(HistorySqlStore store=new HistorySqlStore(new JdbcHistoryDatabase(dir.resolve("adb-history.db")))) {
            String[] commands={"shell:getprop","sync:","track-app:","shell,v2,raw:getprop","shell:wm size","shell:getprop","unknown-service:example"};
            for(int i=0;i<commands.length;i++) {
                String command=commands[i],kind=command.startsWith("shell")?"shell":command.startsWith("sync")?"sync":"service";
                AdbRequestType type=AdbRequestType.of(kind,command);
                store.put(Json.map("id",(long)i+1,"at",1L,"peer","peer","kind",kind,"command",command,"category",type.category,"action",type.action,"signature","old-"+i,"version",1L,
                    "response",Json.map("state","closed","stdout","result-"+i)));
            }
        }
        for(int restart=0;restart<2;restart++)try(AdbHistory history=new AdbHistory(dir,JdbcHistoryDatabase::new)) {
            check(((Number)history.page(0).get("count")).intValue()==3,"Existing SQLite rows use command-only adjacency after restart");
            check("result-0".equals(Json.object(detail(history,1).get("response")).get("stdout")),"Existing duplicate cleanup retains the earliest request and its result");
            request(history.parser("peer"),1,"shell,v2,raw:getprop");
            check(((Number)history.page(0).get("count")).intValue()==3,"The normalized boundary persists across restarts");
        }
    }
}
