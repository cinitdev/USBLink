package io.usblink.mobile;
import java.io.IOException;
import java.util.Map;

public interface MeshNetwork {
    Map<String,Object> status() throws IOException;
    String create() throws IOException;
    void join(String code) throws IOException;
    String pairingCode() throws IOException;
    void leave() throws IOException;
    void setRelay(String relay) throws IOException;
    void tick() throws IOException;
    void stop();
    String localIp();
    String relay();
    byte[] presenceKey() throws IOException;
}
