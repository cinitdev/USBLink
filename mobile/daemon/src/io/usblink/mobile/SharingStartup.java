package io.usblink.mobile;

import java.io.IOException;

/** Read-only, nonblocking gate before the first adbd takeover in this daemon. */
final class SharingStartup {
    interface SystemState {
        boolean bootCompleted() throws IOException;
        String firewallSnapshot() throws IOException;
        long elapsed();
    }
    private final SystemState system;
    private long bootReadyAt=-1,stableAt;
    private String previous;
    private boolean ready;
    SharingStartup(SystemState system){this.system=system;}
    String pending() throws IOException {
        if(ready)return null;
        long now=system.elapsed();
        if(!system.bootCompleted()) {
            bootReadyAt=-1;previous=null;
            return "共享开关已保留，等待 Android 完成启动";
        }
        if(bootReadyAt<0)bootReadyAt=now;
        try {
            String snapshot=system.firewallSnapshot();
            if(!snapshot.equals(previous)){previous=snapshot;stableAt=now;}
            // netd and vendor networking can rewrite chains after boot_completed.
            if(now-bootReadyAt>=10_000 && now-stableAt>=6_000){ready=true;return null;}
        }catch(IOException unavailable){previous=null;}
        if(now-bootReadyAt>=60_000)throw new IOException("系统防火墙持续变动或不可访问，尚未开启共享；请检查网络防火墙模块后重新开启");
        return "共享开关已保留，正在等待系统网络防护就绪";
    }
}
