package io.usblink.mobile;

import java.util.*;

/** Labels request metadata only; never evaluates shell syntax or reads command output. */
final class AdbRequestType {
    final String category, action;
    AdbRequestType(String category,String action){this.category=category;this.action=action;}
    static AdbRequestType of(String kind,String request) {
        if(kind.equals("unparsed"))return type("other","unparsed");
        if(kind.equals("push"))return type("file","push");
        if(kind.equals("pull"))return type("file","pull");
        if(kind.equals("file"))return type("file","file-query");
        if(kind.equals("sync"))return type("file","sync");
        int colon=request.indexOf(':');
        String protocol=colon<0?request:request.substring(0,colon);
        String payload=colon<0?"":request.substring(colon+1);
        String service=protocol.split(",",2)[0];
        if(service.equals("abb") || service.equals("abb_exec")) {
            // ABB uses NUL-delimited arguments. Keep spaces inside an argument intact.
            List<String> args=payload.indexOf('\0')>=0?Arrays.asList(payload.split("\0",-1)):tokens(payload);
            if(!args.isEmpty() && args.get(0).equals("package"))return app(args.size()>1?args.get(1):"");
            return type("app","binder-command");
        }
        if(service.equals("shell") || service.equals("exec"))return shell(payload,0);
        if(Arrays.asList("reboot","root","unroot","remount","disable-verity","enable-verity").contains(service))return type("device",service);
        if(Arrays.asList("tcpip","usb","reverse","forward","localabstract","localfilesystem","localreserved","tcp","vsock","dev").contains(service))return type("network","transport");
        if(service.equals("jdwp") || service.startsWith("track-") || service.equals("track-app"))return type("debug","debug-service");
        if(service.startsWith("bugreport"))return type("debug","bugreport");
        if(service.startsWith("backup") || service.equals("restore"))return type("file",service.equals("restore")?"restore":"backup");
        return type("other","service");
    }
    private static AdbRequestType shell(String payload,int depth) {
        List<String> args=tokens(payload);
        if(args.isEmpty())return type("command","interactive-shell");
        int first=0;
        while(first<args.size() && args.get(first).matches("[A-Za-z_][A-Za-z0-9_]*=.*"))first++;
        if(first>=args.size())return type("command","shell");
        String cmd=args.get(first);cmd=cmd.substring(cmd.lastIndexOf('/')+1);
        if((cmd.equals("sh") || cmd.equals("su")) && depth<2) {
            int index=args.indexOf("-c");
            if(index>first && index+1<args.size())return shell(args.get(index+1),depth+1);
        }
        if(cmd.equals("pm"))return app(args.size()>first+1?args.get(first+1):"");
        if(cmd.equals("cmd") && args.size()>first+1 && args.get(first+1).equals("package"))return app(args.size()>first+2?args.get(first+2):"");
        if(cmd.equals("am") || cmd.equals("monkey"))return type("app","app-control");
        if(cmd.equals("logcat"))return type("debug","logcat");
        if(cmd.startsWith("bugreport"))return type("debug","bugreport");
        if(cmd.equals("screencap") || cmd.equals("screenrecord"))return type("debug",cmd);
        if(Arrays.asList("dumpsys","dumpstate","atrace","perfetto","simpleperf").contains(cmd))return type("debug","diagnostic");
        if(Arrays.asList("getprop","setprop","settings","reboot","svc","input","wm").contains(cmd))return type("device","system-command");
        if(Arrays.asList("ls","stat","find","mkdir","rmdir","rm","mv","cp","cat","touch","chmod","chown","dd","du","df").contains(cmd))return type("file","file-command");
        if(Arrays.asList("ip","ifconfig","netstat","ss","ping","curl","wget").contains(cmd))return type("network","network-command");
        return type("command","shell");
    }
    private static AdbRequestType app(String operation) {
        if(operation.equals("install") || operation.startsWith("install-")) {
            Set<String> stages=new HashSet<>(Arrays.asList("install","install-create","install-write","install-commit","install-abandon","install-add-session","install-incremental","install-streaming","install-existing"));
            return type("app",stages.contains(operation)?operation:"install-other");
        }
        if(operation.equals("uninstall") || operation.startsWith("uninstall-"))return type("app","uninstall");
        if(operation.equals("clear"))return type("app","app-clear");
        if(operation.equals("grant") || operation.equals("revoke"))return type("app","app-permission");
        return type("app","package-command");
    }
    private static AdbRequestType type(String category,String action){return new AdbRequestType(category,action);}
    // Render ABB argument boundaries before redaction/storage; never execute this text.
    static String printable(String request) {
        if(!(request.startsWith("abb:") || request.startsWith("abb_exec:")) || request.indexOf('\0')<0)return request;
        int colon=request.indexOf(':');String payload=request.substring(colon+1);
        while(payload.endsWith("\0"))payload=payload.substring(0,payload.length()-1);
        List<String> args=new ArrayList<>();
        for(String arg:payload.split("\0",-1))args.add(quote(arg));
        return request.substring(0,colon+1)+String.join(" ",args);
    }
    private static String quote(String value){return value.matches("[A-Za-z0-9_./:=,+@%-]+")?value:"'"+value.replace("'","'\"'\"'")+"'";}
    static String adbCommand(String kind,String request) {
        if(kind.equals("push") || kind.equals("pull")) {
            int space=request.indexOf(' ');String path=quote(space<0?"":request.substring(space+1));
            return kind.equals("push")?"adb push <电脑端源路径未知> "+path:"adb pull "+path+" <电脑端目标路径未知>";
        }
        int colon=request.indexOf(':');String protocol=colon<0?request:request.substring(0,colon);
        String payload=colon<0?"":request.substring(colon+1),service=protocol.split(",",2)[0];
        if(service.equals("shell"))return "adb shell"+(payload.isEmpty()?"":" "+payload);
        if(service.equals("exec"))return "adb exec-out "+payload;
        if(service.equals("abb") || service.equals("abb_exec"))return "adb shell cmd "+payload;
        if(Arrays.asList("reboot","root","unroot","remount","disable-verity","enable-verity","tcpip","usb").contains(service))return "adb "+service+(payload.isEmpty()?"":" "+payload);
        return request;
    }
    // Bounded lexical inspection. Quoted text is one argument, never a command to execute.
    private static List<String> tokens(String value) {
        List<String> result=new ArrayList<>();StringBuilder word=new StringBuilder();char quote=0;boolean escaped=false;
        for(int i=0;i<value.length() && result.size()<64;i++) {
            char c=value.charAt(i);
            if(escaped){word.append(c);escaped=false;continue;}
            if(c=='\\' && quote!='\''){escaped=true;continue;}
            if(quote!=0){if(c==quote)quote=0;else word.append(c);continue;}
            if(c=='\'' || c=='"'){quote=c;continue;}
            if(";|&<>\n\r".indexOf(c)>=0)break;
            if(Character.isWhitespace(c)){if(word.length()>0){result.add(word.toString());word.setLength(0);}}
            else word.append(c);
        }
        if(word.length()>0 && result.size()<64)result.add(word.toString());
        return result;
    }
}
