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
    default void tickAdbRoot(Runnable revoke){}
    default void setAdbRoot(boolean enabled,Runnable revoke)throws IOException{throw new IOException("当前模块不支持 ADB Root 控制");}
    default Map<String,Object> adbRootStatus(){return null;}
    default boolean adbRootChanging(){return false;}
    default boolean adbRootAllowsSharing(){return true;}
    default void restoreAdbRoot()throws IOException{}
}
