package io.usblink.mobile;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Bounded device-to-host results, correlated with one transport's ADB stream IDs. */
final class AdbResponse {
    static final int OUTPUT_LIMIT=4096;
    private final BiConsumer<Long,Map<String,Object>> save;
    private final Result result;
    private final String mode;
    private final byte[] shellHead=new byte[5],syncHead=new byte[76];
    private final ArrayDeque<Result> pending=new ArrayDeque<>();
    private int deviceId,used,left,part,headNeed=8;private boolean bound,invalid;
    private String syncOp;

    AdbResponse(long id,String request,BiConsumer<Long,Map<String,Object>> save) {
        this.save=save;result=new Result(id,"");
        int colon=request.indexOf(':');
        String protocol=colon<0?request:request.substring(0,colon),service=protocol.split(",",2)[0];
        String payload=colon<0?"":request.substring(colon+1);
        String action=AdbRequestType.of("service",request).action;
        boolean binary=Arrays.asList("screencap","screenrecord","backup","restore").contains(action);
        if(service.equals("sync")){mode="sync";result.note="文件操作的返回结果记录在各路径请求中";}
        else if((service.equals("shell") && payload.isEmpty()) || binary) {
            mode="omit";result.state="unavailable";result.note=binary?"二进制结果不采集":"交互会话不采集逐键输入及回显";
        } else if(service.equals("abb") || protocol.startsWith("shell,") && Arrays.asList(protocol.split(",")).contains("v2"))mode="shell";
        else if(Arrays.asList("shell","exec","abb_exec","root","unroot","remount","disable-verity","enable-verity","tcpip","usb","reboot").contains(service))mode="text";
        else {mode="omit";result.state="unavailable";result.note="此服务返回协议未解析，不采集其原始数据";}
        result.publish();
    }
    boolean bind(int id,boolean closing,boolean ready) {
        if(id==0)return closing;
        if(bound)return deviceId==id;
        if(!ready)return closing;
        deviceId=id;bound=true;return true;
    }
    void addSync(long id,String operation) {
        Result item=new Result(id,operation);
        if(invalid || pending.size()>=32){item.state="unavailable";item.note="文件结果关联超出上限或协议无法解析";item.publish();return;}
        pending.add(item);item.publish();
    }
    void accept(byte[] bytes,int offset,int length) {
        if(invalid || mode.equals("omit"))return;
        if(mode.equals("text")){result.append(false,bytes,offset,length);return;}
        if(mode.equals("shell"))shell(bytes,offset,length);else sync(bytes,offset,length);
    }
    private void shell(byte[] bytes,int offset,int length) {
        int end=offset+length;
        while(offset<end && !invalid) {
            if(used<5) {
                int n=Math.min(5-used,end-offset);System.arraycopy(bytes,offset,shellHead,used,n);offset+=n;used+=n;
                if(used<5)continue;
                part=shellHead[0]&255;left=word(shellHead,1);
                if(left<0 || left>1024*1024 || part<1 || part>3 || part==3 && left!=1){bad();return;}
                if(left==0){used=0;continue;}
            }
            int n=Math.min(left,end-offset);
            if(part==3){result.exit=bytes[offset]&255;result.state="completed";result.dirty=true;}
            else result.append(part==2,bytes,offset,n);
            offset+=n;left-=n;if(left==0)used=0;
        }
    }
    private void sync(byte[] bytes,int offset,int length) {
        int end=offset+length;
        while(offset<end && !invalid) {
            Result item=pending.peek();
            if(item==null){bad();return;}
            if(used<headNeed) {
                int n=Math.min(headNeed-used,end-offset);System.arraycopy(bytes,offset,syncHead,used,n);offset+=n;used+=n;
                if(used<headNeed)continue;
                if(headNeed==8) {
                    syncOp=new String(syncHead,0,4,StandardCharsets.US_ASCII);
                    boolean expected=syncOp.equals("FAIL") ||
                        (item.operation.equals("SEND") || item.operation.equals("SND2")) && syncOp.equals("OKAY") ||
                        (item.operation.equals("RECV") || item.operation.equals("RCV2")) && (syncOp.equals("DATA") || syncOp.equals("DONE")) ||
                        item.operation.equals("STAT") && syncOp.equals("STAT") ||
                        (item.operation.equals("STA2") || item.operation.equals("LST2")) && syncOp.equals(item.operation) ||
                        item.operation.equals("LIST") && (syncOp.equals("DENT") || syncOp.equals("DONE")) ||
                        item.operation.equals("LIS2") && (syncOp.equals("DNT2") || syncOp.equals("DONE"));
                    if(!expected){bad();return;}
                    switch(syncOp) {
                        case "STAT":headNeed=16;break;
                        case "STA2":case "LST2":headNeed=72;break;
                        case "DENT":headNeed=20;break;
                        case "DNT2":headNeed=76;break;
                        case "DONE":headNeed=item.operation.equals("LIST")?20:item.operation.equals("LIS2")?76:8;break;
                        case "DATA":case "FAIL":case "OKAY":break;
                        default:bad();return;
                    }
                    if(used<headNeed)continue;
                }
                left=syncOp.equals("DATA") || syncOp.equals("FAIL")?word(syncHead,4):
                    syncOp.equals("DENT") || syncOp.equals("DNT2")?word(syncHead,headNeed-4):0;
                if(left<0 || left>(syncOp.equals("DATA")?65536:syncOp.equals("FAIL")?1024*1024:1024)){bad();return;}
                if(syncOp.equals("DENT") || syncOp.equals("DNT2"))item.text(syncOp+" ");
                if(left==0){finishSync(item);continue;}
            }
            int n=Math.min(left,end-offset);
            // DATA is file/APK payload, not command output. Never retain or decode it.
            if(!syncOp.equals("DATA"))item.append(syncOp.equals("FAIL"),bytes,offset,n);
            offset+=n;left-=n;if(left==0)finishSync(item);
        }
    }
    private void finishSync(Result item) {
        boolean terminal=!Arrays.asList("DATA","DENT","DNT2").contains(syncOp);
        if(syncOp.equals("DENT") || syncOp.equals("DNT2"))item.text("\n");
        else if(syncOp.equals("STAT"))item.text("STAT mode="+Integer.toUnsignedString(word(syncHead,4))+" size="+Integer.toUnsignedString(word(syncHead,8))+"\n");
        else if(syncOp.equals("STA2") || syncOp.equals("LST2")) {
            item.text(syncOp+" errno="+Integer.toUnsignedString(word(syncHead,4))+" size="+ByteBuffer.wrap(syncHead).order(ByteOrder.LITTLE_ENDIAN).getLong(40)+"\n");
        }
        if(terminal) {
            item.status=syncOp;item.state="completed";item.dirty=true;
            if(Arrays.asList("STA2","LST2").contains(syncOp) && word(syncHead,4)!=0)item.status=syncOp+" errno="+Integer.toUnsignedString(word(syncHead,4));
            item.publish();pending.remove();
        }
        used=0;headNeed=8;
    }
    private void bad() {
        invalid=true;
        for(Result item:pending){item.state="unparsed";item.note="返回协议未完整解析，不据此判断执行结果";item.dirty=true;item.publish();}
        pending.clear();result.state="unparsed";result.note="返回协议未完整解析，不据此判断执行结果";result.dirty=true;
    }
    void publish(){result.publish();for(Result item:pending)item.publish();}
    void close(boolean interrupted) {
        if(!invalid && (used!=0 || left!=0) && (mode.equals("shell") || mode.equals("sync")))bad();
        result.end(interrupted);
        for(Result item:pending)item.end(true);pending.clear();
    }
    private static int word(byte[] b,int o){return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|((b[o+3]&255)<<24);}
    private final class Result {
        final long id;final String operation;
        final ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();
        String state="pending",status="",note="";Integer exit;
        boolean truncated,binary,dirty=true;
        Result(long id,String operation){this.id=id;this.operation=operation;}
        void append(boolean error,byte[] data,int offset,int length) {
            if(length==0)return;
            int take=Math.min(length,OUTPUT_LIMIT-out.size()-err.size());
            if(take<length && !truncated){truncated=true;dirty=true;}
            if(take>0){
                for(int i=offset;i<offset+take;i++)if((data[i]&255)<32 && data[i]!=9 && data[i]!=10 && data[i]!=13 && data[i]!=27)binary=true;
                (error?err:out).write(data,offset,take);dirty=true;
            }
        }
        void text(String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);append(false,bytes,0,bytes.length);}
        void end(boolean interrupted) {
            if(state.equals("pending")){state=interrupted?"interrupted":"closed";dirty=true;}
            publish();
        }
        String decode(ByteArrayOutputStream bytes) {
            // Decode the complete retained prefix: USB/WRTE boundaries may split UTF-8.
            CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder();
            CharBuffer chars=CharBuffer.allocate(OUTPUT_LIMIT);
            CoderResult decoded=decoder.decode(ByteBuffer.wrap(bytes.toByteArray()),chars,false);
            if(decoded.isError()){binary=true;return "";}
            chars.flip();return chars.toString();
        }
        void publish() {
            if(!dirty)return;dirty=false;
            String stdout=decode(out),stderr=decode(err);
            save.accept(id,Json.map("state",state,"stdout",binary?"":stdout,"stderr",binary?"":stderr,
                "exitCode",exit,"protocolStatus",status,"truncated",truncated,"binary",binary,"note",note,"limit",OUTPUT_LIMIT));
        }
    }
}
