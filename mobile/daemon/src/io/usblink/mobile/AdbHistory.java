package io.usblink.mobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded SQLite history. Transport callbacks never wait for database I/O. */
public final class AdbHistory implements AutoCloseable {
    private static final int LIMIT=200;
    private static final Set<String> FILTERS=new HashSet<>(Arrays.asList("all","install","app","command","file","device","debug","network","other"));
    private final List<Map<String,Object>> entries=new ArrayList<>();
    private final Map<Long,Map<String,Object>> dirty=new LinkedHashMap<>();
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"usblink-history");t.setDaemon(true);return t;});
    private HistorySqlStore store;
    private String epoch=UUID.randomUUID().toString(),problem="";
    private long next=1,revision,savedRevision,clearRevision;
    private String savedEpoch=epoch;
    private boolean scheduled,closed,clearPending;
    public AdbHistory(Path state,HistoryDatabase.Factory factory) {
        try {
            Path database=StateLayout.file(state,"adb-history.db");
            StateLayout.adoptCompanions(database);
            Files.createDirectories(state);
            store=new HistorySqlStore(factory.open(database));
            store.db.begin();boolean committed=false;
            try {
                store.db.exec("UPDATE history SET responseState='interrupted',version=version+1 WHERE responseState='pending'");
                store.prune();
                // Apply the command-only policy to the existing SQLite rows too.
                // JSON history is never opened. Preserve the earliest row and its result.
                String previous=null;
                for(Map<String,Object> item:store.load()) {
                    long id=number(item,"id");next=Math.max(next,id+1);
                    String command=AdbRequestType.recordedCommand((String)item.get("kind"),(String)item.get("command"));
                    if(command==null){store.db.exec("DELETE FROM history WHERE id=?",id);continue;}
                    String key=(String)item.get("signature");
                    if(!key.startsWith("command:")) {
                        // Old sanitized/truncated arguments cannot prove identical originals.
                        if(!command.contains("[已隐藏]") && !command.contains("[配对码已隐藏]") && !command.contains("…"))
                            key=signature((String)item.get("peer"),command);
                        if(!key.equals(item.get("signature"))){item.put("signature",key);store.put(item);}
                    }
                    if(key.equals(previous)){store.db.exec("DELETE FROM history WHERE id=?",id);continue;}
                    previous=key;
                }
                committed=true;
            }finally{store.db.end(committed);}
            entries.addAll(store.load());
            for(Map<String,Object> item:entries)next=Math.max(next,number(item,"id")+1);
        } catch(Exception e) {
            if(store!=null)try{store.close();}catch(IOException ignored){}
            store=null;entries.clear();problem="历史数据库无法打开；请检查模块数据目录，已有数据未被覆盖";
        }
    }
    public Consumer<byte[]> observe(String peer){AdbCommandObserver parser=parser(peer);return bytes->parser.accept(bytes,0,bytes.length);}
    public AdbCommandObserver parser(String peer){return new AdbCommandObserver(new AdbCommandObserver.Recorder() {
        public long request(String kind,String command){return record(peer,kind,command);}
        public void response(long id,Map<String,Object> value){update(id,value);}
    });}
    private static long number(Map<String,Object> data,String key){Object value=data.get(key);if(!(value instanceof Long) && !(value instanceof Integer))throw new IllegalArgumentException("历史记录参数无效");return ((Number)value).longValue();}
    private synchronized long record(String peer,String kind,String command) {
        if(closed || store==null)return -1;
        String normalized=AdbRequestType.recordedCommand(kind,command);
        if(normalized==null)return -1;
        Map<String,Object> item=entry(next,System.currentTimeMillis(),peer,kind,command,normalized);
        if(!entries.isEmpty() && item.get("signature").equals(entries.get(entries.size()-1).get("signature")))return -1;
        long id=next++;entries.add(item);
        if(entries.size()>LIMIT)dirty.remove(number(entries.remove(0),"id"));
        revision++;dirty.put(id,item);schedule();return id;
    }
    private synchronized void update(long id,Map<String,Object> response) {
        if(closed || id<0)return; // Suppressed repeats must never overwrite the first request's response.
        for(int i=entries.size()-1;i>=0;i--)if(number(entries.get(i),"id")==id) {
            Map<String,Object> item=new LinkedHashMap<>(entries.get(i));item.put("response",response(response));
            item.put("version",number(item,"version")+1);entries.set(i,item);dirty.put(id,item);revision++;schedule();return;
        }
    }
    private void schedule(){if(!scheduled){scheduled=true;writer.schedule(this::persist,300,TimeUnit.MILLISECONDS);}}
    private static String signature(String peer,String command) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String value:new String[]{peer,command}) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
            return "command:"+Base64.getEncoder().encodeToString(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static Map<String,Object> entry(long id,long at,String peer,String kind,String command,String normalized) {
        AdbRequestType type=AdbRequestType.of(kind,command);
        return Json.map("id",id,"at",at,"peer",clean(peer,64),"kind",clean(kind,24),"command",clean(AdbRequestType.printable(command),4096),
            "category",type.category,"action",type.action,"signature",signature(peer,normalized),"version",1L);
    }
    static String preview(String text){return text.getBytes(StandardCharsets.UTF_8).length<=120?text:utf8Limit(text,117)+"…";}
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
        // Pairing uses USBLINK1- plus URL-safe Base64. Plain usblink-* filenames,
        // module names and network names are not pairing codes. Redact incomplete
        // versioned codes too, because a truncated result can still contain secrets.
        return value.replaceAll("(?i)USBLINK1-[A-Za-z0-9_-]+={0,2}","[配对码已隐藏]")
            .replaceAll("(?i)((?:password|passwd|token|secret|api[_-]?key|authorization)[\"']?\\s*[=:]\\s*|--(?:password|token|secret|api-key)\\s+)(?:\"[^\"]*\"|'[^']*'|[^\\s]+)","$1[已隐藏]");
    }
    private static String cleanOutput(String value) {
        // Preserve readable lines, strip terminal escapes; never render returned HTML.
        value=redact(value.replaceAll("\\x1b\\][^\\x07\\x1b]*(?:\\x07|\\x1b\\\\|$)","")
            .replaceAll("\\x1b\\[[0-?]*[ -/]*[@-~]",""))
            .replaceAll("[\\p{Cc}\\p{Cf}&&[^\\r\\n\\t]]","");
        return value.length()>4096?value.substring(0,4095)+"…":value;
    }
    public Map<String,Object> page(long before) {
        try{return query(before,"all","",null,0);}
        catch(IOException e){synchronized(this){return Json.map("entries",new ArrayList<>(),"count",entries.size(),"epoch",epoch,"revision",revision,"hasMore",false,"problem",problem.isEmpty()?e.getMessage():problem,"limit",LIMIT);}}
    }
    public Map<String,Object> page(Map<String,Object> input)throws IOException {
        long before=input.containsKey("before")?number(input,"before"):0,detail=input.containsKey("id")?number(input,"id"):0;
        String filter=input.containsKey("filter")?Json.string(input,"filter"):"all",query=input.containsKey("query")?Json.string(input,"query").trim():"";
        if(before<0 || detail<0 || !FILTERS.contains(filter) || query.length()>128)throw new IOException("历史筛选条件无效");
        return query(before,filter,query,Json.string(input,"epoch"),detail);
    }
    private Map<String,Object> query(long before,String filter,String query,String expected,long detail)throws IOException {
        return await(()->{
            persist();String stamp;long version;
            synchronized(this){
                if(store==null || !problem.isEmpty())throw new IOException(problem);
                if(expected!=null && !epoch.equals(expected))throw new IOException("历史记录已更新，请返回最新记录");
                if(!epoch.equals(savedEpoch))throw new IOException("历史记录已更新，请返回最新记录");
                stamp=savedEpoch;version=savedRevision;
            }
            Map<String,Object> page;
            if(detail>0) {
                Map<String,Object> item=store.detail(detail);item.remove("signature");
                page=Json.map("entries",Collections.singletonList(item),"hasMore",false,"detail",true);
            } else page=store.page(before,filter,query);
            synchronized(this){if(!stamp.equals(epoch))throw new IOException("历史记录已更新，请返回最新记录");}
            page.put("epoch",stamp);page.put("revision",version);page.put("problem","");page.put("limit",LIMIT);page.put("storage","sqlite");return page;
        });
    }
    public void clear()throws IOException {
        synchronized(this){if(store==null)throw new IOException(problem);entries.clear();dirty.clear();clearPending=true;epoch=UUID.randomUUID().toString();clearRevision=++revision;}
        await(()->{persist();synchronized(this){if(!problem.isEmpty())throw new IOException(problem);}return null;});
    }
    private void persist() {
        Map<Long,Map<String,Object>> copy;long stamp,clearStamp;boolean clear;String snapshotEpoch;
        synchronized(this){scheduled=false;if(store==null || revision==savedRevision)return;stamp=revision;copy=new LinkedHashMap<>(dirty);clear=clearPending;clearStamp=clearRevision;snapshotEpoch=epoch;}
        try {
            store.db.begin();boolean commit=false;
            try {if(clear)store.db.exec("DELETE FROM history");for(Map<String,Object> item:copy.values())store.put(item);store.prune();commit=true;}
            finally{store.db.end(commit);}
            synchronized(this){
                savedRevision=stamp;savedEpoch=snapshotEpoch;problem="";
                // A clear arriving during I/O must remain pending for the next transaction.
                if(clear && clearRevision==clearStamp)clearPending=false;
                for(Map.Entry<Long,Map<String,Object>> item:copy.entrySet())if(dirty.get(item.getKey())==item.getValue())dirty.remove(item.getKey());
            }
        }catch(IOException|RuntimeException e){synchronized(this){problem="历史数据库保存失败，请检查模块数据目录；USB 转发不受影响";}}
    }
    private <T> T await(Callable<T> action)throws IOException {
        try{return writer.submit(action).get(5,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("历史记录读取被中断");}
        catch(ExecutionException e){if(e.getCause() instanceof IOException)throw (IOException)e.getCause();throw new IOException("历史数据库查询失败，请稍后重试");}
        catch(TimeoutException|RejectedExecutionException e){throw new IOException("历史记录处理尚未完成，请稍后检查");}
    }
    @Override public void close() {
        synchronized(this){if(closed)return;closed=true;}
        try{await(()->{persist();if(store!=null)store.close();return null;});}catch(IOException ignored){}
        writer.shutdown();
    }
}
