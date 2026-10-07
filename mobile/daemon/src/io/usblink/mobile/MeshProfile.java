package io.usblink.mobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/** The USBLINK1 wire format is shared with the existing Windows application. */
final class MeshProfile {
    static final String DEFAULT_RELAY="tcp://183.230.36.171:11010";
    static final String FALLBACK_RELAY="tcp://107.172.5.203:11010";
    final String name, secret, relay;
    MeshProfile(String name,String secret,String relay) throws IOException {
        if(name==null || !name.matches("usblink-[a-f0-9]{12}") || secret==null || !secret.matches("[a-f0-9]{64}"))
            throw new IOException("配对码不是有效的 USBLink 网络");
        this.name=name;this.secret=secret;this.relay=validateRelay(relay);
    }
    static String validateRelay(String relay) throws IOException {
        if(relay==null || relay.length()>260 || !relay.matches("(?:tcp|udp|ws|wss|wg|quic)://[A-Za-z0-9.-]+:[0-9]{1,5}/?"))
            throw new IOException("中继地址格式无效");
        String clean=relay.endsWith("/")?relay.substring(0,relay.length()-1):relay;
        int port=Integer.parseInt(clean.substring(clean.lastIndexOf(':')+1));
        if(port<1 || port>65535)throw new IOException("中继端口超出范围");
        if(clean.substring(clean.indexOf("://")+3,clean.lastIndexOf(':')).equalsIgnoreCase("public.easytier.top"))return DEFAULT_RELAY;
        return clean;
    }
    static MeshProfile create() throws IOException {
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        StringBuilder secret=new StringBuilder();for(byte b:bytes)secret.append(String.format("%02x",b&255));
        return new MeshProfile("usblink-"+secret.substring(0,12),secret.toString(),DEFAULT_RELAY);
    }
    Map<String,Object> json() {return Json.map("version",1,"network_name",name,"network_secret",secret,"relay",relay);}
    String encode() {return "USBLINK1-"+Base64.getUrlEncoder().withoutPadding().encodeToString(Json.stringify(json()).getBytes(StandardCharsets.UTF_8));}
    static MeshProfile decode(String code) throws IOException {
        if(code==null || code.length()>2048 || !code.trim().startsWith("USBLINK1-"))throw new IOException("配对码格式不正确");
        try {return parse(new String(Base64.getUrlDecoder().decode(code.trim().substring(9)),StandardCharsets.UTF_8));}
        catch(IllegalArgumentException e) {throw new IOException("配对码内容不正确");}
    }
    static MeshProfile parse(String text) throws IOException {
        try {
            Map<String,Object> json=Json.object(Json.parse(text));
            if(Json.integer(json,"version")!=1)throw new IOException("配对码版本不兼容");
            return new MeshProfile(Json.string(json,"network_name"),Json.string(json,"network_secret"),Json.string(json,"relay"));
        }catch(IllegalArgumentException e) {throw new IOException("配对码内容不正确");}
    }
}
