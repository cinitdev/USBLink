package io.usblink.mobile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Passive, bounded parser of host-to-adbd bytes. Never changes or executes traffic. */
public final class AdbCommandObserver {
    private static final int OPEN=0x4e45504f, WRTE=0x45545257, CLSE=0x45534c43;
    private final BiConsumer<String,String> record;
    private final byte[] header=new byte[24], service=new byte[4096];
    private final Map<Integer,Sync> syncs=new HashMap<>();
    private int used,remaining,command,local,captured;
    private boolean disabled;
    public AdbCommandObserver(BiConsumer<String,String> record){this.record=record;}
    public void accept(byte[] data,int offset,int length) {
        if(disabled)return;
        try {
            int end=offset+length;
            while(offset<end) {
                if(used<24) {
                    int n=Math.min(24-used,end-offset);System.arraycopy(data,offset,header,used,n);used+=n;offset+=n;
                    if(used<24)continue;
                    command=word(header,0);local=word(header,4);remaining=word(header,12);captured=0;
                    if(word(header,20)!=~command || remaining<0 || remaining>1024*1024) {
                        disabled=true;record.accept("unparsed","ADB 数据格式无法识别，本次会话后续请求未记录");return;
                    }
                    if(command==CLSE)syncs.remove(local);
                    if(remaining==0){finish();continue;}
                }
                int n=Math.min(remaining,end-offset);
                if(command==OPEN) {
                    int take=Math.min(n,service.length-captured);
                    System.arraycopy(data,offset,service,captured,take);captured+=take;
                } else if(command==WRTE && syncs.containsKey(local))syncs.get(local).accept(data,offset,n);
                remaining-=n;offset+=n;if(remaining==0)finish();
            }
        } catch(RuntimeException ignored) { disabled=true; /* Observation must never interrupt a USB transfer. */ }
    }
    private void finish() {
        if(command==OPEN) {
            syncs.remove(local);
            String text=new String(service,0,captured,StandardCharsets.UTF_8);
            while(text.endsWith("\0"))text=text.substring(0,text.length()-1);
            if(text.equals("sync:")) {
                if(syncs.size()<64)syncs.put(local,new Sync());
                else record.accept("unparsed","并发文件通道超过记录上限，新通道只记录打开请求");
            }
            String kind=text.startsWith("shell")?"shell":text.startsWith("exec:")?"exec":
                text.startsWith("abb")?"app":text.equals("sync:")?"sync":"service";
            record.accept(kind,text);
        }
        used=0;
    }
    private static int word(byte[] b,int o){return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|((b[o+3]&255)<<24);}

    /** SYNC request metadata only; DATA/APK contents are skipped, including compressed v2 DATA. */
    private final class Sync {
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
                        stopped=true;record.accept("unparsed","文件传输协议无法识别，后续文件路径未记录");return;
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
                record.accept(kind,op+" "+value);
                // v2 follows its path with a second ID plus mode/flags or flags.
                extra=op.equals("SND2")?12:op.equals("RCV2")?8:0;
            }
            count=0;
        }
    }
}
