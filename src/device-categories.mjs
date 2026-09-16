// Use the existing USB description only; unknown devices stay in Other.
export function isPhoneDevice(device) {
  const name = device.name || "";
  // Brands also sell peripherals; "phone" inside "microphone" is not a phone.
  if (/(?:head|ear)phone|microphone|headset|\b(?:ear)?buds?\b|camera|webcam|keyboard|mouse|adapter|\bhub\b|serial|modem|storage|\bdisk\b|\bpad\b|ipad|tablet|watch|耳机|麦克风|摄像|键盘|鼠标|适配器|串口|串行|存储|平板|手表|输入设备/i.test(name)) return false;
  return /\b(?:iphone|smartphone|phone|android|redmi|poco|pixel|nexus|oneplus|realme|oppo|vivo|iqoo|honor|huawei|xiaomi|xperia)\b|\bgalaxy\s+(?:[samf]\d|note|z\s*(?:fold|flip))|\bsm-[asnmfg]\d|手机|红米|小米\s*\d|华为|荣耀/i.test(name)
    || /Android 设备|Android 调试设备|智能手机|移动电话/i.test(device.detail || "");
}
