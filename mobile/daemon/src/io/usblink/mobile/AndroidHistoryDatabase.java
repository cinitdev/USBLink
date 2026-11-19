package io.usblink.mobile;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.system.Os;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Uses Android's SQLite directly: no sqlite shell process or bundled native library. */
final class AndroidHistoryDatabase implements HistoryDatabase {
    private final SQLiteDatabase db;
    AndroidHistoryDatabase(Path path) throws IOException {
        SQLiteDatabase opened=null;
        try {
            // app_process has no Application. Some ROM defaults (observed on GT5 /
            // Android 16) throw in SQLiteGlobal.getDefaultSyncMode before open returns.
            // Supply WAL and FULL at open time; a later PRAGMA cannot repair that.
            SQLiteDatabase.OpenParams options=new SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY|SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                .setJournalMode("WAL").setSynchronousMode("FULL")
                .setErrorHandler(damaged->{throw new SQLiteException("History database is corrupt; preserve it for recovery");})
                .build();
            opened=SQLiteDatabase.openDatabase(path.toFile(),options);
            Os.chmod(path.toString(),0600);
            db=opened;
        } catch(Exception e) {
            if(opened!=null)try{opened.close();}catch(Exception closeFailure){e.addSuppressed(closeFailure);}
            throw new IOException("历史数据库访问失败，请检查模块数据目录",e);
        }
    }
    private static IOException failure(){return new IOException("历史数据库访问失败，请检查模块数据目录");}
    public void exec(String sql,Object... args)throws IOException {
        try{db.execSQL(sql,args);}catch(SQLiteException e){throw failure();}
    }
    public List<Map<String,Object>> query(String sql,Object... args)throws IOException {
        String[] params=new String[args.length];for(int i=0;i<args.length;i++)params[i]=String.valueOf(args[i]);
        try(Cursor cursor=db.rawQuery(sql,params)) {
            List<Map<String,Object>> rows=new ArrayList<>();
            while(cursor.moveToNext()) {
                Map<String,Object> row=new LinkedHashMap<>();
                for(int i=0;i<cursor.getColumnCount();i++)row.put(cursor.getColumnName(i),cursor.isNull(i)?null:
                    cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER?cursor.getLong(i):cursor.getString(i));
                rows.add(row);
            }
            return rows;
        }catch(SQLiteException e){throw failure();}
    }
    public void begin()throws IOException{try{db.beginTransactionNonExclusive();}catch(SQLiteException e){throw failure();}}
    public void end(boolean commit)throws IOException{try{if(commit)db.setTransactionSuccessful();db.endTransaction();}catch(SQLiteException e){throw failure();}}
    public void close(){db.close();}
}
