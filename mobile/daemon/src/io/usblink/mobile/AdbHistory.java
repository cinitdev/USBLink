package io.usblink.mobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Private rolling request history. Disk I/O is coalesced off the transport threads. */
public final class AdbHistory implements AutoCloseable {
    private static final int LIMIT=200,PAGE=20;
    private final Path file;
    private final List<Map<String,Object>> entries=new ArrayList<>();
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"usblink-history");t.setDaemon(true);return t;});
    private String epoch=UUID.randomUUID().toString(),problem="";
    private long next=1,revision,savedRevision;
    private boolean scheduled,closed;
    public AdbHistory(Path state) {
        file=state.resolve("adb-history.jsonl");
        try {
            if(Files.exists(file)) {
                if(Files.size(file)>1024*1024)throw new IOException();
                List<String> rows=Files.readAllLines(file,StandardCharsets.UTF_8);
                if(rows.size()>LIMIT)throw new IOException();
                long previous=0;
                for(String row:rows) {
                    Map<String,Object> item=Json.object(Json.parse(row));long id=number(item,"id"),at=number(item,"at");
                    if(id<=previous || id>=Long.MAX_VALUE || at<0)throw new IOException();
                    entries.add(entry(id,at,Json.string(item,"peer"),Json.string(item,"kind"),Json.string(item,"command")));
                    previous=id;next=id+1;
                }
            }
        } catch(Exception e){entries.clear();next=1;problem="历史记录无法读取；旧文件将在收到新请求或清空时重建";}
    }
    public Consumer<byte[]> observe(String peer) {
        AdbCommandObserver parser=parser(peer);
        return bytes->parser.accept(bytes,0,bytes.length);
    }
    public AdbCommandObserver parser(String peer){return new AdbCommandObserver((kind,command)->record(peer,kind,command));}
    private static long number(Map<String,Object> data,String key){Object value=data.get(key);if(!(value instanceof Long) && !(value instanceof Integer))throw new IllegalArgumentException("历史记录参数无效");return ((Number)value).longValue();}
    private synchronized void record(String peer,String kind,String command) {
        if(closed)return;
        entries.add(entry(next++,System.currentTimeMillis(),peer,kind,command));
        if(entries.size()>LIMIT)entries.remove(0);revision++;
        if(!scheduled){scheduled=true;writer.schedule(this::persist,1,TimeUnit.SECONDS);}
    }
    private static Map<String,Object> entry(long id,long at,String peer,String kind,String command) {
        return Collections.unmodifiableMap(Json.map("id",id,"at",at,"peer",clean(peer,64),"kind",clean(kind,24),"command",clean(command,512)));
    }
    private static String clean(String value,int limit) {
        // Pairing material and common credential arguments must not become a second secret store.
        value=value.replace('\0',' ').replaceAll("(?i)USBLINK(?:1)?-[A-Za-z0-9_+/=.-]+","[配对码已隐藏]")
            .replaceAll("(?i)((?:password|passwd|token|secret|api[_-]?key|authorization)\\s*[=:]\\s*|--(?:password|token|secret|api-key)\\s+)(?:\"[^\"]*\"|'[^']*'|[^\\s]+)","$1[已隐藏]")
            .replaceAll("[\\p{Cc}\\p{Cf}]"," ");
        if(value.length()>limit){int end=limit-1;if(Character.isHighSurrogate(value.charAt(end-1)))end--;value=value.substring(0,end)+"…";}
        return value;
    }
    public synchronized Map<String,Object> page(long before) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(int i=entries.size()-1;i>=0 && result.size()<PAGE;i--){Map<String,Object> item=entries.get(i);if(before==0 || (Long)item.get("id")<before)result.add(item);}
        long oldest=entries.isEmpty()?0:(Long)entries.get(0).get("id");
        boolean more=!result.isEmpty() && (Long)result.get(result.size()-1).get("id")>oldest;
        return Json.map("entries",result,"count",entries.size(),"epoch",epoch,"hasMore",more,"problem",problem,"limit",LIMIT);
    }
    public Map<String,Object> page(Map<String,Object> input)throws IOException {
        long before=number(input,"before");
        synchronized(this){if(before<1 || !epoch.equals(Json.string(input,"epoch")))throw new IOException("历史记录已更新，请返回最新记录");return page(before);}
    }
    public void clear()throws IOException {
        synchronized(this){entries.clear();epoch=UUID.randomUUID().toString();revision++;}
        flush();
    }
    private void persist() {
        List<Map<String,Object>> copy;long stamp;
        synchronized(this){scheduled=false;stamp=revision;if(stamp==savedRevision)return;copy=new ArrayList<>(entries);}
        try {
            Files.createDirectories(file.getParent());Path temp=file.resolveSibling("adb-history.json.new");
            StringBuilder lines=new StringBuilder();for(Map<String,Object> item:copy)lines.append(Json.stringify(item)).append('\n');
            byte[] bytes=lines.toString().getBytes(StandardCharsets.UTF_8);
            Files.write(temp,bytes);
            try{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
            synchronized(this){savedRevision=stamp;problem="";}
        }catch(IOException|RuntimeException e){synchronized(this){problem="历史记录保存失败，重启后可能丢失；请检查模块数据目录";}}
    }
    private void flush()throws IOException {
        try{writer.submit(this::persist).get(5,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("历史记录保存被中断");}
        catch(ExecutionException|TimeoutException e){throw new IOException("历史记录保存尚未确认，请稍后检查");}
        synchronized(this){if(!problem.isEmpty())throw new IOException(problem);}
    }
    @Override public void close(){synchronized(this){if(closed)return;closed=true;}try{flush();}catch(IOException ignored){}writer.shutdown();}
}
