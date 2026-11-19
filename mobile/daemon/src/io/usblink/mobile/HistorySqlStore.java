package io.usblink.mobile;

import java.io.IOException;
import java.util.*;

/** Typed columns only. JSON is only a control-wire format, never database storage. */
final class HistorySqlStore implements AutoCloseable {
    final HistoryDatabase db;
    static final String FIELDS="id,at,peer,kind,command,category,action,adbCommand,signature,version,responseState,stdout,stderr,exitCode,protocolStatus,truncated,binary,note,searchText";
    static final String SUMMARY="id,at,peer,kind,substr(command,1,120) AS command,category,action,substr(adbCommand,1,120) AS adbCommand,version,responseState,exitCode,protocolStatus,truncated,binary";
    HistorySqlStore(HistoryDatabase db)throws IOException {
        this.db=db;
        try {
        db.exec("CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY, at INTEGER NOT NULL, peer TEXT NOT NULL, kind TEXT NOT NULL, command TEXT NOT NULL, category TEXT NOT NULL, action TEXT NOT NULL, adbCommand TEXT NOT NULL, signature TEXT NOT NULL, version INTEGER NOT NULL, responseState TEXT, stdout TEXT NOT NULL, stderr TEXT NOT NULL, exitCode INTEGER, protocolStatus TEXT NOT NULL, truncated INTEGER NOT NULL, binary INTEGER NOT NULL, note TEXT NOT NULL, searchText TEXT NOT NULL)");
        db.exec("CREATE INDEX IF NOT EXISTS history_category_id ON history(category,id)");
        } catch(IOException|RuntimeException failure) {
            try{db.close();}catch(Exception closeFailure){failure.addSuppressed(closeFailure);}
            throw failure;
        }
    }
    List<Map<String,Object>> load()throws IOException {
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:db.query("SELECT "+FIELDS+" FROM history ORDER BY id ASC"))out.add(decode(row,false));
        return out;
    }
    void put(Map<String,Object> item)throws IOException {
        Map<String,Object> response=item.get("response") instanceof Map?Json.object(item.get("response")):Collections.emptyMap();
        String display=AdbRequestType.adbCommand((String)item.get("kind"),(String)item.get("command"));
        String alias=String.valueOf(item.get("action")).startsWith("install")?"adb install":item.get("action").equals("uninstall")?"adb uninstall":"";
        String search=(item.get("command")+" "+display+" "+alias+" "+item.get("peer")+" "+response.getOrDefault("stdout","")+" "+response.getOrDefault("stderr","")).toLowerCase(Locale.ROOT);
        db.exec("INSERT OR REPLACE INTO history ("+FIELDS+") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            item.get("id"),item.get("at"),item.get("peer"),item.get("kind"),item.get("command"),item.get("category"),item.get("action"),display,item.get("signature"),item.get("version"),
            response.get("state"),response.getOrDefault("stdout",""),response.getOrDefault("stderr",""),response.get("exitCode"),response.getOrDefault("protocolStatus",""),
            Boolean.TRUE.equals(response.get("truncated"))?1:0,Boolean.TRUE.equals(response.get("binary"))?1:0,response.getOrDefault("note",""),search);
    }
    void prune()throws IOException{db.exec("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 200)");}
    int count()throws IOException{return ((Number)db.query("SELECT count(*) AS n FROM history").get(0).get("n")).intValue();}
    Map<String,Object> page(long before,String filter,String query)throws IOException {
        String where="1=1";List<Object> args=new ArrayList<>();
        if(filter.equals("install"))where+=" AND action LIKE 'install%'";
        else if(!filter.equals("all")){where+=" AND category=?";args.add(filter);}
        if(!query.isEmpty()){where+=" AND instr(searchText,?)>0";args.add(query.toLowerCase(Locale.ROOT));}
        int matched=((Number)db.query("SELECT count(*) AS n FROM history WHERE "+where,args.toArray()).get(0).get("n")).intValue();
        if(before>0){where+=" AND id<?";args.add(before);}
        List<Map<String,Object>> rows=db.query("SELECT "+SUMMARY+" FROM history WHERE "+where+" ORDER BY id DESC LIMIT 51",args.toArray());
        List<Map<String,Object>> out=new ArrayList<>();int bytes=0;
        for(Map<String,Object> row:rows) {
            if(out.size()==50)break;
            Map<String,Object> item=decode(row,true);
            int size=Json.stringify(item).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1;
            if(bytes+size>40000)break;
            out.add(item);bytes+=size;
        }
        return Json.map("entries",out,"count",count(),"matchedCount",matched,"hasMore",rows.size()>out.size(),"filter",filter,"query",query);
    }
    Map<String,Object> detail(long id)throws IOException {
        List<Map<String,Object>> rows=db.query("SELECT "+FIELDS+" FROM history WHERE id=?",id);
        if(rows.isEmpty())throw new IOException("该记录已清空或超出保留上限，请返回最新记录");
        return decode(rows.get(0),false);
    }
    private static Map<String,Object> decode(Map<String,Object> row,boolean summary) {
        Map<String,Object> item=new LinkedHashMap<>();
        for(String key:new String[]{"id","at","peer","kind","command","category","action","adbCommand","version"})item.put(key,row.get(key));
        if(summary) {
            for(String key:new String[]{"command","adbCommand"})item.put(key,AdbHistory.preview((String)item.get(key)));
        } else item.put("signature",row.get("signature"));
        item.put("partial",summary);
        if(row.get("responseState")!=null) {
            Map<String,Object> response=Json.map("state",row.get("responseState"),"exitCode",row.get("exitCode"),"protocolStatus",row.get("protocolStatus"),
                "truncated",((Number)row.get("truncated")).intValue()!=0,"binary",((Number)row.get("binary")).intValue()!=0,"limit",AdbResponse.OUTPUT_LIMIT);
            if(!summary){response.put("stdout",row.get("stdout"));response.put("stderr",row.get("stderr"));response.put("note",row.get("note"));}
            item.put("response",response);
        }
        return item;
    }
    public void close()throws IOException{db.close();}
}
