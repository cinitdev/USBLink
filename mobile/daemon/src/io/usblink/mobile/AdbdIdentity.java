package io.usblink.mobile;

import java.util.regex.*;

/** Process name and system executable must be verified by the caller first. */
final class AdbdIdentity {
    static boolean matches(String pid,String status,String stat,String context) {
        Matcher uid=Pattern.compile("(?m)^Uid:\\s+(0|2000)\\s+\\1\\s+\\1\\s+\\1\\s*$").matcher(status);
        if(!pid.matches("[0-9]+") || !uid.find())return false;
        int value=Integer.parseInt(uid.group(1));
        // The caller already verifies init ancestry and executable identity. Root managers
        // and external adbd wrappers use different domains; a manager setting is no evidence.
        if(!context.equals("u:r:adbd:s0") && !(value==0 && context.matches("u:r:(?!untrusted_app|isolated_app)[a-zA-Z0-9_]+:s0")))return false;
        int end=stat.lastIndexOf(") ");if(end<0)return false;
        String[] fields=stat.substring(end+2).trim().split("\\s+");
        return fields.length>=20 && !fields[0].equals("Z") && fields[19].matches("[0-9]+");
    }
}
