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
    private boolean enabled,recovered,startAttempted,blocked,closed;
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
        try{mesh.tick();reconcile();}catch(Exception e){if(!blocked)fail("组网检查失败，共享已暂停，请检查网络状态");}
        finally{updatePresence();}
    }
    private void updatePresence() {
        try {
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
        proxy.stopAll();connectPort=0;state="error";problem=message;blocked=true;
    }
    private void fail(String message){
        block(message);
        try{platform.stopTcp();}catch(IOException cleanup){problem=message+"；"+cleanup.getMessage();}
    }
    private void reconcile() {
        if(blocked)return;
        boolean restoring=false;
        try {
            if(!recovered){restoring=true;platform.recoverTcp();recovered=true;restoring=false;}
            if(!enabled) {
                proxy.stopAll();restoring=true;platform.stopTcp();startAttempted=false;blocked=false;connectPort=0;
                state=storeProblem==null?"off":"error";problem=storeProblem;return;
            }
            if(storeProblem!=null){fail(storeProblem);return;}
            if(startAttempted)connectPort=platform.maintainTcpPort();
            String ip=mesh.localIp();
            // A network outage revokes the proxy but does not repeatedly restart system adbd.
            if(ip==null || ip.isEmpty()){proxy.stopAll();state="waiting_network";problem="等待设备网络就绪";return;}
            if(!startAttempted){
                String preparation=platform.sharingStartupProblem();
                if(preparation!=null){state="waiting_system";problem=preparation;return;}
                startAttempted=true;platform.startTcp();connectPort=platform.tcpPort();
            }
            if(connectPort==0){proxy.stopAll();state="starting";problem="正在等待系统调试服务监听";return;}
            proxy.configure("adb",ip,listenPort,connectPort);state="sharing";problem=null;
        }catch(IOException e){if(restoring)block(e.getMessage());else fail(e.getMessage());}
    }
    private void changeSharing(boolean next)throws IOException {
        if(next && blocked)throw new IOException("上次共享未完成，请先关闭共享，确认恢复后再开启");
        if(!next){enabled=false;blocked=false;proxy.stopAll();connectPort=0;}
        try{store.save(next);storeProblem=null;enabled=next;}
        catch(IOException e){enabled=false;proxy.stopAll();storeProblem="共享已撤销，但开关保存失败；重启后可能恢复，请检查模块数据目录";reconcile();throw new IOException(storeProblem);}
        reconcile();if(state.equals("error"))throw new IOException(problem);
    }
    public synchronized Map<String,Object> handle(String action,Map<String,Object> input)throws IOException {
        if(action.equals("set-sharing"))presence.phase(PresenceServer.PREPARING+(proxy.usb()?4:0));
        if(action.equals("create-mesh") || action.equals("join-mesh") || action.equals("leave-mesh") || action.equals("set-relay"))presence.stop();
        Map<String,Object> result;
        try { result=perform(action,input); }
        finally { if(!action.equals("status") && !action.equals("pairing-code"))updatePresence(); }
        return action.equals("pairing-code")?result:status();
    }
    private Map<String,Object> perform(String action,Map<String,Object> input)throws IOException {
        switch(action) {
            case "status":return status();
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
        return Json.map("version",proxy.usb()?"0.3.3-experimental":"0.2.2","device",platform.device(),
            "sharing",Json.map("enabled",enabled,"active",active,"state",state,"problem",problem),
            "debug",Json.map("mode",proxy.usb()?"usb-adb-experimental":"system-tcp","enabled",connectPort>0,"connectAddress",active?mesh.localIp()+":"+proxy.boundPort("adb"):null),
            "appSession",Json.map("ready",presence.active(),"problem",presenceProblem),
            "mesh",mesh.status(),"sessions",proxy.snapshot(),"settings",Json.map("relay",mesh.relay()),"features",Json.map("externalUsb",false));
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;presence.close();proxy.close();
        try{platform.stopTcp();}catch(IOException e){problem=e.getMessage();}finally{mesh.stop();}
        // The supervisor separately verifies recovery after this process has exited.
    }
}
