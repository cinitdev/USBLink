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
    private static final int LIMIT=1000,PAGE=100;
    private static final Set<String> FILTERS=new HashSet<>(Arrays.asList("all","install","app","command","file","device","debug","network","other"));
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
                if(Files.size(file)>32*1024*1024)throw new IOException();
                List<String> rows=Files.readAllLines(file,StandardCharsets.UTF_8);
                if(rows.size()>LIMIT)throw new IOException();
                long previous=0;
                for(String row:rows) {
                    Map<String,Object> item=Json.object(Json.parse(row));long id=number(item,"id"),at=number(item,"at");
                    if(id<=previous || id>=Long.MAX_VALUE || at<0)throw new IOException();
                    Map<String,Object> value=entry(id,at,Json.string(item,"peer"),Json.string(item,"kind"),Json.string(item,"command"));
                    if(item.get("response") instanceof Map) {
                        Map<String,Object> response=response(Json.object(item.get("response")));
                        if("pending".equals(response.get("state")))response.put("state","interrupted");
                        value.put("response",response);
                    }
                    entries.add(Collections.unmodifiableMap(value));
                    previous=id;next=id+1;
                }
            }
        } catch(Exception e){entries.clear();next=1;problem="历史记录无法读取；旧文件将在收到新请求或清空时重建";}
    }
    public Consumer<byte[]> observe(String peer) {
        AdbCommandObserver parser=parser(peer);
        return bytes->parser.accept(bytes,0,bytes.length);
    }
    public AdbCommandObserver parser(String peer){return new AdbCommandObserver(new AdbCommandObserver.Recorder() {
        public long request(String kind,String command){return record(peer,kind,command);}
        public void response(long id,Map<String,Object> value){update(id,value);}
    });}
    private static long number(Map<String,Object> data,String key){Object value=data.get(key);if(!(value instanceof Long) && !(value instanceof Integer))throw new IllegalArgumentException("历史记录参数无效");return ((Number)value).longValue();}
    private synchronized long record(String peer,String kind,String command) {
        if(closed)return -1;
        long id=next++;
        entries.add(Collections.unmodifiableMap(entry(id,System.currentTimeMillis(),peer,kind,command)));
        if(entries.size()>LIMIT)entries.remove(0);revision++;
        schedule();return id;
    }
    private synchronized void update(long id,Map<String,Object> response) {
        if(closed)return;
        for(int i=entries.size()-1;i>=0;i--)if((Long)entries.get(i).get("id")==id) {
            Map<String,Object> item=new LinkedHashMap<>(entries.get(i));item.put("response",response(response));
            entries.set(i,Collections.unmodifiableMap(item));revision++;schedule();return;
        }
        // Evicted/cleared requests cannot be recreated by a delayed device response.
    }
    private void schedule() {
        if(!scheduled){scheduled=true;writer.schedule(this::persist,1,TimeUnit.SECONDS);}
    }
    private static Map<String,Object> entry(long id,long at,String peer,String kind,String command) {
        AdbRequestType type=AdbRequestType.of(kind,command);
        return Json.map("id",id,"at",at,"peer",clean(peer,64),"kind",clean(kind,24),"command",clean(AdbRequestType.printable(command),4096),"category",type.category,"action",type.action);
    }
    private static Map<String,Object> response(Map<String,Object> data) {
        String state=String.valueOf(data.getOrDefault("state","unavailable"));
        if(!Arrays.asList("pending","completed","closed","interrupted","unavailable","unparsed").contains(state))state="unavailable";
        Object exit=data.get("exitCode");
        if(!(exit instanceof Number) || ((Number)exit).longValue()<0 || ((Number)exit).longValue()>255)exit=null;
        String stdout=cleanOutput(String.valueOf(data.getOrDefault("stdout",""))),stderr=cleanOutput(String.valueOf(data.getOrDefault("stderr","")));
        boolean truncated=Boolean.TRUE.equals(data.get("truncated")) ||
            stdout.getBytes(StandardCharsets.UTF_8).length+stderr.getBytes(StandardCharsets.UTF_8).length>AdbResponse.OUTPUT_LIMIT;
        stdout=utf8Limit(stdout,AdbResponse.OUTPUT_LIMIT);
        stderr=utf8Limit(stderr,AdbResponse.OUTPUT_LIMIT-stdout.getBytes(StandardCharsets.UTF_8).length);
        return Json.map("state",state,"stdout",stdout,"stderr",stderr,"exitCode",exit,
            "protocolStatus",clean(String.valueOf(data.getOrDefault("protocolStatus","")),80),
            "truncated",truncated,"binary",Boolean.TRUE.equals(data.get("binary")),
            "note",clean(String.valueOf(data.getOrDefault("note","")),160),"limit",AdbResponse.OUTPUT_LIMIT);
    }
    private static String utf8Limit(String text,int limit) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length<=limit)return text;
        int end=limit;while(end>0 && (bytes[end]&0xc0)==0x80)end--;
        return new String(bytes,0,end,StandardCharsets.UTF_8);
    }
    private static String clean(String value,int limit) {
        // Pairing material and common credential arguments must not become a second secret store.
        value=redact(value).replaceAll("[\\p{Cc}\\p{Cf}]"," ");
        if(value.length()>limit){int end=limit-1;if(Character.isHighSurrogate(value.charAt(end-1)))end--;value=value.substring(0,end)+"…";}
        return value;
    }
    private static String redact(String value) {
        return value.replaceAll("(?i)USBLINK(?:1)?-[A-Za-z0-9_+/=.-]+","[配对码已隐藏]")
            .replaceAll("(?i)((?:password|passwd|token|secret|api[_-]?key|authorization)[\"']?\\s*[=:]\\s*|--(?:password|token|secret|api-key)\\s+)(?:\"[^\"]*\"|'[^']*'|[^\\s]+)","$1[已隐藏]");
    }
    private static String cleanOutput(String value) {
        // Preserve readable lines, strip terminal escapes; never render returned HTML.
        value=redact(value.replaceAll("\\x1b\\][^\\x07\\x1b]*(?:\\x07|\\x1b\\\\|$)","")
            .replaceAll("\\x1b\\[[0-?]*[ -/]*[@-~]",""))
            .replaceAll("[\\p{Cc}\\p{Cf}&&[^\\r\\n\\t]]","");
        return value.length()>4096?value.substring(0,4095)+"…":value;
    }
    public synchronized Map<String,Object> page(long before) {
        return page(before,"all","");
    }
    private Map<String,Object> page(long before,String filter,String query) {
        List<Map<String,Object>> result=new ArrayList<>();
        int matched=0,bytes=0;boolean more=false;String needle=query.toLowerCase(Locale.ROOT);
        for(int i=entries.size()-1;i>=0;i--) {
            Map<String,Object> item=entries.get(i);
            boolean category=filter.equals("all") || filter.equals(item.get("category")) || filter.equals("install") && ((String)item.get("action")).startsWith("install");
            if(!category)continue;
            String display=needle.isEmpty()?"":AdbRequestType.adbCommand((String)item.get("kind"),(String)item.get("command"));
            String alias=((String)item.get("action")).startsWith("install")?"adb install":item.get("action").equals("uninstall")?"adb uninstall":"";
            Map<String,Object> response=item.get("response") instanceof Map?Json.object(item.get("response")):Collections.emptyMap();
            if(!needle.isEmpty() && !(item.get("command")+" "+display+" "+alias+" "+item.get("peer")+" "+response.getOrDefault("stdout","")+" "+response.getOrDefault("stderr","")).toLowerCase(Locale.ROOT).contains(needle))continue;
            matched++;
            if(before==0 || (Long)item.get("id")<before) {
                if(more || result.size()>=PAGE){more=true;continue;}
                if(display.isEmpty())display=AdbRequestType.adbCommand((String)item.get("kind"),(String)item.get("command"));
                Map<String,Object> view=new LinkedHashMap<>(item);view.put("adbCommand",display);
                int size=Json.stringify(view).getBytes(StandardCharsets.UTF_8).length+1;
                // Stop at the first full page: never skip a long row and break the cursor.
                if(bytes+size<=40000){result.add(view);bytes+=size;}else more=true;
            }
        }
        return Json.map("entries",result,"count",entries.size(),"epoch",epoch,"revision",revision,"hasMore",more,"problem",problem,"limit",LIMIT,"matchedCount",matched,"filter",filter,"query",query);
    }
    public Map<String,Object> page(Map<String,Object> input)throws IOException {
        long before=number(input,"before");
        String filter=input.containsKey("filter")?Json.string(input,"filter"):"all",query=input.containsKey("query")?Json.string(input,"query").trim():"";
        if(!FILTERS.contains(filter) || query.length()>128)throw new IOException("历史筛选条件无效");
        synchronized(this){if(before<0 || !epoch.equals(Json.string(input,"epoch")))throw new IOException("历史记录已更新，请返回最新记录");return page(before,filter,query);}
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
