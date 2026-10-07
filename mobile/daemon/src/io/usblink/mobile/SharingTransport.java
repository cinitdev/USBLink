package io.usblink.mobile;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Lifecycle owned by the serialized controller; implementations never initiate imports. */
public interface SharingTransport extends Closeable {
    void configure(String kind,String ip,int port,int target) throws IOException;
    boolean active(String kind);
    int boundPort(String kind);
    void stopAll();
    void disconnect(String id) throws IOException;
    List<Map<String,Object>> snapshot();
    void close();
    default boolean usb(){return false;}
    default Map<String,Object> export(){return null;}
}
