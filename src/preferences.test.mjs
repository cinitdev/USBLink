import test from "node:test";
import assert from "node:assert/strict";
import { readBoolean, readStored, writeStored, removeStored, normalizeRelay } from "./preferences.mjs";

test("malformed booleans fall back to safe defaults", () => {
  globalThis.localStorage = { getItem: () => "{broken" };
  assert.equal(readBoolean("refresh", true), true);
  assert.equal(readBoolean("start", false), false);
});
test("unavailable storage cannot throw into React or USB queries", () => {
  globalThis.localStorage = {
    getItem() { throw new Error("denied"); },
    setItem() { throw new Error("full"); },
    removeItem() { throw new Error("denied"); },
  };
  assert.equal(readStored("names", "{}"), "{}");
  assert.doesNotThrow(() => writeStored("names", "{}"));
  assert.doesNotThrow(() => removeStored("connection"));
});
test("relay validation rejects invalid ports and the retired server", () => {
  assert.equal(normalizeRelay(" tcp://example.com:65535/ "), "tcp://example.com:65535/");
  for (const relay of ["tcp://host:0", "tcp://host:65536", "tcp://host:99999", "http://host:80", "tcp://public.easytier.top:11010/"]) {
    assert.throws(() => normalizeRelay(relay));
  }
});
