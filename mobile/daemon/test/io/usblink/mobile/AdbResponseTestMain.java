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
    private static Map<String,Object> entry(AdbHistory history,int index){return Json.object(((List<?>)JdbcHistoryDatabase.fullPage(history,0).get("entries")).get(index));}
    private static Map<String,Object> result(AdbHistory history,int index){return Json.object(entry(history,index).get("response"));}
    public static void main(String[] args)throws Exception {shells();files();retention();redaction();System.out.println("PASS: "+checks+" correlated command/result checks");}
    private static void redaction()throws Exception {
        Path dir=Files.createTempDirectory("usblink-history-redaction-");
        String[] ordinary={
            "su -c 'sh /data/local/tmp/usblink-organize-0.3.28.sh'",
            "rm -f /data/local/tmp/usblink-layout-0.3.28.sh /data/local/tmp/USBLink-Mobile-0.3.28-usb-adb-arm64.zip",
            "ls /data/adb/modules/usblink-mobile /data/adb/usblink-usb-probe",
            "echo usblink-0123456789ab"
        };
        try(AdbHistory history=new AdbHistory(dir,JdbcHistoryDatabase::new)) {
            AdbCommandObserver parser=history.parser("peer");int id=0;
            for(String command:ordinary) {
                open(parser,++id,"shell,v2,raw:"+command);reply(parser,id,shell(1,utf(command+"\n")));
                check(("adb shell "+command).equals(entry(history,0).get("adbCommand")),"Ordinary paths and names remain intact in commands");
                check((command+"\n").equals(result(history,0).get("stdout")),"Ordinary paths and names remain intact in output");
            }
            String code=new MeshProfile("usblink-0123456789ab","0123456789abcdef".repeat(4),MeshProfile.DEFAULT_RELAY).encode();
            open(parser,++id,"shell,v2,raw:echo '"+code+"' --token private-value");
            reply(parser,id,shell(1,utf(code+"\npassword=private-password\n")));
            String recorded=Json.stringify(entry(history,0));
            check(!recorded.contains(code) && !recorded.contains(code.substring(9)) && recorded.contains("[配对码已隐藏]"),"Real pairing code hidden in request and reply");
            check(!recorded.contains("private-value") && !recorded.contains("private-password"),"Explicit credentials remain hidden");
        }
        try(AdbHistory history=new AdbHistory(dir,JdbcHistoryDatabase::new)) {
            String recorded=Json.stringify(JdbcHistoryDatabase.fullPage(history,0));
            check(recorded.contains("usblink-organize-0.3.28.sh") && recorded.contains("USBLink-Mobile-0.3.28-usb-adb-arm64.zip"),"Correct paths persist across database reload");
        }
    }
    private static void shells()throws Exception {
        Path dir=Files.createTempDirectory("usblink-results-");
        try(AdbHistory history=new AdbHistory(dir,JdbcHistoryDatabase::new)) {
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
            String text=Json.stringify(JdbcHistoryDatabase.fullPage(history,0));
            check(!text.contains("TOP_SECRET") && !text.contains("hidden-code") && !text.contains("\\u001b") && text.contains("red\\n"),"Output redacted and ANSI stripped while lines remain");
            open(first,5,"shell,v2,raw:logcat");reply(first,5,shell(1,utf("x".repeat(5000))));reply(first,5,shell(3,new byte[]{7}));
            check(Boolean.TRUE.equals(result(history,0).get("truncated")) && result(history,0).get("stdout").toString().length()==4096,"Output limit is explicit");
            check(((Number)result(history,0).get("exitCode")).intValue()==7,"Parser keeps reading exit after output truncation");
            long revision=((Number)JdbcHistoryDatabase.fullPage(history,0).get("revision")).longValue();
            reply(first,4,shell(2,utf("RESULT_SEARCH_TOKEN\n")));
            check(((Number)JdbcHistoryDatabase.fullPage(history,0).get("revision")).longValue()>revision,"Output-only changes advance refresh revision");
            Map<String,Object> matches=history.page(Json.map("before",0,"epoch",JdbcHistoryDatabase.fullPage(history,0).get("epoch"),"filter","all","query","RESULT_SEARCH_TOKEN"));
            check(((Number)matches.get("matchedCount")).intValue()==1,"Search includes results across retained records");
            check(Json.stringify(history.page(0)).getBytes(StandardCharsets.UTF_8).length<41000,"Result pages fit control response budget");
            check(!Json.stringify(JdbcHistoryDatabase.fullPage(history,0)).contains("PRIVATE_APK_PAYLOAD"),"Install input is not logged");
            first.close();second.close();
        }
        try(AdbHistory history=new AdbHistory(dir,JdbcHistoryDatabase::new)) {
            check(Json.stringify(JdbcHistoryDatabase.fullPage(history,0)).contains("INSTALL_FAILED"),"Responses survive reload");
            AdbCommandObserver observer=history.parser("peer");open(observer,1,"shell:echo old");history.clear();reply(observer,1,utf("late-output"));
            check(((Number)JdbcHistoryDatabase.fullPage(history,0).get("count")).intValue()==0,"Late response cannot resurrect cleared request");
            open(observer,2,"shell,v2,pty:");reply(observer,2,shell(1,utf("private interactive echo")));
            open(observer,3,"exec:screencap -p");reply(observer,3,new byte[]{0,1,2,3});
            check(!Json.stringify(JdbcHistoryDatabase.fullPage(history,0)).contains("private interactive echo"),"Interactive echo and binary known services excluded");
            open(observer,4,"exec:cat /proc/info");reply(observer,4,new byte[]{0,1,2,3});
            check(Boolean.TRUE.equals(result(history,0).get("binary")) && result(history,0).get("stdout").equals(""),"Unexpected binary output is identified rather than rendered");
            observer.close();
        }
    }
    private static void files()throws Exception {
        try(AdbHistory history=new AdbHistory(Files.createTempDirectory("usblink-sync-results-"),JdbcHistoryDatabase::new)) {
            AdbCommandObserver observer=history.parser("peer");open(observer,1,"sync:");
            upload(observer,packet("WRTE",1,101,AdbHistoryTestMain.sync("SEND",utf("/data/local/tmp/app.apk,33188"))));
            reply(observer,1,AdbHistoryTestMain.sync("OKAY",new byte[0]));
            check("OKAY".equals(result(history,0).get("protocolStatus")),"Push acknowledgement matched to its path");
            upload(observer,packet("WRTE",1,101,AdbHistoryTestMain.sync("RECV",utf("/sdcard/test"))));
            reply(observer,1,AdbHistoryTestMain.join(AdbHistoryTestMain.sync("DATA",utf("PRIVATE_FILE_PAYLOAD")),AdbHistoryTestMain.sync("DONE",new byte[0])));
            check("DONE".equals(result(history,0).get("protocolStatus")) && !Json.stringify(JdbcHistoryDatabase.fullPage(history,0)).contains("PRIVATE_FILE_PAYLOAD"),"Pull completion retained, file data skipped");
            upload(observer,packet("WRTE",1,101,AdbHistoryTestMain.sync("RECV",utf("/denied"))));
            reply(observer,1,AdbHistoryTestMain.sync("FAIL",utf("Permission denied")));
            check("FAIL".equals(result(history,0).get("protocolStatus")) && "Permission denied".equals(result(history,0).get("stderr")),"File failure text retained");
            upload(observer,packet("WRTE",1,101,AdbHistoryTestMain.sync("STAT",utf("/sdcard/test"))));
            reply(observer,1,ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(utf("STAT")).putInt(33188).putInt(42).putInt(0).array());
            check(((Number)history.page(0).get("count")).intValue()==3,"SYNC setup and STAT probes are not command history");
            upload(observer,packet("WRTE",1,101,AdbHistoryTestMain.sync("SEND",utf("/sdcard/after-stat,33188"))));
            reply(observer,1,AdbHistoryTestMain.sync("OKAY",new byte[0]));
            check("OKAY".equals(result(history,0).get("protocolStatus")),"Skipping STAT history preserves following push response correlation");
            observer.close();
        }
    }
    private static void retention()throws Exception {
        Path dir=Files.createTempDirectory("usblink-history-sql-");
        Files.writeString(dir.resolve("adb-history.jsonl"),"old JSON is not imported\n");
        try(AdbHistory history=new AdbHistory(dir,JdbcHistoryDatabase::new)) {
            check(((Number)history.page(0).get("count")).intValue()==0,"Legacy JSON is ignored");
            java.util.function.Consumer<byte[]> observe=history.observe("peer");
            for(int i=0;i<230;i++)observe.accept(AdbHistoryTestMain.packet("OPEN",i,AdbHistoryTestMain.utf("shell:echo "+i)));
            check(((Number)history.page(0).get("count")).intValue()==200,"SQLite retains newest 200 rows");
            check(((List<?>)history.page(0).get("entries")).size()==50 && (Boolean)history.page(0).get("hasMore"),"Default page contains 50 summaries");
        }
        try(AdbHistory history=new AdbHistory(dir,JdbcHistoryDatabase::new)) {
            check(((Number)history.page(0).get("count")).intValue()==200,"SQL rows persist across restart");
        }
        check(Files.readString(dir.resolve("adb-history.jsonl")).equals("old JSON is not imported\n"),"Old JSON is neither read nor rewritten");
    }
}
