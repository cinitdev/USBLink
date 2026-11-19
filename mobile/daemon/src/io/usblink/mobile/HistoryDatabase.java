package io.usblink.mobile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Small SQL boundary: Android uses the platform SQLite, host checks use SQLite JDBC. */
interface HistoryDatabase extends AutoCloseable {
    interface Factory { HistoryDatabase open(Path path) throws IOException; }
    void exec(String sql,Object... args) throws IOException;
    List<Map<String,Object>> query(String sql,Object... args) throws IOException;
    void begin() throws IOException;
    void end(boolean commit) throws IOException;
    void close() throws IOException;
}
