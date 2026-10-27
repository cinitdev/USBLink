package io.usblink.mobile;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

/** Serialized state: preference, system adbd readiness and mesh listener are distinct. */
public final class Controller implements Closeable {
    private final Platform platform;
    private final MeshNetwork mesh;
    private final SharingStore store;
    private final SharingTransport proxy;
    private final int listenPort;
    private final PresenceServer presence;
    private String presenceProblem;
    private boolean enabled,recovered,startAttempted,blocked,closed,offClean;
    private int connectPort;
    private String state="off",problem,storeProblem;
    public Controller(Platform platform,MeshNetwork mesh,SharingStore store,SharingTransport proxy) {this(platform,mesh,store,proxy,proxy.usb()?3240:3242);}
    Controller(Platform platform,MeshNetwork mesh,SharingStore store,SharingTransport proxy,int listenPort) {
        this.platform=platform;this.mesh=mesh;this.store=store;this.proxy=proxy;this.listenPort=listenPort;
        presence=new PresenceServer(listenPort==0?0:3241);
        try{enabled=store.load();}catch(IOException e){storeProblem=e.getMessage();}
    }
    public synchronized void tick() {
        if(closed)return;
        try{
            if(enabled && recovered && !blocked){mesh.setEnabled(true);mesh.tick();}
            reconcile();
        }catch(Exception e){if(!blocked)fail("组网检查失败，共享已暂停，请检查网络状态");}
        finally{updatePresence();}
    }
    private void updatePresence() {
        try {
            if(closed || !enabled){presence.stop();presenceProblem=null;return;}
            String ip=mesh.localIp();byte[] key=mesh.presenceKey();
            if(closed || ip==null || ip.isEmpty() || key==null){presence.stop();presenceProblem=null;return;}
            int phase=state.equals("error")?PresenceServer.FAILED:!recovered?PresenceServer.PREPARING:
                !enabled?PresenceServer.OFF:proxy.active("adb")?PresenceServer.READY:PresenceServer.PREPARING;
            presence.configure(ip,key,phase+(proxy.usb()?4:0));
            presence.export(proxy.export());presenceProblem=null;
        }catch(IOException e){presence.stop();presenceProblem=e.getMessage();}
    }
    private void block(String message){
        presence.phase(PresenceServer.FAILED+(proxy.usb()?4:0));
        proxy.stopAll("sharing_error");connectPort=0;state="error";problem=message;blocked=true;
    }
    private void fail(String message){
        block(message);
        try{platform.stopTcp();}catch(IOException cleanup){problem=message+"；"+cleanup.getMessage();}
    }
    private void stopSharingAndNetwork()throws IOException {
        presence.stop();proxy.stopAll();connectPort=0;
        IOException failure=null;
        try{platform.stopTcp();}catch(IOException e){failure=e;}
        // Network shutdown is required even if restoring adbd fails; retain its protection journal.
        try{mesh.setEnabled(false);}catch(IOException e){failure=new IOException(failure==null?e.getMessage():failure.getMessage()+"；"+e.getMessage());}
        if(failure!=null)throw failure;
        startAttempted=false;offClean=true;
    }
    private void reconcile() {
        if(blocked && !recovered)return;
        boolean restoring=false;
        try {
            if(!recovered){restoring=true;platform.recoverTcp();recovered=true;restoring=false;}
            platform.tickAdbRoot(this::revokeForRoot);
            if(blocked)return;
            if(!enabled) {
                restoring=true;if(!offClean)stopSharingAndNetwork();
                state=storeProblem==null?"off":"error";problem=storeProblem;return;
            }
            if(storeProblem!=null){fail(storeProblem);return;}
            mesh.setEnabled(true);
            if(platform.adbRootChanging()){state="starting";problem="正在切换 adbd 权限，请等待确认后在电脑手动连接";return;}
            if(!platform.adbRootAllowsSharing()){proxy.stopAll("adbd_unavailable");connectPort=0;state="error";problem="尚未确认 adbd 已退出 Root，共享已暂停；请检查 ADB Root 设置";return;}
            if(startAttempted)connectPort=platform.maintainTcpPort();
            String ip=mesh.localIp();
            // A network outage revokes the proxy but does not repeatedly restart system adbd.
            if(ip==null || ip.isEmpty()){proxy.stopAll(mesh.unavailableReason());state="waiting_network";problem="等待设备网络就绪";return;}
            if(!startAttempted){
                String preparation=platform.sharingStartupProblem();
                if(preparation!=null){state="waiting_system";problem=preparation;return;}
                startAttempted=true;platform.startTcp();connectPort=platform.tcpPort();
            }
            if(connectPort==0){proxy.stopAll("adbd_unavailable");state="starting";problem="正在等待系统调试服务监听";return;}
            proxy.configure("adb",ip,listenPort,connectPort);state="sharing";problem=null;
        }catch(IOException e){if(restoring)block(e.getMessage());else fail(e.getMessage());}
    }
    private void changeSharing(boolean next)throws IOException {
        if(next && blocked)throw new IOException("上次共享未完成，请先关闭共享，确认恢复后再开启");
        offClean=false;
        if(!next){enabled=false;blocked=false;presence.stop();proxy.stopAll();connectPort=0;}
        try{store.save(next);storeProblem=null;enabled=next;}
        catch(IOException e){enabled=false;proxy.stopAll();storeProblem="共享已撤销，但开关保存失败；重启后可能恢复，请检查模块数据目录";reconcile();throw new IOException(storeProblem);}
        reconcile();if(state.equals("error"))throw new IOException(problem);
    }
    public synchronized Map<String,Object> handle(String action,Map<String,Object> input)throws IOException {
        if(action.equals("set-sharing"))presence.phase(PresenceServer.PREPARING+(proxy.usb()?4:0));
        if(action.equals("create-mesh") || action.equals("join-mesh") || action.equals("leave-mesh") || action.equals("set-relay"))presence.stop();
        Map<String,Object> result;
        try { result=perform(action,input); }
        finally { if(!readOnly(action))updatePresence(); }
        return action.equals("pairing-code") || action.equals("history")?result:status();
    }
    static boolean readOnly(String action){return action.equals("status") || action.equals("pairing-code") || action.equals("history");}
    private void revokeForRoot(){presence.phase(PresenceServer.PREPARING+(proxy.usb()?4:0));proxy.stopAll("adb_root_changed");connectPort=0;}
    private Map<String,Object> perform(String action,Map<String,Object> input)throws IOException {
        switch(action) {
            case "status":return status();
            case "set-adb-root":
                if(!(input.get("enabled") instanceof Boolean))throw new IllegalArgumentException("ADB Root 开关值无效");
                try{platform.setAdbRoot((Boolean)input.get("enabled"),this::revokeForRoot);}
                finally{reconcile();}
                return status();
            case "history":
                if(proxy.history()==null)throw new IOException("当前模块不支持历史记录");
                return proxy.history().page(input);
            case "clear-history":
                if(proxy.history()==null)throw new IOException("当前模块不支持历史记录");
                proxy.history().clear();return status();
            case "set-sharing":
                if(!(input.get("enabled") instanceof Boolean))throw new IllegalArgumentException("共享开关值无效");
                changeSharing((Boolean)input.get("enabled"));return status();
            case "create-mesh":proxy.stopAll();mesh.create();reconcile();return status();
            case "join-mesh":proxy.stopAll();mesh.join(Json.string(input,"code"));reconcile();return status();
            case "leave-mesh":
                IOException failure=null;
                try{changeSharing(false);}catch(IOException e){failure=e;}
                try{mesh.leave();}catch(IOException e){if(failure==null)failure=e;}
                if(failure!=null)throw failure;return status();
            case "pairing-code":return Json.map("code",mesh.pairingCode());
            case "open-debug-settings":platform.openDebugSettings();return status();
            case "set-relay":proxy.stopAll();mesh.setRelay(Json.string(input,"relay"));reconcile();return status();
            case "disconnect":proxy.disconnect(Json.string(input,"id"));return status();
            default:throw new IllegalArgumentException("不支持的操作");
        }
    }
    public synchronized Map<String,Object> status()throws IOException {
        boolean active=enabled && proxy.active("adb");
        return Json.map("version",proxy.usb()?"0.3.12-experimental":"0.2.8","device",platform.device(),"adbRoot",platform.adbRootStatus(),
            "sharing",Json.map("enabled",enabled,"active",active,"state",state,"problem",problem),
            "debug",Json.map("mode",proxy.usb()?"usb-adb-experimental":"system-tcp","enabled",connectPort>0,"connectAddress",active?mesh.localIp()+":"+proxy.boundPort("adb"):null),
            "appSession",Json.map("ready",presence.active(),"problem",presenceProblem),
            "mesh",mesh.status(),"sessions",proxy.snapshot(),"lastDisconnect",proxy.lastDisconnect(),"history",proxy.history()==null?null:proxy.history().page(0),"settings",Json.map("relay",mesh.relay()),"features",Json.map("externalUsb",false));
    }
    synchronized int observationDelaySeconds(){return !enabled && offClean && state.equals("off") && !platform.adbRootChanging()?5:2;}
    @Override public synchronized void close() {
        if(closed)return;closed=true;presence.close();proxy.close();
        try{platform.restoreAdbRoot();}catch(IOException e){problem=e.getMessage();}
        try{platform.stopTcp();}catch(IOException e){problem=e.getMessage();}finally{mesh.stop();}
        // The supervisor separately verifies recovery after this process has exited.
    }
}
