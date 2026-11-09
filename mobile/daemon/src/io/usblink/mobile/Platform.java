package io.usblink.mobile;
import java.io.IOException;
import java.util.Map;

public interface Platform {
    Map<String,Object> device();
    void recoverTcp() throws IOException;
    void startTcp() throws IOException;
    int tcpPort() throws IOException;
    default String sharingStartupProblem() throws IOException{return null;}
    default int maintainTcpPort() throws IOException{return tcpPort();}
    void stopTcp() throws IOException;
    void openDebugSettings() throws IOException;
    default void requireUsbDebugging() throws IOException { throw new IOException("无法确认 USB 调试状态，未开启共享"); }
}
