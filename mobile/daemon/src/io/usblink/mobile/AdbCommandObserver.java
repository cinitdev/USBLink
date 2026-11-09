package io.usblink.mobile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Passive, bounded duplex observer. Never changes or executes traffic. */
public final class AdbCommandObserver {
    public interface Recorder {
        long request(String kind,String command);
        void response(long id,Map<String,Object> value);
    }
    private static final int OPEN=0x4e45504f, WRTE=0x45545257, CLSE=0x45534c43, OKAY=0x59414b4f;
    private final Recorder record;
    private final byte[] header=new byte[24], service=new byte[4096];
    private final Map<Integer,Sync> syncs=new LinkedHashMap<>();
    private final Map<Integer,AdbResponse> replies=new LinkedHashMap<>();
    private final Replies incoming=new Replies();
    private int used,remaining,command,local,captured;
    private boolean disabled,closed,serviceTruncated;
    public AdbCommandObserver(BiConsumer<String,String> record){this(new Recorder() {
        public long request(String kind,String command){record.accept(kind,command);return 0;}
        public void response(long id,Map<String,Object> value){}
    });}
    public AdbCommandObserver(Recorder record){this.record=record;}
    public synchronized void accept(byte[] data,int offset,int length) {
        if(disabled || closed)return;
        try {
            int end=offset+length;
            while(offset<end) {
                if(used<24) {
                    int n=Math.min(24-used,end-offset);System.arraycopy(data,offset,header,used,n);used+=n;offset+=n;
                    if(used<24)continue;
                    command=word(header,0);local=word(header,4);remaining=word(header,12);captured=0;serviceTruncated=false;
                    if(word(header,20)!=~command || remaining<0 || remaining>1024*1024) {
                        disabled=true;record.request("unparsed","ADB 数据格式无法识别，本次会话后续请求未记录");close();return;
                    }
                    if(command==CLSE)end(local,true);
                    if(remaining==0){finish();continue;}
                }
                int n=Math.min(remaining,end-offset);
                if(command==OPEN) {
                    int take=Math.min(n,service.length-captured);
                    if(take<n)serviceTruncated=true;
                    System.arraycopy(data,offset,service,captured,take);captured+=take;
                } else if(command==WRTE && syncs.containsKey(local))syncs.get(local).accept(data,offset,n);
                remaining-=n;offset+=n;if(remaining==0)finish();
            }
        } catch(RuntimeException ignored) { disabled=true; /* Observation must never interrupt a USB transfer. */ }
    }
    private void finish() {
        if(command==OPEN) {
            end(local,true);
            String text=new String(service,0,captured,StandardCharsets.UTF_8);
            while(text.endsWith("\0"))text=text.substring(0,text.length()-1);
            if(serviceTruncated)text+="… [请求超过 4096 字节，已截断]";
            String kind=text.startsWith("shell")?"shell":text.startsWith("exec:")?"exec":
                text.startsWith("abb")?"app":text.equals("sync:")?"sync":"service";
            long id=record.request(kind,text);
            if(replies.size()>=64)end(replies.keySet().iterator().next(),true);
            AdbResponse response=new AdbResponse(id,text,record::response);
            replies.put(local,response);
            if(text.equals("sync:"))syncs.put(local,new Sync(response));
        }
        used=0;
    }
    private static int word(byte[] b,int o){return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|((b[o+3]&255)<<24);}
    public synchronized void returned(byte[] bytes,int offset,int length) {
        if(disabled || closed)return;
        try{incoming.accept(bytes,offset,length);}catch(RuntimeException ignored){disabled=true;close();}
    }
    private void end(int id,boolean interrupted) {
        syncs.remove(id);AdbResponse response=replies.remove(id);
        if(response!=null)response.close(interrupted);
    }
    public synchronized void close(){closed=true;for(Integer id:new java.util.ArrayList<>(replies.keySet()))end(id,true);}
    private final class Replies {
        final byte[] head=new byte[24];int used,left,command,remote;AdbResponse stream;
        void accept(byte[] data,int offset,int length) {
            int end=offset+length;
            while(offset<end) {
                if(used<24) {
                    int n=Math.min(24-used,end-offset);System.arraycopy(data,offset,head,used,n);used+=n;offset+=n;
                    if(used<24)continue;
                    command=word(head,0);remote=word(head,8);left=word(head,12);
                    if(word(head,20)!=~command || left<0 || left>1024*1024)throw new IllegalArgumentException("ADB reply");
                    // arg1 is the host stream ID; reject an old device stream after ID reuse.
                    stream=command==WRTE || command==CLSE || command==OKAY?replies.get(remote):null;
                    if(stream!=null && !stream.bind(word(head,4),command==CLSE,command==OKAY))stream=null;
                    if(left==0){finish();continue;}
                }
                int n=Math.min(left,end-offset);
                if(command==WRTE && stream!=null)stream.accept(data,offset,n);
                offset+=n;left-=n;if(left==0)finish();
            }
        }
        void finish(){
            if(command==CLSE && stream!=null)end(remote,false);
            else if(stream!=null)stream.publish();
            used=0;
        }
    }

    /** SYNC request metadata only; DATA/APK contents are skipped, including compressed v2 DATA. */
    private final class Sync {
        final AdbResponse response;
        Sync(AdbResponse response){this.response=response;}
        final byte[] head=new byte[8],path=new byte[1024];
        int count,left,have,extra;String op;boolean stopped;
        void accept(byte[] data,int offset,int length) {
            int end=offset+length;
            while(offset<end && !stopped) {
                if(extra>0){int n=Math.min(extra,end-offset);offset+=n;extra-=n;continue;}
                if(count<8) {
                    int n=Math.min(8-count,end-offset);System.arraycopy(data,offset,head,count,n);count+=n;offset+=n;
                    if(count<8)continue;
                    op=new String(head,0,4,StandardCharsets.US_ASCII);left=word(head,4);have=0;
                    if(op.equals("DONE")){count=0;continue;}
                    if(op.equals("QUIT")){stopped=true;return;}
                    if(!op.matches("SEND|SND2|RECV|RCV2|STAT|STA2|LST2|LIST|LIS2|DATA") || left<0 || left>(op.equals("DATA")?65536:1024)) {
                        stopped=true;record.request("unparsed","文件传输协议无法识别，后续文件路径未记录");return;
                    }
                    if(left==0){finishPath();continue;}
                }
                int n=Math.min(left,end-offset);
                if(!op.equals("DATA")){int take=Math.min(n,path.length-have);System.arraycopy(data,offset,path,have,take);have+=take;}
                offset+=n;left-=n;if(left==0)finishPath();
            }
        }
        void finishPath() {
            if(!op.equals("DATA")) {
                String value=new String(path,0,have,StandardCharsets.UTF_8);
                if(op.equals("SEND")){int comma=value.lastIndexOf(',');if(comma>=0)value=value.substring(0,comma);}
                String kind=op.startsWith("SND") || op.equals("SEND")?"push":op.startsWith("RCV") || op.equals("RECV")?"pull":"file";
                response.addSync(record.request(kind,op+" "+value),op);
                // v2 follows its path with a second ID plus mode/flags or flags.
                extra=op.equals("SND2")?12:op.equals("RCV2")?8:0;
            }
            count=0;
        }
    }
}
