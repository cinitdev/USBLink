import test from "node:test";
import assert from "node:assert/strict";
import { isPhoneDevice } from "./device-categories.mjs";

test("classifies phones from reported names or descriptions without using vendor IDs alone", () => {
  for (const name of ["Redmi K40", "Pixel 9 Pro", "Apple iPhone", "Samsung Galaxy S24", "SM-S9280", "HUAWEI P60", "Android 调试设备"]) {
    assert.equal(isPhoneDevice({ name }), true, name);
  }
  assert.equal(isPhoneDevice({ name: "M2012K11AC", detail: "Android 设备 · 18d1:4ee7" }), true);
  for (const name of ["Realtek Bluetooth Adapter", "USB 输入设备", "icspring camera", "测试串口", "USB Microphone", "Headphones", "Xiaomi Camera", "Pixel Buds", "Redmi Pad", "Galaxy Tab", "iPad", "USB Device"]) {
    assert.equal(isPhoneDevice({ name, vidPid: "18d1:4ee7" }), false, name);
  }
  for (const name of ["USB Microphone", "USB Headphones", "USB Earphones", "Xiaomi Camera", "测试串口"]) {
    assert.equal(isPhoneDevice({ name, detail: "Android 设备" }), false, name);
  }
  assert.equal(isPhoneDevice({}), false);
});
