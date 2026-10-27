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
    public static void main(String[] args)throws Exception {parser();storage();tcp();failures();System.out.println("PASS: "+checks+" ADB history checks");}
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
            for(int i=0;i<230;i++)observe.accept(packet("OPEN",i,utf("shell:echo "+i+"汉".repeat(600))));
            Map<String,Object> page=history.page(0);epoch=Json.string(page,"epoch");
            check((Integer)page.get("count")==200 && rows(page).size()==20,"Storage and automatic status page bounded");
            check(Json.stringify(page).getBytes(StandardCharsets.UTF_8).length<40000,"History page fits the existing 64KiB control response");
            check(Json.string(Json.object(rows(page).get(0)),"command").length()==512,"Long commands truncated");
            long before=((Number)Json.object(rows(page).get(19)).get("id")).longValue();
            Map<String,Object> older=history.page(Json.map("before",before,"epoch",epoch));
            check(rows(older).size()==20 && ((Number)Json.object(rows(older).get(0)).get("id")).longValue()==before-1,"Cursor page has no overlapping entry");
        }
        check(Files.size(dir.resolve("adb-history.jsonl"))>65536,"Reload test exceeds single JSON codec limit");
        try(AdbHistory history=new AdbHistory(dir)) {
            check((Integer)history.page(0).get("count")==200,"History survives daemon restart");
            try{history.page(Json.map("before",200,"epoch",epoch));throw new AssertionError("stale epoch accepted");}catch(IOException expected){checks++;}
            Consumer<byte[]> observe=history.observe("10.126.126.2");
            observe.accept(packet("OPEN",999,utf("shell:echo USBLINK1-private_pairing --token super-secret password='private pass'\u001b[0m")));
            String text=Json.stringify(history.page(0));check(!text.contains("private_pairing") && !text.contains("super-secret") && !text.contains("private pass") && !text.contains("\\u001b"),"Pairing and recognized credential values redacted; terminal controls stripped");
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
