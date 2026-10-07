package io.usblink.mobile;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/** Uses names supplied by this phone's ROM, never a model-code/VID lookup table. */
final class DeviceNames {
    static final String[] MARKET_PROPERTIES={
        "ro.product.marketname","ro.product.vendor.marketname","ro.product.odm.marketname",
        "ro.product.system.marketname","ro.product.product.marketname","ro.config.marketing_name",
        "ro.vendor.oplus.market.name","ro.vivo.market.name","ro.product.market_name"
    };
    final String model,deviceName,modelCode;
    DeviceNames(Map<String,String> properties,String configuredName,String buildModel) {
        modelCode=clean(buildModel);
        String market="";
        for(String key:MARKET_PROPERTIES){market=clean(properties.get(key));if(!market.isEmpty())break;}
        String personal=clean(configuredName);
        model=!market.isEmpty()?market:!personal.isEmpty()?personal:!modelCode.isEmpty()?modelCode:"Android 手机";
        deviceName=personal.isEmpty()?model:personal;
    }
    static String clean(String raw) {
        if(raw==null)return "";
        String value=Normalizer.normalize(raw.trim(),Normalizer.Form.NFC);
        if(value.isEmpty() || value.codePointCount(0,value.length())>64 || value.codePoints().anyMatch(c->Character.isISOControl(c) || Character.getType(c)==Character.FORMAT))return "";
        switch(value.toLowerCase(Locale.ROOT)) {
            case "null":case "unknown":case "android":case "phone":case "手机":case "default":return "";
            default:return value;
        }
    }
}
