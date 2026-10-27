package io.usblink.mobile;

import java.util.regex.*;

/** Process name and system executable must be verified by the caller first. */
final class AdbdIdentity {
    static AdbRoot.ProcessState parse(String pid,String status,String stat,String context) {
        Matcher uid=Pattern.compile("(?m)^Uid:\\s+(0|2000)\\s+\\1\\s+\\1\\s+\\1\\s*$").matcher(status);
        if(!pid.matches("[0-9]+") || !uid.find())return null;
        int value=Integer.parseInt(uid.group(1));
        if(!context.equals("u:r:adbd:s0") && !(value==0 && context.equals("u:r:su:s0")))return null;
        int end=stat.lastIndexOf(") ");if(end<0)return null;
        String[] fields=stat.substring(end+2).trim().split("\\s+");
        if(fields.length<20 || fields[0].equals("Z") || !fields[19].matches("[0-9]+"))return null;
        return new AdbRoot.ProcessState(pid+":"+fields[19],value);
    }
}
