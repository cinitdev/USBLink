package io.usblink.mobile;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Synthetic ADB frames only: never runs commands on a phone. */
public final class AdbResponseTestMain {
    private static int checks;
    private static byte[] utf(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static byte[] packet(String command,int local,int remote,byte[] data) {
        byte[] frame=AdbHistoryTestMain.packet(command,local,data);ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putInt(8,remote);return frame;
    }
    private static byte[] shell(int type,byte[] data){return ByteBuffer.allocate(5+data.length).order(ByteOrder.LITTLE_ENDIAN).put((byte)type).putInt(data.length).put(data).array();}
    private static void upload(AdbCommandObserver parser,byte[] data){parser.accept(data,0,data.length);}
    private static void receive(AdbCommandObserver parser,byte[] data){for(int i=0;i<data.length;i++)parser.returned(data,i,1);}
    private static void open(AdbCommandObserver parser,int id,String command) {
        upload(parser,packet("OPEN",id,0,utf(command)));receive(parser,packet("OKAY",id+100,id,new byte[0]));
    }
    private static void reply(AdbCommandObserver parser,int id,byte[] data){receive(parser,packet("WRTE",id+100,id,data));}
    private static Map<String,Object> entry(AdbHistory history,int index){return Json.object(((List<?>)history.page(0).get("entries")).get(index));}
    private static Map<String,Object> result(AdbHistory history,int index){return Json.object(entry(history,index).get("response"));}
    public static void main(String[] args)throws Exception {shells();files();retention();System.out.println("PASS: "+checks+" correlated command/result checks");}
    private static void shells()throws Exception {
        Path dir=Files.createTempDirectory("usblink-results-");
        try(AdbHistory history=new AdbHistory(dir)) {
            AdbCommandObserver first=history.parser("peer-a"),second=history.parser("peer-b");
            open(first,1,"shell,v2,raw:echo 手机");open(second,1,"shell,v2,raw:pm uninstall app.test");
            byte[] chinese=shell(1,utf("手机\n第二行\n"));
            reply(first,1,Arrays.copyOf(chinese,7));reply(first,1,Arrays.copyOfRange(chinese,7,chinese.length));
            reply(second,1,AdbHistoryTestMain.join(shell(2,utf("Failure [DELETE_FAILED_INTERNAL_ERROR]\n")),shell(3,new byte[]{1})));
            reply(first,1,shell(3,new byte[]{0}));receive(first,packet("CLSE",101,1,new byte[0]));
            check("手机\n第二行\n".equals(result(history,1).get("stdout")),"Fragmented UTF-8 and shell headers reassemble without mixing peers");
            check(((Number)result(history,1).get("exitCode")).intValue()==0,"Shell exit zero retained");
            check(((Number)result(history,0).get("exitCode")).intValue()==1 && result(history,0).get("stderr").toString().contains("DELETE_FAILED"),"stderr and nonzero exit retained on correct stream");
            open(first,2,"abb:package\0install-commit\0"+"123\0");reply(first,2,AdbHistoryTestMain.join(shell(1,utf("Success\n")),shell(3,new byte[]{0})));
            check("Success\n".equals(result(history,0).get("stdout")),"ABB uses shell framing");
            open(first,3,"abb_exec:package\0install-write\0-S\0"+"42\0"+"123\0base.apk\0-\0");
            upload(first,packet("WRTE",3,103,utf("PRIVATE_APK_PAYLOAD")));
            reply(first,3,utf("Failure [INSTALL_FAILED_USER_RESTRICTED]\n"));receive(first,packet("CLSE",103,3,new byte[0]));
            check("closed".equals(result(history,0).get("state")) && result(history,0).get("exitCode")==null,"Legacy install result never invents an exit code");
            check(result(history,0).get("stdout").toString().contains("INSTALL_FAILED"),"Actual installation failure text retained");
            open(first,4,"shell,v2,raw:echo secrets");
            reply(first,4,shell(1,utf("\u001b[31mred\u001b[0m\n{\"token\":\"TOP_SECRET\"}\nUSBLINK1-hidden-code\n")));
            String text=Json.stringify(history.page(0));
            check(!text.contains("TOP_SECRET") && !text.contains("hidden-code") && !text.contains("\\u001b") && text.contains("red\\n"),"Output redacted and ANSI stripped while lines remain");
            open(first,5,"shell,v2,raw:logcat");reply(first,5,shell(1,utf("x".repeat(5000))));reply(first,5,shell(3,new byte[]{7}));
            check(Boolean.TRUE.equals(result(history,0).get("truncated")) && result(history,0).get("stdout").toString().length()==4096,"Output limit is explicit");
            check(((Number)result(history,0).get("exitCode")).intValue()==7,"Parser keeps reading exit after output truncation");
            long revision=((Number)history.page(0).get("revision")).longValue();
            reply(first,4,shell(2,utf("RESULT_SEARCH_TOKEN\n")));
            check(((Number)history.page(0).get("revision")).longValue()>revision,"Output-only changes advance refresh revision");
            Map<String,Object> matches=history.page(Json.map("before",0,"epoch",history.page(0).get("epoch"),"filter","all","query","RESULT_SEARCH_TOKEN"));
            check(((Number)matches.get("matchedCount")).intValue()==1,"Search includes results across retained records");
            check(Json.stringify(history.page(0)).getBytes(StandardCharsets.UTF_8).length<41000,"Result pages fit control response budget");
            check(!Json.stringify(history.page(0)).contains("PRIVATE_APK_PAYLOAD"),"Install input is not logged");
            first.close();second.close();
        }
        try(AdbHistory history=new AdbHistory(dir)) {
            check(Json.stringify(history.page(0)).contains("INSTALL_FAILED"),"Responses survive reload");
            AdbCommandObserver observer=history.parser("peer");open(observer,1,"shell:echo old");history.clear();reply(observer,1,utf("late-output"));
            check(((Number)history.page(0).get("count")).intValue()==0,"Late response cannot resurrect cleared request");
            open(observer,2,"shell,v2,pty:");reply(observer,2,shell(1,utf("private interactive echo")));
            open(observer,3,"exec:screencap -p");reply(observer,3,new byte[]{0,1,2,3});
            check(!Json.stringify(history.page(0)).contains("private interactive echo"),"Interactive echo and binary known services excluded");
            open(observer,4,"exec:cat /proc/info");reply(observer,4,new byte[]{0,1,2,3});
            check(Boolean.TRUE.equals(result(history,0).get("binary")) && result(history,0).get("stdout").equals(""),"Unexpected binary output is identified rather than rendered");
            observer.close();
        }
    }
    private static void files()throws Exception {
        try(AdbHistory history=new AdbHistory(Files.createTempDirectory("usblink-sync-results-"))) {
            AdbCommandObserver observer=history.parser("peer");open(observer,1,"sync:");
            upload(observer,packet("WRTE",1,101,AdbHistoryTestMain.sync("SEND",utf("/data/local/tmp/app.apk,33188"))));
            reply(observer,1,AdbHistoryTestMain.sync("OKAY",new byte[0]));
            check("OKAY".equals(result(history,0).get("protocolStatus")),"Push acknowledgement matched to its path");
            upload(observer,packet("WRTE",1,101,AdbHistoryTestMain.sync("RECV",utf("/sdcard/test"))));
            reply(observer,1,AdbHistoryTestMain.join(AdbHistoryTestMain.sync("DATA",utf("PRIVATE_FILE_PAYLOAD")),AdbHistoryTestMain.sync("DONE",new byte[0])));
            check("DONE".equals(result(history,0).get("protocolStatus")) && !Json.stringify(history.page(0)).contains("PRIVATE_FILE_PAYLOAD"),"Pull completion retained, file data skipped");
            upload(observer,packet("WRTE",1,101,AdbHistoryTestMain.sync("RECV",utf("/denied"))));
            reply(observer,1,AdbHistoryTestMain.sync("FAIL",utf("Permission denied")));
            check("FAIL".equals(result(history,0).get("protocolStatus")) && "Permission denied".equals(result(history,0).get("stderr")),"File failure text retained");
            upload(observer,packet("WRTE",1,101,AdbHistoryTestMain.sync("STAT",utf("/sdcard/test"))));
            reply(observer,1,ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(utf("STAT")).putInt(33188).putInt(42).putInt(0).array());
            check(result(history,0).get("stdout").toString().contains("size=42"),"File metadata result is readable");
            observer.close();
        }
    }
    private static void retention()throws Exception {
        Path dir=Files.createTempDirectory("usblink-history-upgrade-");StringBuilder rows=new StringBuilder();
        for(int i=1;i<=1000;i++)rows.append(Json.stringify(Json.map("id",i,"at",0,"peer","old","kind","shell","command","shell:echo "+i))).append('\n');
        Files.writeString(dir.resolve("adb-history.jsonl"),rows);
        try(AdbHistory history=new AdbHistory(dir)) {
            check(((Number)history.page(0).get("count")).intValue()==1000 && ((Number)entry(history,0).get("id")).longValue()==1000,"Upgrade preserves all 1000 old rows");
            check(((List<?>)history.page(0).get("entries")).size()==100 && (Boolean)history.page(0).get("hasMore"),"Default page contains 100 short records, with older history retained");
            check(!entry(history,0).containsKey("response") && history.page(0).get("problem").equals(""),"Legacy history isn't called corrupt or given invented output");
        }
        check(Files.readAllLines(dir.resolve("adb-history.jsonl")).size()==1000,"Opening and closing history does not trim it to one display page");
    }
}
