package io.usblink.mobile;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Host-only adapter exercising the same SQL/schema against real SQLite. Never packaged. */
final class JdbcHistoryDatabase implements HistoryDatabase {
    private final Connection connection;
    JdbcHistoryDatabase(Path path)throws IOException {
        try{connection=DriverManager.getConnection("jdbc:sqlite:"+path);exec("PRAGMA journal_mode=WAL");}
        catch(SQLException e){throw new IOException(e);}
    }
    private PreparedStatement statement(String sql,Object[] args)throws SQLException {
        PreparedStatement statement=connection.prepareStatement(sql);
        for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;
    }
    public void exec(String sql,Object... args)throws IOException {
        try(PreparedStatement statement=statement(sql,args)){statement.execute();}catch(SQLException e){throw new IOException(e);}
    }
    public List<Map<String,Object>> query(String sql,Object... args)throws IOException {
        try(PreparedStatement statement=statement(sql,args);ResultSet result=statement.executeQuery()) {
            List<Map<String,Object>> rows=new ArrayList<>();ResultSetMetaData meta=result.getMetaData();
            while(result.next()) {
                Map<String,Object> row=new LinkedHashMap<>();
                for(int i=1;i<=meta.getColumnCount();i++){
                    Object value=result.getObject(i);row.put(meta.getColumnLabel(i),value instanceof Number?((Number)value).longValue():value);
                }
                rows.add(row);
            }
            return rows;
        }catch(SQLException e){throw new IOException(e);}
    }
    public void begin()throws IOException{exec("BEGIN IMMEDIATE");}
    public void end(boolean commit)throws IOException{exec(commit?"COMMIT":"ROLLBACK");}
    public void close()throws IOException{try{connection.close();}catch(SQLException e){throw new IOException(e);}}
    static Map<String,Object> fullPage(AdbHistory history,long before) {
        try {
        Map<String,Object> page=history.page(before);List<Map<String,Object>> full=new ArrayList<>();
        for(Object item:(List<?>)page.get("entries"))full.add(Json.object(((List<?>)history.page(Json.map("epoch",page.get("epoch"),"id",Json.object(item).get("id"))).get("entries")).get(0)));
        page.put("entries",full);return page;
        }catch(IOException e){throw new AssertionError(e);}
    }
}
