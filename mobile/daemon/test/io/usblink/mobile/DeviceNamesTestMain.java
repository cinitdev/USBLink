package io.usblink.mobile;

import java.util.Map;

public final class DeviceNamesTestMain {
    private static int checks;
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        DeviceNames redmi=new DeviceNames(Map.of("ro.product.marketname","Redmi K40"),"我的手机","M2012K11AC");
        check(redmi.model.equals("Redmi K40") && redmi.modelCode.equals("M2012K11AC"),"ROM market name preserves separate hardware model");
        check(redmi.deviceName.equals("我的手机"),"Mesh uses configured device name");
        for(String key:DeviceNames.MARKET_PROPERTIES) {
            DeviceNames phone=new DeviceNames(Map.of(key,"Other Brand Model"),"null","ABC123");
            check(phone.model.equals("Other Brand Model"),"Vendor supplied name: "+key);
        }
        check(new DeviceNames(Map.of(),"Galaxy S24 Ultra","SM-S9280").model.equals("Galaxy S24 Ultra"),"System device name can replace a model code");
        check(new DeviceNames(Map.of(),"null","Pixel 9 Pro").model.equals("Pixel 9 Pro"),"Build model fallback without a name table");
        check(new DeviceNames(Map.of(),"Android","ABC123").model.equals("ABC123"),"Unknown brands keep truthful model fallback");
        check(new DeviceNames(Map.of("ro.product.marketname","unknown"),"null","unknown").model.equals("Android 手机"),"No invented name");
        for(String invalid:new String[]{"bad\nname","bad\u202ename","x".repeat(65),"null","unknown"})check(DeviceNames.clean(invalid).isEmpty(),"Reject invalid display name");
        MeshProfile profile=MeshProfile.create();
        String toml=MeshManager.toml(profile,"A \"Phone\" \\ Test");
        check(toml.contains("hostname = \"A \\\"Phone\\\" \\\\ Test\""),"Device name cannot inject TOML");
        check(!MeshManager.toml(profile,"bad\nname").contains("bad"),"Control characters cannot enter config");
        System.out.println("PASS: "+checks+" device name checks");
    }
}
