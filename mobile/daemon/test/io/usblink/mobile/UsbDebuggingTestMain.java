package io.usblink.mobile;

import java.io.IOException;
import java.nio.file.Files;

/** Manual enables must fail before changing the persisted intent or network. */
public final class UsbDebuggingTestMain {
    public static void main(String[] args)throws Exception {
        for(Boolean debug:new Boolean[]{false,null}) {
            SharingStore store=new SharingStore(Files.createTempDirectory("usblink-debug-gate-"),3);
            TestMain.FakePlatform phone=new TestMain.FakePlatform();phone.usbDebugging=debug;
            TestMain.FakeMesh mesh=new TestMain.FakeMesh();mesh.ip="127.0.0.1";
            try(Controller c=new Controller(phone,mesh,store,new ProxyPool(),0)) {
                try{c.handle("set-sharing",Json.map("enabled",true));throw new AssertionError("Unsafe enable accepted");}
                catch(IOException expected){}
                if(store.load() || mesh.requested || phone.starts!=0)throw new AssertionError("Rejected enable mutated state");
                c.handle("set-sharing",Json.map("enabled",false));
                phone.usbDebugging=true;c.handle("set-sharing",Json.map("enabled",true));
                if(!store.load() || !mesh.requested || phone.starts!=1)throw new AssertionError("Enabled debugging must permit sharing");
                phone.usbDebugging=debug;c.handle("set-sharing",Json.map("enabled",false));
                if(store.load() || mesh.requested)throw new AssertionError("Off must remain available");
            }
        }
        System.out.println("PASS: USB debugging false/unknown blocks enable without mutation; verified on permits enable; off remains available");
    }
}
