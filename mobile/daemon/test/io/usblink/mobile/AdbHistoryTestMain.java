package io.usblink.mobile;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class AdbHistoryTestMain {
    private static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static byte[] packet(String type,int id,byte[] payload){int c=ByteBuffer.wrap(utf(type)).order(ByteOrder.LITTLE_ENDIAN).getInt();return ByteBuffer.allocate(24+payload.length).order(ByteOrder.LITTLE_ENDIAN).putInt(c).putInt(id).putInt(0).putInt(payload.length).putInt(0).putInt(~c).put(payload).array();}
    static byte[] sync(String type,byte[] payload){return ByteBuffer.allocate(8+payload.length).order(ByteOrder.LITTLE_ENDIAN).put(utf(type)).putInt(payload.length).put(payload).array();}
    static byte[] join(byte[]... data)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(byte[] b:data)bytes.write(b);return bytes.toByteArray();}
    static List<?> rows(Map<String,Object> page){return (List<?>)page.get("entries");}
    static void fragmented(AdbCommandObserver observer,byte[] data){for(int i=0;i<data.length;i++)observer.accept(data,i,1);}
    public static void main(String[] args)throws Exception {parser();classification();commandDisplay();storage();filtering();tcp();failures();System.out.println("PASS: "+checks+" ADB history checks");}
    private static void commandDisplay()throws Exception {
        Path dir=Files.createTempDirectory("usblink-history-command-");
        try(AdbHistory history=new AdbHistory(dir)) {
            Consumer<byte[]> observe=history.observe("peer");
            observe.accept(packet("OPEN",1,utf("shell,v2,raw:echo 'hello world'")));
            Map<String,Object> item=Json.object(rows(history.page(0)).get(0));
            check("adb shell echo 'hello world'".equals(item.get("adbCommand")),"Shell arguments are retained in readable ADB form");
            observe.accept(packet("OPEN",2,utf("abb_exec:package\0install-write\0-S\0"+"57\0"+"123\0"+"my app.apk\0-\0")));
            item=Json.object(rows(history.page(0)).get(0));
            check("adb shell cmd package install-write -S 57 123 'my app.apk' -".equals(item.get("adbCommand")),"ABB argument boundaries survive formatting");
            String epoch=Json.string(history.page(0),"epoch");
            check(rows(history.page(Json.map("epoch",epoch,"before",0,"filter","all","query","adb install"))).size()==1,"Install aliases search actual request history");
            check(rows(history.page(Json.map("epoch",epoch,"before",0,"filter","all","query","adb shell echo"))).size()==1,"Displayed shell command is searchable");
        }
        try(AdbHistory history=new AdbHistory(dir)) {
            check(Json.stringify(history.page(0)).contains("'my app.apk'"),"ABB boundaries survive disk reload");
            check("adb push <电脑端源路径未知> '/sdcard/my app.apk'".equals(AdbRequestType.adbCommand("push","SEND /sdcard/my app.apk")),"Host file paths are explicitly unknown");
        }
    }
    private static void classification()throws Exception {
        String[][] requests={
            {"exec:cmd package install -r -S 57006306","app","install"},
            {"shell:pm install /data/local/tmp/app.apk","app","install"},
            {"shell,v2,raw:cmd package install-write -S 57 123 base.apk -","app","install-write"},
            {"abb_exec:package\0install-create\0-S\0"+"57\0","app","install-create"},
            {"abb:package\0install-commit\0"+"123\0","app","install-commit"},
            {"shell:pm install-abandon 123","app","install-abandon"},
            {"shell:pm uninstall com.example.demo","app","uninstall"},
            {"shell:su -c 'pm install /data/local/tmp/app.apk'","app","install"},
            {"shell:echo 'pm install fake.apk'","command","shell"},
            {"shell:logcat -v threadtime","debug","logcat"},
            {"shell,v2,raw:getprop ro.product.model","device","system-command"},
            {"shell:ls /sdcard","file","file-command"},
            {"shell,v2,pty:","command","interactive-shell"},
            {"reboot:bootloader","device","reboot"},
            {"root:","device","root"},
            {"reverse:forward:tcp:8080;tcp:8080","network","transport"},
            {"jdwp:123","debug","debug-service"},
            {"vendor-custom:request","other","service"},
        };
        Path dir=Files.createTempDirectory("usblink-history-types-");
        try(AdbHistory history=new AdbHistory(dir)) {
            Consumer<byte[]> observe=history.observe("peer");
            for(int i=0;i<requests.length;i++) {
                String[] request=requests[i];observe.accept(packet("OPEN",i+1,utf(request[0]+"\0")));
                Map<String,Object> item=Json.object(rows(history.page(0)).get(0));
                check(request[1].equals(item.get("category")) && request[2].equals(item.get("action")),"Classify "+request[0].replace('\0',' '));
                // Streamed APK stdin must never be interpreted as another command.
                observe.accept(packet("WRTE",i+1,utf("APK_CONTENT shell:pm install hidden.apk")));
            }
            check(rows(history.page(0)).size()==requests.length,"Every OPEN retained; install data excluded");
        }
        try(AdbHistory history=new AdbHistory(dir)) {
            check(rows(history.page(0)).size()==requests.length,"New category records survive reload");
            check(!Json.stringify(history.page(0)).contains("APK_CONTENT"),"Streamed APK contents never persisted");
        }
    }
    private static void filtering()throws Exception {
        Path dir=Files.createTempDirectory("usblink-history-query-");
        // An existing pre-upgrade row receives a category without clearing history.
        Files.writeString(dir.resolve("adb-history.jsonl"),Json.stringify(Json.map("id",1,"at",0,"peer","old-peer","kind","exec","command","exec:cmd package install old.apk"))+"\n");
        try(AdbHistory history=new AdbHistory(dir)) {
            Consumer<byte[]> observe=history.observe("peer");
            for(int i=0;i<45;i++)observe.accept(packet("OPEN",i+2,utf(i%2==0?"shell:pm install file-"+i+".apk":"shell:echo newer")));
            String epoch=Json.string(history.page(0),"epoch");
            Map<String,Object> matches=history.page(Json.map("epoch",epoch,"before",0,"filter","install","query",""));
            check((Integer)matches.get("matchedCount")==24 && rows(matches).size()==24 && !(Boolean)matches.get("hasMore"),"Global install filter returns all matches within the 100-row page");
            long last=((Number)Json.object(rows(matches).get(19)).get("id")).longValue();
            Map<String,Object> older=history.page(Json.map("epoch",epoch,"before",last,"filter","install","query",""));
            check(rows(older).size()==4 && !(Boolean)older.get("hasMore"),"Filtered cursor has no missing matches");
            Map<String,Object> old=history.page(Json.map("epoch",epoch,"before",0,"filter","app","query","OLD.APK"));
            check(rows(old).size()==1 && "install".equals(Json.object(rows(old).get(0)).get("action")),"Old format upgraded and found beyond latest page, case insensitive");
            check(rows(history.page(Json.map("epoch",epoch,"before",0,"filter","file","query","old.apk"))).isEmpty(),"Category and text query combined");
            try{history.page(Json.map("epoch",epoch,"before",0,"filter","all","query","x".repeat(129)));throw new AssertionError("unbounded query accepted");}catch(IOException expected){checks++;}
            history.clear();
            try{history.page(Json.map("epoch",epoch,"before",0,"filter","install","query",""));throw new AssertionError("old filter epoch accepted");}catch(IOException expected){checks++;}
        }
    }
    private static void parser()throws Exception {
        List<String> events=new ArrayList<>();AdbCommandObserver observer=new AdbCommandObserver((kind,value)->events.add(kind+" "+value));
        byte[] auth=packet("AUTH",1,utf("private-key-material"));
        byte[] open=packet("OPEN",1,utf("shell,v2,raw:echo 手机\0"));byte[] original=open.clone();
        fragmented(observer,join(auth,open,packet("OPEN",2,utf("abb_exec:package\0install-create\0-S\056\0"))));
        check(events.size()==2 && events.get(0).equals("shell shell,v2,raw:echo 手机"),"Fragmented headers and UTF-8 service names, AUTH ignored");
        check(Arrays.equals(open,original),"Observation never mutates forwarded data");
        fragmented(observer,packet("OPEN",3,utf("sync:\0")));
        byte[] fileData=join(utf("secret APK content"),packet("OPEN",99,utf("shell:do-not-log")));
        byte[] requests=join(sync("SEND",utf("/data/local/tmp/应用.apk,33206")),sync("DATA",fileData),ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(utf("DONE")).putInt(1_700_000_000).array(),sync("RECV",utf("/sdcard/report.txt")));
        for(int at=0;at<requests.length;at+=3)fragmented(observer,packet("WRTE",3,Arrays.copyOfRange(requests,at,Math.min(at+3,requests.length))));
        check(events.size()==5 && events.get(3).equals("push SEND /data/local/tmp/应用.apk") && events.get(4).equals("pull RECV /sdcard/report.txt"),"SYNC crosses WRTE and USB boundaries; DATA skipped and DONE time isn't a size");
        byte[] setup=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(utf("SND2")).putInt(33206).putInt(4).array();
        byte[] recv=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(utf("RCV2")).putInt(4).array();
        requests=join(sync("SND2",utf("/data/local/tmp/large.apk")),setup,sync("DATA",new byte[65536]),sync("DONE",new byte[0]),sync("RCV2",utf("/sdcard/test")),recv,sync("STAT",utf("/sdcard/final")));
        for(int at=0;at<requests.length;at+=1001)fragmented(observer,packet("WRTE",3,Arrays.copyOfRange(requests,at,Math.min(at+1001,requests.length))));
        check(events.size()==8 && events.get(7).equals("file STAT /sdcard/final"),"v2 setup includes repeated ID; compressed DATA is not parsed");
        fragmented(observer,join(packet("CLSE",3,new byte[0]),packet("WRTE",3,sync("SEND",utf("ignored,1"))),packet("OPEN",4,utf("shell:\0")),packet("WRTE",4,utf("input password\n"))));
        check(events.size()==9 && events.get(8).equals("shell shell:"),"Closed streams and interactive input are not recorded");
        byte[] bad=packet("OPEN",7,new byte[0]);bad[12]=(byte)255;bad[13]=(byte)255;bad[14]=(byte)255;bad[15]=127;
        fragmented(observer,join(bad,packet("OPEN",1,utf("shell:ignored"))));
        check(events.size()==10 && events.get(9).startsWith("unparsed "),"Oversized packet disables observation only; no allocation or payload resync");
        String all=String.join("\n",events);check(!all.contains("private-key") && !all.contains("APK content") && !all.contains("do-not-log") && !all.contains("input password"),"Private auth, file data and input never enter history");
    }
    private static void storage()throws Exception {
        Path dir=Files.createTempDirectory("usblink-history-");String epoch;
        try(AdbHistory history=new AdbHistory(dir)) {
            Consumer<byte[]> observe=history.observe("10.126.126.2");
            for(int i=0;i<1030;i++)observe.accept(packet("OPEN",i,utf("shell:echo "+i+"汉".repeat(600))));
            Map<String,Object> page=history.page(0);epoch=Json.string(page,"epoch");
            check((Integer)page.get("count")==1000 && !rows(page).isEmpty() && rows(page).size()<=100,"Storage retains newest 1000 and each native response stays byte bounded");
            check(Json.stringify(page).getBytes(StandardCharsets.UTF_8).length<41000,"History page fits the existing 64KiB control response");
            check(Json.string(Json.object(rows(page).get(0)),"command").endsWith("汉".repeat(600)),"Long observed arguments are no longer cut at 512 characters");
            long before=((Number)Json.object(rows(page).get(rows(page).size()-1)).get("id")).longValue();
            Map<String,Object> older=history.page(Json.map("before",before,"epoch",epoch));
            check(!rows(older).isEmpty() && ((Number)Json.object(rows(older).get(0)).get("id")).longValue()==before-1,"Byte-bounded cursor has no skipped or overlapping entry");
        }
        check(Files.size(dir.resolve("adb-history.jsonl"))>65536,"Reload test exceeds single JSON codec limit");
        try(AdbHistory history=new AdbHistory(dir)) {
            check((Integer)history.page(0).get("count")==1000,"All 1000 retained records survive daemon restart");
            try{history.page(Json.map("before",200,"epoch",epoch));throw new AssertionError("stale epoch accepted");}catch(IOException expected){checks++;}
            Consumer<byte[]> observe=history.observe("10.126.126.2");
            observe.accept(packet("OPEN",999,utf("shell:echo USBLINK1-private_pairing --token super-secret password='private pass'\u001b[0m")));
            String text=Json.stringify(history.page(0));check(!text.contains("private_pairing") && !text.contains("super-secret") && !text.contains("private pass") && !text.contains("\\u001b"),"Pairing and recognized credential values redacted; terminal controls stripped");
            observe.accept(packet("OPEN",1000,utf("shell:echo "+"x".repeat(5000))));
            String longCommand=Json.string(Json.object(rows(history.page(0)).get(0)),"command");
            check(longCommand.length()==4096 && longCommand.endsWith("…"),"Oversized requests remain bounded and truncation is visible");
            history.clear();check((Integer)history.page(0).get("count")==0,"Clear removes in-memory history");
        }
        try(AdbHistory history=new AdbHistory(dir)){check(rows(history.page(0)).isEmpty(),"Pending disk save cannot resurrect cleared history");}
    }
    private static void tcp()throws Exception {
        Path dir=Files.createTempDirectory("usblink-history-tcp-");
        try(AdbHistory history=new AdbHistory(dir);ProxyPool pool=new ProxyPool(history);ServerSocket backend=new ServerSocket(0)) {
            pool.configure("adb","127.0.0.1",0,backend.getLocalPort());
            try(Socket client=new Socket("127.0.0.1",pool.boundPort("adb"));Socket target=backend.accept()) {
                client.setSoTimeout(3000);target.setSoTimeout(3000);
                byte[] request=packet("OPEN",1,utf("exec:cmd package install -S 1024\0"));client.getOutputStream().write(request);
                byte[] got=new byte[request.length];new DataInputStream(target.getInputStream()).readFully(got);check(Arrays.equals(got,request),"TCP upload remains byte-for-byte unchanged");
                long until=System.nanoTime()+2_000_000_000L;while(rows(history.page(0)).isEmpty() && System.nanoTime()<until)Thread.sleep(5);
                check(rows(history.page(0)).size()==1,"Transport records actual forwarded requests");
                byte[] ready=packet("OKAY",101,new byte[0]),answer=packet("WRTE",101,utf("Success\n")),done=packet("CLSE",101,new byte[0]);
                for(byte[] frame:new byte[][]{ready,answer,done})ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putInt(8,1);
                byte[] result=join(ready,answer,done);target.getOutputStream().write(result);
                byte[] returned=new byte[result.length];new DataInputStream(client.getInputStream()).readFully(returned);
                check(Arrays.equals(result,returned),"TCP responses remain byte-for-byte unchanged");
                check("Success\n".equals(Json.object(Json.object(rows(history.page(0)).get(0)).get("response")).get("stdout")),"TCP device response reaches history");
                history.clear();target.getOutputStream().write(utf("OK"));check(client.getInputStream().read()=='O',"Clearing history preserves active transfer");
                check(pool.snapshot().size()==1,"History clear never disconnects the session");
            }
        }
    }
    private static void failures()throws Exception {
        Path dir=Files.createTempDirectory("usblink-history-failure-");Files.createDirectory(dir.resolve("adb-history.jsonl"));
        try(AdbHistory history=new AdbHistory(dir)) {
            check(!Json.string(history.page(0),"problem").isEmpty(),"Corrupt storage is visible");
            history.observe("peer").accept(packet("OPEN",1,utf("shell:echo test")));
            check(rows(history.page(0)).size()==1,"Disk failure cannot prevent capturing a forwarded request");
            try{history.clear();throw new AssertionError("failed clear accepted");}catch(IOException expected){checks++;}
        }
    }
}
