import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Add20Regular, ArrowClockwise20Regular, ArrowRight20Regular, ArrowSync20Regular,
  Checkmark16Regular, ChevronDown16Regular, ChevronRight20Regular, Copy20Regular,
  Desktop20Regular, Dismiss16Regular, ErrorCircle20Regular, Key20Regular,
  Link20Regular, LockClosed16Filled, MoreHorizontal20Regular, NetworkCheck20Regular,
  Open20Regular, PlugConnected20Regular, PlugDisconnected20Regular, Power20Regular,
  Settings20Regular, ShieldCheckmark20Filled, Storage20Regular, UsbPlug20Regular,
} from "@fluentui/react-icons";
import { invoke } from "@tauri-apps/api/core";
import { useAttachedDevices } from "./useAttachedDevices.js";
import { applyAttachedState } from "./usb-state.mjs";
import { canUsePeer, mergePeerPresence, mountPresence } from "./peer-state.mjs";
import { readStored, readBoolean, writeStored, removeStored, normalizeRelay } from "./preferences.mjs";

const nativeApp = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
const previewUnpaired = !nativeApp && new URLSearchParams(window.location.search).has("unpaired");
const previewLegacy = !nativeApp && new URLSearchParams(window.location.search).has("legacy");
const defaultRelay = "tcp://183.230.36.171:11010";
const legacyRelay = "tcp://public.easytier.top:11010";
const demoPairingCode = "USBLINK1-eyJ2ZXJzaW9uIjoxLCJuZXR3b3JrX25hbWUiOiJ1c2JsaW5rLWRlbW8iLCJuZXR3b3JrX3NlY3JldCI6ImRlbW8iLCJyZWxheSI6InRjcDovL3B1YmxpYy5lYXN5dGllci50b3A6MTEwMTAifQ";
const demoPeers = [{ name: "OFFICE-PC", ip: "10.126.126.2", online: true, os: "windows", latency: "3.5", tunnel: "udp" }];
const demoMesh = previewUnpaired
  ? { configured: false, running: false, localIp: null, networkName: null, relay: defaultRelay, peerCount: 0, peers: [], pairingCode: null, problem: null, needsRepair: false }
  : previewLegacy
  ? { configured: true, running: false, localIp: null, networkName: "usblink-legacy000001", relay: legacyRelay, peerCount: 0, peers: [], pairingCode: null, problem: "默认公共节点已经失效，请修复连接", needsRepair: true }
  : { configured: true, running: true, localIp: "10.126.126.1", networkName: "usblink-7f2a94c1830b", relay: defaultRelay, peerCount: 1, peers: demoPeers, pairingCode: null, problem: null, needsRepair: false };
const demoEnvironment = {
  computerName: "DESKTOP-7G3K2LQ", meshIp: demoMesh.localIp,
  usbipdInstalled: true, usbipInstalled: true, easytierEmbedded: true,
  usbipSafe: true, usbipProblem: null,
  usbipdVersion: "5.3.0", usbipVersion: "0.9.8.0", easytierVersion: "2.6.4",
  meshConfigured: demoMesh.configured, meshRunning: demoMesh.running,
  meshPeerCount: demoMesh.peerCount, meshRelay: demoMesh.relay,
};
const demoDevices = [
  { busId: "1-4", vidPid: "18d1:4ee7", name: "Pixel 9 Pro", detail: "Android 设备 · USB 3.0", shared: false },
  { busId: "1-7", vidPid: "1a86:7523", name: "CH340 串口", detail: "USB 串行设备 · USB 2.0", shared: false },
  { busId: "2-2", vidPid: "1050:0407", name: "YubiKey 5 NFC", detail: "安全密钥 · USB 2.0", shared: false },
  { busId: "2-5", vidPid: "0781:5581", name: "SanDisk Ultra", detail: "USB 存储设备 · USB 3.1", shared: false },
];

async function backend(command, args = {}, fallback) {
  if (nativeApp) return invoke(command, args);
  await new Promise((resolve) => setTimeout(resolve, 240));
  return typeof fallback === "function" ? fallback() : fallback;
}

async function copyText(value) {
  try { await navigator.clipboard.writeText(value); return; } catch { /* fallback below */ }
  const field = document.createElement("textarea");
  field.value = value; field.style.position = "fixed"; field.style.opacity = "0";
  document.body.appendChild(field); field.select();
  try { if (!document.execCommand("copy")) throw new Error("复制失败，请手动选择配对码复制"); }
  finally { field.remove(); }
}

function StatusDot({ online = true }) { return <span className={`status-dot ${online ? "online" : "offline"}`} />; }
function Check({ checked, onChange, label, disabled = false }) {
  return <button type="button" role="checkbox" disabled={disabled} aria-checked={checked} aria-label={label} className={`check ${checked ? "checked" : ""}`} onClick={onChange}>{checked && <Checkmark16Regular />}</button>;
}
function deviceIcon(device) {
  const value = `${device.name} ${device.detail}`.toLowerCase();
  return value.includes("存储") || value.includes("disk") || value.includes("sandisk") ? Storage20Regular : UsbPlug20Regular;
}

const REMOTE_NAME_CACHE_KEY = "usblink.remoteDeviceNames";

function loadRemoteNameCache() {
  try {
    const saved = JSON.parse(readStored(REMOTE_NAME_CACHE_KEY) || "{}");
    return saved && typeof saved === "object" && !Array.isArray(saved) ? saved : {};
  } catch {
    return {};
  }
}

function stabilizeRemoteNames(host, devices, cache) {
  let changed = false;
  const resolved = devices.map((device) => {
    const key = `${host}|${device.busId}|${device.vidPid}`;
    const remembered = cache[key];
    const name = typeof device.name === "string" ? device.name.trim() : "";
    if (device.friendlyName && name && name !== "Android 调试设备") {
      const detail = device.detail || device.vidPid;
      if (!remembered || remembered.name !== name || remembered.detail !== detail) {
        cache[key] = { name, detail };
        changed = true;
      }
      return device;
    }
    if (remembered && typeof remembered.name === "string" && remembered.name) {
      return { ...device, name: remembered.name, detail: remembered.detail || device.detail, friendlyName: true };
    }
    return device;
  });
  if (changed) {
    const trimmed = Object.fromEntries(Object.entries(cache).slice(-200));
    for (const key of Object.keys(cache)) delete cache[key];
    Object.assign(cache, trimmed);
    writeStored(REMOTE_NAME_CACHE_KEY, JSON.stringify(cache));
  }
  return resolved;
}

export default function App() {
  const initialRouteDone = useRef(!nativeApp);
  const problemRouteDone = useRef(!nativeApp);
  const usbAccessCheckedFor = useRef("");
  const meshServiceCheckedFor = useRef("");
  const remoteNameCache = useRef(loadRemoteNameCache());
  const previewAttached = useRef([]);
  const remoteRequest = useRef(0);
  const remotePending = useRef(null);
  const usbOperation = useRef(false);
  const operation = useRef("");
  const meshRequest = useRef(0);
  const meshPending = useRef(null);
  const localRequest = useRef(0);
  const localPending = useRef(null);
  const environmentPending = useRef(null);
  const relayDirty = useRef(false);
  const meshIdentity = useRef("");
  const toastTimer = useRef(null);
  const preferencePending = useRef(false);
  const [savingPreference, setSavingPreference] = useState(false);
  const [localLoading, setLocalLoading] = useState(false);
  const [localProblem, setLocalProblem] = useState("");
  const [sharingProblem, setSharingProblem] = useState("");
  const [sharingSession, setSharingSession] = useState({ phase: nativeApp ? "pending" : "ready", ready: !nativeApp, problem: null });
  const sharingSessionSnapshot = useRef(sharingSession);
  const sessionRequest = useRef(0);
  const sessionPending = useRef(false);
  const [meshServiceProblem, setMeshServiceProblem] = useState("");
  const [page, setPage] = useState(previewUnpaired || previewLegacy ? "connections" : "devices");
  const [environment, setEnvironment] = useState(() => nativeApp
    ? { computerName: "这台电脑", usbipInstalled: false, usbipdInstalled: false, usbipSafe: false, easytierVersion: "2.6.4", usbipProblem: "正在验证 USB/IP 内核驱动版本…" }
    : demoEnvironment);
  const [mesh, setMesh] = useState(nativeApp ? { configured: false, running: false, peers: [], peerCount: 0, relay: defaultRelay } : demoMesh);
  const [meshReady, setMeshReady] = useState(!nativeApp);
  const [meshServiceReady, setMeshServiceReady] = useState(!nativeApp);
  const [devices, setDevices] = useState(nativeApp ? [] : demoDevices);
  const [peers, setPeers] = useState(nativeApp ? [] : demoMesh.peers);
  const peersSnapshot = useRef(peers);
  const meshSnapshot = useRef(mesh);
  const [selectedPeerIp, setSelectedPeerIp] = useState(nativeApp ? "" : demoMesh.peers[0]?.ip || "");
  const currentPeerIp = useRef(selectedPeerIp);
  currentPeerIp.current = selectedPeerIp;
  const [selected, setSelected] = useState(new Set());
  const [remoteDevices, setRemoteDevices] = useState([]);
  const [remoteProblem, setRemoteProblem] = useState("");
  const [remoteLoading, setRemoteLoading] = useState(false);
  const [remoteSelected, setRemoteSelected] = useState(new Set());
  const [peerMenu, setPeerMenu] = useState(false);
  const [busy, setBusy] = useState("");
  const [toast, setToast] = useState(null);
  const [pairingCode, setPairingCode] = useState("");
  const [joinCode, setJoinCode] = useState("");
  const [relayDraft, setRelayDraft] = useState(() => {
    const saved = readStored("usblink.relay");
    return !saved || saved === legacyRelay ? defaultRelay : saved;
  });
  const [checkingComponents, setCheckingComponents] = useState(false);
  const [installingComponent, setInstallingComponent] = useState("");
  const [prefs, setPrefs] = useState(() => ({
    autoRefresh: readBoolean("usblink.autoRefresh", true),
    autoStart: readBoolean("usblink.autoStart", false),
  }));

  const resolveAttachedNames = useCallback((devices) => devices.map((device) => stabilizeRemoteNames(device.host, [device], remoteNameCache.current)[0]), []);
  const readAttached = useCallback(async () => {
    const next = await backend("list_attached_devices", {}, () => previewAttached.current);
    return resolveAttachedNames(next);
  }, [resolveAttachedNames]);
  const attached = useAttachedDevices(readAttached, environment.usbipInstalled && environment.usbipSafe && sharingSession.ready, prefs.autoRefresh);

  const selectedPeer = useMemo(() => peers.find((peer) => peer.ip === selectedPeerIp) || peers[0], [peers, selectedPeerIp]);
  const remoteRows = useMemo(() => canUsePeer(selectedPeer) ? applyAttachedState(selectedPeerIp, remoteDevices, attached.devices) : [], [selectedPeerIp, selectedPeer, remoteDevices, attached.devices]);
  const notify = useCallback((message, type = "success") => {
    window.clearTimeout(toastTimer.current);
    setToast({ message, type }); toastTimer.current = window.setTimeout(() => setToast(null), 3800);
  }, []);

  const applyMesh = useCallback((next) => {
    const identity = next.configured ? [next.networkName, next.relay].join("|") : "";
    const knownPeers = mergePeerPresence(peersSnapshot.current, next.peers, { ...next, sameNetwork: identity === meshIdentity.current });
    peersSnapshot.current = knownPeers; meshSnapshot.current = next;
    setMesh(next); setPeers(knownPeers);
    const selectedIp = knownPeers.some(peer => peer.ip === currentPeerIp.current) ? currentPeerIp.current : (knownPeers.find(peer => peer.online) || knownPeers[0])?.ip || "";
    if (identity !== meshIdentity.current) {
      setPairingCode(next.pairingCode || "");
      meshServiceCheckedFor.current = ""; usbAccessCheckedFor.current = "";
      setMeshServiceReady(!nativeApp); setMeshServiceProblem(""); setSharingProblem("");
    }
    else if (next.pairingCode) setPairingCode(next.pairingCode);
    if (identity !== meshIdentity.current) {
      remoteRequest.current += 1;
      setRemoteDevices([]); setRemoteSelected(new Set()); setRemoteProblem("");
    }
    if (!next.running || !canUsePeer(knownPeers.find(peer => peer.ip === selectedIp))) {
      remoteRequest.current += 1; remotePending.current = null;
      setRemoteDevices([]); setRemoteSelected(new Set()); setRemoteLoading(false); setRemoteProblem("");
    }
    meshIdentity.current = identity;
    if (next.configured && !relayDirty.current) setRelayDraft(next.relay || defaultRelay);
    setSelectedPeerIp(selectedIp);
  }, []);

  const beginOperation = useCallback((name) => {
    if (operation.current || (!sharingSessionSnapshot.current.ready && name !== "session-cleanup")) return false;
    operation.current = name;
    if (name.startsWith("mesh-") || name === "attach" || name === "detach") meshRequest.current += 1;
    if (name === "attach" || name === "detach") {
      remoteRequest.current += 1; remotePending.current = null; setRemoteLoading(false);
    }
    if (name !== "mesh-check" && name !== "share-check") localRequest.current += 1;
    setBusy(name);
    return true;
  }, []);
  const endOperation = useCallback(() => {
    if (operation.current.startsWith("mesh-") || operation.current === "attach" || operation.current === "detach") meshRequest.current += 1;
    operation.current = "";
    setBusy("");
  }, []);

  const refreshEnvironment = useCallback(async () => {
    if (!nativeApp) return demoEnvironment;
    if (environmentPending.current) return;
    const pending = backend("get_environment_status"); environmentPending.current = pending;
    try { const next = await pending; setEnvironment(next); return next; }
    catch (error) { notify(String(error), "error"); }
    finally { if (environmentPending.current === pending) environmentPending.current = null; }
  }, [notify]);

  const refreshMesh = useCallback(async () => {
    if (!nativeApp || usbOperation.current || operation.current.startsWith("mesh-") || meshPending.current) return;
    const request = ++meshRequest.current;
    const pending = backend("get_mesh_status", { includeCode: false }); meshPending.current = pending;
    try {
      const next = await pending;
      if (request !== meshRequest.current) return;
      applyMesh(next); setMeshReady(true);
      if (!initialRouteDone.current) { initialRouteDone.current = true; if (!next.configured) setPage("connections"); }
      if (!problemRouteDone.current) { problemRouteDone.current = true; if (next.problem) setPage("connections"); }
      return next;
    } catch (error) {
      if (request !== meshRequest.current) return;
      setMeshReady(true);
      applyMesh({ ...meshSnapshot.current, running: false, localIp: null, peers: [], peerCount: 0, problem: String(error), needsRepair: true });
      notify(String(error), "error");
    } finally { if (meshPending.current === pending) meshPending.current = null; }
  }, [applyMesh, notify]);

  const refreshLocal = useCallback(async (force = false) => {
    if (localPending.current && !force) return;
    const request = ++localRequest.current;
    const pending = backend("list_local_devices", {}, null); localPending.current = pending;
    setLocalLoading(true);
    try {
      const next = await pending;
      if (request !== localRequest.current || !next) return;
      setDevices(next); setLocalProblem("");
      setSelected((old) => new Set([...old].filter((id) => next.some((item) => item.busId === id))));
    } catch (error) { if (request === localRequest.current) { setLocalProblem(String(error)); setSelected(new Set()); notify(String(error), "error"); } }
    finally { if (localPending.current === pending) { localPending.current = null; setLocalLoading(false); } }
  }, [notify]);

  const refreshRemote = useCallback(async (showError = false, force = false) => {
    if (usbOperation.current || !mesh.running || !meshServiceReady || operation.current.startsWith("mesh-")) return;
    if (!selectedPeerIp || selectedPeerIp !== currentPeerIp.current) return;
    if (!canUsePeer(peersSnapshot.current.find(peer => peer.ip === selectedPeerIp))) return;
    if (remotePending.current?.host === selectedPeerIp && !force) return;
    const request = ++remoteRequest.current;
    remotePending.current = { host: selectedPeerIp, request };
    setRemoteLoading(true);
    try {
      const fallback = demoDevices.map((item) => ({ ...item, shared: true }));
      const next = await backend("list_remote_devices", { host: selectedPeerIp }, fallback);
      if (request !== remoteRequest.current || selectedPeerIp !== currentPeerIp.current) return;
      const resolved = stabilizeRemoteNames(selectedPeerIp, next, remoteNameCache.current);
      setRemoteDevices(resolved);
      setRemoteProblem("");
      setRemoteSelected((old) => new Set([...old].filter((id) => resolved.some((item) => item.busId === id))));
    } catch (error) {
      if (request !== remoteRequest.current || selectedPeerIp !== currentPeerIp.current) return;
      const message = String(error);
      setRemoteProblem(message);
      if (showError) notify(message, "error");
    } finally {
      if (remotePending.current?.request === request) remotePending.current = null;
      if (request === remoteRequest.current) setRemoteLoading(false);
    }
  }, [notify, selectedPeerIp, mesh.running, meshServiceReady, selectedPeer?.online, selectedPeer?.usbReady]);

  const applySharingSession = useCallback((next) => {
    const wasReady = sharingSessionSnapshot.current.ready;
    sharingSessionSnapshot.current = next; setSharingSession(next);
    if (!next.ready || !wasReady) {
      setSelected(new Set()); setRemoteSelected(new Set());
    }
    if (next.ready && !wasReady) {
      setLocalProblem("正在确认清理后的 USB 状态");
      refreshLocal(true);
    }
  }, [refreshLocal]);
  const refreshSharingSession = useCallback(async () => {
    if (!nativeApp || sessionPending.current || operation.current === "session-cleanup") return;
    sessionPending.current = true;
    const request = ++sessionRequest.current;
    try {
      const next = await backend("get_sharing_session");
      if (request === sessionRequest.current) applySharingSession(next);
    } catch (error) {
      if (request === sessionRequest.current) applySharingSession({ phase: "failed", ready: false, problem: `无法确认 USB 共享清理状态：${String(error)}` });
    } finally { sessionPending.current = false; }
  }, [applySharingSession]);
  const retrySharingCleanup = async () => {
    if (!beginOperation("session-cleanup")) return;
    sessionRequest.current += 1;
    applySharingSession({ phase: "starting", ready: false, problem: null });
    try { applySharingSession(await backend("retry_sharing_cleanup")); }
    catch (error) { applySharingSession({ phase: "failed", ready: false, problem: String(error) }); }
    finally { endOperation(); }
  };
  useEffect(() => {
    refreshSharingSession();
    if (!nativeApp) return;
    const refresh = () => { if (!document.hidden) refreshSharingSession(); };
    const timer = window.setInterval(refresh, 1000);
    window.addEventListener("focus", refresh);
    return () => { window.clearInterval(timer); window.removeEventListener("focus", refresh); };
  }, [refreshSharingSession]);
  useEffect(() => { refreshEnvironment(); refreshMesh(); refreshLocal(); }, []);
  useEffect(() => {
    writeStored("usblink.autoReconnect", "false");
    removeStored("usblink.lastConnection");
  }, []);
  useEffect(() => {
    if (!nativeApp) return;
    const refresh = () => { refreshEnvironment(); refreshMesh(); };
    const visible = () => { if (!document.hidden) refresh(); };
    window.addEventListener("focus", refresh); document.addEventListener("visibilitychange", visible);
    return () => { window.removeEventListener("focus", refresh); document.removeEventListener("visibilitychange", visible); };
  }, [refreshEnvironment, refreshMesh]);
  useEffect(() => {
    if (!nativeApp) return;
    const timer = window.setInterval(() => { if (document.hidden) return; refreshMesh(); if (page === "settings") refreshEnvironment(); }, 3000);
    return () => window.clearInterval(timer);
  }, [page, refreshEnvironment, refreshMesh]);
  useEffect(() => {
    remoteRequest.current += 1;
    setRemoteDevices([]); setRemoteSelected(new Set()); setRemoteProblem(""); setRemoteLoading(false);
    return () => { remoteRequest.current += 1; };
  }, [selectedPeerIp]);
  useEffect(() => {
    if (!busy && page === "connections" && selectedPeerIp) refreshRemote();
  }, [busy, page, selectedPeerIp, refreshRemote]);
  useEffect(() => {
    setRemoteSelected((old) => new Set([...old].filter((id) => remoteRows.some((item) => item.busId === id && !item.attached))));
  }, [remoteRows]);
  useEffect(() => {
    if (!prefs.autoRefresh) return;
    const timer = window.setInterval(() => { if (document.hidden || operation.current) return; page === "devices" ? refreshLocal() : page === "connections" && selectedPeerIp && refreshRemote(); }, 5000);
    return () => window.clearInterval(timer);
  }, [page, prefs.autoRefresh, refreshLocal, refreshRemote, selectedPeerIp]);
  useEffect(() => {
    if (!nativeApp || busy || !meshReady || !mesh.configured || !mesh.networkName) return;
    const identity = `${meshIdentity.current}|${mesh.localIp || "pending"}`;
    if (meshServiceCheckedFor.current === identity || !beginOperation("mesh-check")) return;
    // Record attempts, including cancellation, so polling never repeats a UAC prompt.
    meshServiceCheckedFor.current = identity;
    setMeshServiceReady(false);
    backend("ensure_mesh_service_current", { meshIp: mesh.localIp || null })
      .then((changed) => {
        setMeshServiceReady(true); setMeshServiceProblem("");
        if (changed) notify("EasyTier 服务配置已升级，正在重新连接");
      })
      .catch((error) => {
        const message = `EasyTier 服务配置检查失败：${String(error)}。请点击“修复连接”重试`;
        setMeshServiceProblem(message); notify(message, "error");
      })
      .finally(() => { endOperation(); refreshMesh(); });
  }, [busy, sharingSession.ready, mesh.configured, mesh.networkName, mesh.relay, mesh.localIp, meshReady, notify, refreshMesh, beginOperation, endOperation]);
  useEffect(() => {
    if (!nativeApp || busy || localProblem || !meshServiceReady || !mesh.running || !mesh.localIp || !devices.some((device) => device.shared)) return;
    const identity = `${meshIdentity.current}|${mesh.localIp}`;
    if (usbAccessCheckedFor.current === identity || !beginOperation("share-check")) return;
    usbAccessCheckedFor.current = identity;
    backend("ensure_usb_sharing_ready")
      .then(() => setSharingProblem(""))
      .catch((error) => {
        const message = `USB 共享访问规则检查失败：${String(error)}。请点击“修复共享”重试`;
        setSharingProblem(message); notify(message, "error");
      })
      .finally(endOperation);
  }, [busy, sharingSession.ready, devices, localProblem, mesh.localIp, mesh.running, meshServiceReady, notify, beginOperation, endOperation]);
  useEffect(() => {
    if (!installingComponent) return;
    const ready = installingComponent === "usbipd" ? environment.usbipdInstalled : environment.usbipInstalled && environment.usbipSafe;
    if (ready) { notify(`已检测到 ${installingComponent === "usbipd" ? "usbipd-win" : "usbip-win2"}`); setInstallingComponent(""); }
  }, [environment, installingComponent, notify]);

  const toggle = (id, remote = false) => {
    const setter = remote ? setRemoteSelected : setSelected;
    setter((old) => { const next = new Set(old); next.has(id) ? next.delete(id) : next.add(id); return next; });
  };

  const createNetwork = async () => {
    if (!beginOperation("mesh-create")) return;
    try {
      const result = await backend("create_mesh", { relay: relayDraft }, { ...demoMesh, configured: true, running: true, localIp: "10.126.126.1", networkName: "usblink-7f2a94c1830b", pairingCode: demoPairingCode });
      applyMesh(result); setEnvironment((old) => ({ ...old, meshConfigured: true, meshRunning: result.running, meshIp: result.localIp, meshPeerCount: result.peerCount }));
      notify("加密连接已创建，配对码可以发给另一台电脑");
    } catch (error) { notify(String(error), "error"); }
    finally { endOperation(); }
  };

  const joinNetwork = async () => {
    if (!joinCode.trim()) return;
    if (!beginOperation("mesh-join")) return;
    try {
      const result = await backend("join_mesh", { pairingCode: joinCode.trim() }, { ...demoMesh, configured: true, running: true, localIp: "10.126.126.2", networkName: "usblink-7f2a94c1830b" });
      applyMesh(result); setEnvironment((old) => ({ ...old, meshConfigured: true, meshRunning: result.running, meshIp: result.localIp, meshPeerCount: result.peerCount }));
      setJoinCode(""); notify("已加入加密连接");
    } catch (error) { notify(String(error), "error"); }
    finally { endOperation(); }
  };

  const revealAndCopyCode = async () => {
    if (!beginOperation("mesh-code")) return;
    try {
      let code = pairingCode;
      if (!code) { const result = await backend("get_mesh_status", { includeCode: true }, { ...mesh, pairingCode: demoPairingCode }); code = result.pairingCode; setPairingCode(code || ""); }
      if (code) { await copyText(code); notify("配对码已复制"); }
    } catch (error) { notify(String(error), "error"); }
    finally { endOperation(); }
  };

  const restartNetwork = async () => {
    if (!beginOperation("mesh-restart")) return;
    try { const result = await backend("restart_mesh", {}, mesh); applyMesh(result); notify("EasyTier 网络已重新启动"); }
    catch (error) { notify(String(error), "error"); }
    finally { endOperation(); }
  };

  const repairNetwork = async () => {
    if (!beginOperation("mesh-repair")) return;
    try {
      const result = await backend("repair_mesh", {}, { ...mesh, relay: defaultRelay, running: false, problem: null, needsRepair: false });
      applyMesh(result); setEnvironment((old) => ({ ...old, meshRunning: false, meshIp: null, meshRelay: result.relay }));
      meshServiceCheckedFor.current = meshIdentity.current; usbAccessCheckedFor.current = "";
      setMeshServiceReady(true); setMeshServiceProblem("");
      notify("连接配置已修复，正在重新建立网络");
    } catch (error) { notify(String(error), "error"); }
    finally { endOperation(); }
  };

  const leaveNetwork = async () => {
    if (!window.confirm("确定离开当前加密连接吗？本机保存的配对信息将被删除。")) return;
    if (!beginOperation("mesh-leave")) return;
    try {
      await backend("leave_mesh"); const empty = { configured: false, running: false, localIp: null, networkName: null, relay: defaultRelay, peerCount: 0, peers: [], problem: null, needsRepair: false };
      applyMesh(empty); setEnvironment((old) => ({ ...old, meshConfigured: false, meshRunning: false, meshIp: null, meshPeerCount: 0 }));
      setPairingCode(""); setRemoteDevices([]); setRemoteProblem(""); notify("已离开连接");
    } catch (error) { notify(String(error), "error"); }
    finally { endOperation(); }
  };

  const saveRelay = async () => {
    if (!beginOperation("mesh-relay")) return;
    try {
      const relay = normalizeRelay(relayDraft);
      if (mesh.configured) {
        const result = await backend("change_mesh_relay", { relay }, { ...mesh, relay, pairingCode: null });
        relayDirty.current = false; applyMesh(result);
      } else { relayDirty.current = false; setRelayDraft(relay); }
      writeStored("usblink.relay", relay);
      notify(mesh.configured ? "中继地址已更新" : "默认中继地址已保存");
    } catch (error) { notify(String(error), "error"); }
    finally { endOperation(); }
  };

  const share = async () => {
    if (!selected.size || localProblem || !mesh.running || !meshServiceReady) return; if (!beginOperation("share")) return;
    try { await backend("share_devices", { busIds: [...selected] }); if (!nativeApp) setDevices((old) => old.map((item) => selected.has(item.busId) ? { ...item, shared: true } : item)); notify(`已共享 ${selected.size} 个 USB 设备`); setSelected(new Set()); }
    catch (error) { notify(String(error), "error"); } finally { await refreshLocal(true); endOperation(); }
  };
  const repairSharing = async () => {
    if (!beginOperation("share-repair")) return;
    try {
      await backend("repair_usb_sharing"); setSharingProblem("");
      usbAccessCheckedFor.current = `${meshIdentity.current}|${mesh.localIp}`;
      notify("USB 共享访问已修复");
    }
    catch (error) { setSharingProblem(String(error)); notify(String(error), "error"); }
    finally { endOperation(); }
  };
  const unshare = async (busId) => {
    if (localProblem) return;
    if (!beginOperation(busId)) return; try { await backend("unshare_device", { busId }); if (!nativeApp) setDevices((old) => old.map((item) => item.busId === busId ? { ...item, shared: false } : item)); notify("已停止共享"); }
    catch (error) { notify(String(error), "error"); } finally { await refreshLocal(true); endOperation(); }
  };
  const attach = async () => {
    if (!mesh.running || !meshServiceReady || !environment.usbipSafe || !canUsePeer(selectedPeer) || !remoteSelected.size || usbOperation.current || !attached.ready || attached.problem) return;
    const host = selectedPeer.ip;
    const chosen = remoteRows.filter((item) => remoteSelected.has(item.busId) && !item.attached);
    if (!chosen.length || remoteProblem) return;
    if (!beginOperation("attach")) return;
    usbOperation.current = true; attached.pause();
    let confirmed = false;
    try {
      const result = await backend("attach_devices", { host, busIds: chosen.map((item) => item.busId), expectedVidPids: Object.fromEntries(chosen.map((item) => [item.busId, item.vidPid])) }, () => {
        previewAttached.current = [...previewAttached.current, ...chosen.map((item, index) => ({ ...item, host, attached: true, port: previewAttached.current.length + index + 1 }))];
        return previewAttached.current;
      });
      if (!Array.isArray(result) || chosen.some((item) => !applyAttachedState(host, [item], result)[0]?.attached)) throw new Error("未收到所选设备的完整挂载结果，请刷新连接状态");
      attached.accept(resolveAttachedNames(result)); confirmed = true;
      notify(`已连接 ${chosen.length} 个远程 USB 设备`);
      setRemoteSelected(new Set());
    } catch (error) { notify(String(error), "error"); }
    finally {
      attached.resume();
      if (!confirmed) await attached.refresh(true);
      usbOperation.current = false; endOperation();
      refreshRemote(false, true); refreshMesh();
    }
  };
  const stopAll = async () => {
    if (usbOperation.current) return;
    if (!beginOperation("detach")) return;
    usbOperation.current = true; attached.pause();
    try {
      await backend("detach_all_devices");
      if (!nativeApp) previewAttached.current = [];
      removeStored("usblink.lastConnection"); notify("远程 USB 已全部断开");
    } catch (error) { notify(String(error), "error"); }
    finally {
      attached.resume();
      await attached.refresh(true);
      usbOperation.current = false; endOperation();
      refreshRemote(false, true); refreshMesh();
    }
  };
  const openDownload = async (kind) => {
    const needsInstall = kind === "usbipd" ? !environment.usbipdInstalled : kind === "usbip" && (!environment.usbipInstalled || !environment.usbipSafe);
    if (needsInstall) setInstallingComponent(kind);
    try { await backend("open_dependency_download", { kind }); }
    catch (error) { if (needsInstall) setInstallingComponent(""); notify(String(error), "error"); }
  };
  const checkComponents = async () => { setCheckingComponents(true); await refreshEnvironment(); window.setTimeout(() => setCheckingComponents(false), 450); };
  const updatePreference = async (key, value) => {
    if (key === "autoStart") {
      if (preferencePending.current) return;
      preferencePending.current = true; setSavingPreference(true);
      try {
        await backend("set_auto_start", { enabled: value });
        setPrefs((old) => ({ ...old, [key]: value }));
        writeStored("usblink.autoStart", JSON.stringify(value));
        notify(value ? "已设置开机启动" : "已关闭开机启动");
      } catch (error) { notify(String(error), "error"); }
      finally { preferencePending.current = false; setSavingPreference(false); }
    } else {
      setPrefs((old) => ({ ...old, [key]: value }));
      writeStored("usblink." + key, JSON.stringify(value));
    }
  };

  const displayedMesh = { ...mesh, running: mesh.running && meshServiceReady, problem: meshServiceProblem || mesh.problem, needsRepair: !!meshServiceProblem || mesh.needsRepair };
  const effectiveBusy = busy || (!sharingSession.ready ? sharingSession.phase === "closing" ? "closing" : "initializing" : "");
  return <div className="app-shell">
    <aside className="sidebar">
      <div className="brand"><span className="brand-mark"><Link20Regular /></span><span>USBLink</span></div>
      <nav>
        <button aria-label="设备" title="设备" className={page === "devices" ? "active" : ""} onClick={() => setPage("devices")}><UsbPlug20Regular /><span>设备</span></button>
        <button aria-label="连接" title="连接" className={page === "connections" ? "active" : ""} onClick={() => setPage("connections")}><ArrowSync20Regular /><span>连接</span></button>
        <button aria-label="设置" title="设置" className={page === "settings" ? "active" : ""} onClick={() => setPage("settings")}><Settings20Regular /><span>设置</span></button>
      </nav>
      <div className="network-state"><StatusDot online={displayedMesh.running} /><div><strong>{displayedMesh.problem ? "连接异常" : displayedMesh.running ? "加密网络" : mesh.configured ? "正在连接" : "尚未配对"}</strong><span>{mesh.localIp || "无需注册账号"}</span></div></div>
    </aside>
    <main className="workspace">
      {!sharingSession.ready && <p className="usb-status-warning sharing-session-status" role="status"><ErrorCircle20Regular />{sharingSession.problem || (sharingSession.phase === "closing" ? "正在停止共享并断开 USB，完成后退出…" : "正在清理上次遗留的 USB 共享和挂载，请稍候…")}{sharingSession.phase === "failed" && <button className="text-action" disabled={!!busy} onClick={retrySharingCleanup}>重新清理 USB</button>}</p>}
      {page === "devices" && <DevicePage environment={environment} mesh={displayedMesh} localProblem={localProblem} sharingProblem={sharingProblem} devices={devices} selected={selected} selectedPeer={selectedPeer} peerMenu={peerMenu} setPeerMenu={setPeerMenu} peers={peers} setSelectedPeerIp={setSelectedPeerIp} toggle={toggle} setSelected={setSelected} busy={effectiveBusy} localLoading={localLoading} refresh={() => refreshLocal()} share={share} repairSharing={repairSharing} unshare={unshare} openDownload={openDownload} setPage={setPage} />}
      {page === "connections" && <ConnectionPage environment={environment} mesh={displayedMesh} createNetwork={createNetwork} joinNetwork={joinNetwork} joinCode={joinCode} setJoinCode={setJoinCode} pairingCode={pairingCode} revealAndCopyCode={revealAndCopyCode} restartNetwork={restartNetwork} repairNetwork={repairNetwork} leaveNetwork={leaveNetwork} peers={peers} selectedPeer={selectedPeer} setSelectedPeerIp={setSelectedPeerIp} peerMenu={peerMenu} setPeerMenu={setPeerMenu} devices={remoteRows} attached={attached} remoteLoading={remoteLoading} remoteProblem={remoteProblem} selected={remoteSelected} setSelected={setRemoteSelected} toggle={(id) => toggle(id, true)} busy={effectiveBusy || (!meshReady ? "initializing" : "")} refresh={() => { refreshMesh(); refreshRemote(true); attached.refresh(); }} attach={attach} stopAll={stopAll} openDownload={openDownload} />}
      {page === "settings" && <SettingsPage environment={environment} mesh={mesh} prefs={prefs} savingPreference={savingPreference} updatePreference={updatePreference} openDownload={openDownload} installingComponent={installingComponent} checkingComponents={checkingComponents} checkComponents={checkComponents} relayDraft={relayDraft} setRelayDraft={(value) => { relayDirty.current = true; setRelayDraft(value); }} saveRelay={saveRelay} busy={effectiveBusy} />}
    </main>
    {toast && <div className={`toast ${toast.type}`}><span>{toast.type === "error" ? <ErrorCircle20Regular /> : <Checkmark16Regular />}</span><p>{toast.message}</p><button className="icon-button" aria-label="关闭通知" onClick={() => setToast(null)}><Dismiss16Regular /></button></div>}
  </div>;
}

function PageHeader({ title, subtitle, icon: Icon, onRefresh, spinning, tone = "normal" }) {
  return <header className="page-header"><div><h1>{title}</h1><p className={tone}><Icon />{subtitle}</p></div>{onRefresh && <button className="secondary-button" onClick={onRefresh}><ArrowClockwise20Regular className={spinning ? "spin" : ""} />刷新</button>}</header>;
}
function PeerPicker({ selectedPeer, peers, open, setOpen, select, compact = false }) {
  return <div className={`peer-wrap ${compact ? "compact" : ""}`}><button className="peer-picker" onClick={() => setOpen(!open)}><Desktop20Regular /><div><strong>{selectedPeer?.name || "选择电脑"}</strong><span>{selectedPeer ? `${selectedPeer.ip} · ${selectedPeer.online ? "USBLink 在线" : "USBLink 未运行或不可达"}` : "等待对方加入"}</span></div><StatusDot online={!!selectedPeer?.online} /><ChevronDown16Regular /></button>{open && <div className="peer-menu">{peers.length ? peers.map((peer) => <button key={peer.ip} onClick={() => { select(peer.ip); setOpen(false); }}><StatusDot online={!!peer.online} /><span><strong>{peer.name}</strong><small>{peer.ip} · {peer.online ? "USBLink 在线" : "USBLink 未运行或不可达"}</small></span></button>) : <p>还没有发现其他 USBLink 电脑</p>}</div>}</div>;
}

function DevicePage({ environment, mesh, devices, selected, selectedPeer, peerMenu, setPeerMenu, peers, setSelectedPeerIp, toggle, setSelected, busy, localLoading, localProblem, sharingProblem, refresh, share, repairSharing, unshare, openDownload, setPage }) {
  const all = devices.length > 0 && selected.size === devices.length;
  const hasShared = devices.some((device) => device.shared);
  return <><PageHeader title="设备" subtitle="共享仅在本次运行期间有效，退出时自动停止" icon={ShieldCheckmark20Filled} onRefresh={refresh} spinning={localLoading} />
    {mesh.problem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />{mesh.problem}<button className="text-action" onClick={() => setPage("connections")}>前往连接</button></p>}
    {localProblem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />本机设备状态待确认，请刷新后重试：{localProblem}</p>}
    {sharingProblem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />{sharingProblem}</p>}
    <section className="connection-overview"><div className="machine"><span>本机</span><div><Desktop20Regular /><p><strong>{environment.computerName}</strong><small>{mesh.localIp || "尚未加入加密网络"}</small></p></div></div><div className={`connection-line ${selectedPeer?.online ? "connected" : ""}`}><i /><b><LockClosed16Filled /></b><i /></div><div className="machine remote"><span>远程电脑</span><PeerPicker compact selectedPeer={selectedPeer} peers={peers} open={peerMenu} setOpen={setPeerMenu} select={setSelectedPeerIp} /></div></section>
    {!mesh.configured ? <DependencyEmpty icon={ArrowRight20Regular} title="先连接另一台电脑" text="无需账号，在“连接”页面创建配对码或输入对方的配对码。" action="前往连接" onClick={() => setPage("connections")} /> : !environment.usbipdInstalled ? <DependencyEmpty title="USB 共享服务尚未安装" text="安装免费的 usbipd-win 后即可共享本机 USB 设备。" action="打开安装页面" onClick={() => openDownload("usbipd")} /> : <DeviceTable title="可共享的 USB 设备" devices={devices} selected={selected} all={all} onAll={() => setSelected(all ? new Set() : new Set(devices.map((item) => item.busId)))} toggle={toggle} busy={busy} unshare={unshare} unavailable={!!localProblem} connectionKnown={!localProblem} />}
    <footer className="command-bar"><div><strong>{!mesh.running ? "等待加密网络连接" : selected.size ? `已选择 ${selected.size} 个设备` : "选择需要共享的设备"}</strong><span>{mesh.running ? "选择数量不受限制" : "创建或加入连接后即可共享"}</span></div>{hasShared && <button className="secondary-button" disabled={!!busy} onClick={repairSharing}><ArrowClockwise20Regular />{busy === "share-repair" ? "正在修复…" : "修复共享"}</button>}<button className="primary-button" disabled={!mesh.running || !!localProblem || !environment.usbipdInstalled || !selected.size || !!busy} onClick={share}><Open20Regular />{busy === "share" ? "正在共享…" : "共享所选设备"}</button></footer>
  </>;
}

function ConnectionPage({ environment, mesh, createNetwork, joinNetwork, joinCode, setJoinCode, pairingCode, revealAndCopyCode, restartNetwork, repairNetwork, leaveNetwork, peers, selectedPeer, setSelectedPeerIp, peerMenu, setPeerMenu, devices, attached, remoteLoading, remoteProblem, selected, setSelected, toggle, busy, refresh, attach, stopAll, openDownload }) {
  const available = devices.filter((device) => !device.attached);
  const all = available.length > 0 && available.every((device) => selected.has(device.busId));
  const onlineCount = peers.filter(peer => peer.online).length;
  const mountsUnavailable = attached.devices.some(device => mountPresence(device, peers, mesh.running) !== "已连接");
  const imported = <AttachedDevices snapshot={attached} stopAll={stopAll} busy={busy} peers={peers} networkReady={mesh.running} />;
  if (!mesh.configured) return <><PageHeader title="连接" subtitle={mesh.problem || "无需注册账号，使用配对码连接两台电脑"} tone={mesh.problem ? "error" : "normal"} icon={NetworkCheck20Regular} onRefresh={refresh} />{imported}<PairingSetup createNetwork={createNetwork} joinNetwork={joinNetwork} joinCode={joinCode} setJoinCode={setJoinCode} busy={busy} /></>;
  return <><PageHeader title="连接" subtitle={mesh.problem || (mesh.running ? "EasyTier 加密网络已启动" : "EasyTier 网络正在启动")} tone={mesh.problem ? "error" : "normal"} icon={mesh.problem ? ErrorCircle20Regular : NetworkCheck20Regular} onRefresh={refresh} spinning={remoteLoading} />
    <div className="connection-content">
    <section className={`mesh-summary ${mesh.problem ? "problem" : ""}`}><div><span className="mesh-icon">{mesh.problem ? <ErrorCircle20Regular /> : <NetworkCheck20Regular />}</span><p><strong>{mesh.problem ? "连接失败" : mesh.running ? onlineCount ? "对方 USBLink 在线" : "暂无在线 USBLink" : "等待网络服务"}</strong><small>{mesh.problem || (mesh.running && !onlineCount ? "配对信息已保存，等待对方打开 USBLink" : mesh.localIp) || "正在分配虚拟地址"}</small></p></div><div className="mesh-meta"><span>应用响应已验证</span><strong>{onlineCount} 台电脑</strong></div><div className="mesh-actions">{mesh.needsRepair && <button className="primary-button compact" onClick={repairNetwork} disabled={!!busy}><ArrowClockwise20Regular />{busy === "mesh-repair" ? "正在修复…" : "修复连接"}</button>}<button className="secondary-button" onClick={revealAndCopyCode} disabled={!!busy}><Copy20Regular />复制配对码</button><button className="icon-button" title="重新启动网络" aria-label="重新启动网络" onClick={restartNetwork} disabled={!!busy}><Power20Regular /></button><button className="danger-action" onClick={leaveNetwork} disabled={!!busy}>离开连接</button></div></section>
    {pairingCode && <div className="pairing-code-band"><Key20Regular /><input aria-label="当前配对码" readOnly value={pairingCode} /><button className="text-action" disabled={!!busy} onClick={revealAndCopyCode}><Copy20Regular />复制</button></div>}
    {imported}
    <section className="remote-source"><span>设备来源</span><PeerPicker selectedPeer={selectedPeer} peers={peers} open={peerMenu} setOpen={setPeerMenu} select={setSelectedPeerIp} /></section>
    {selectedPeer && !canUsePeer(selectedPeer) && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />{selectedPeer.problem || (selectedPeer.online ? "USBLink 在线，但 USB 共享服务不可达" : "对方 USBLink 未运行或不可达，请双方更新并打开程序")}</p>}
    {remoteProblem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />远程设备列表暂时无法更新：{remoteProblem}</p>}
    {!environment.usbipInstalled ? <DependencyEmpty title="远程 USB 驱动尚未安装" text="安装免费的 usbip-win2 后即可连接远程 USB。" action="打开安装页面" onClick={() => openDownload("usbip")} /> : !environment.usbipSafe ? <DependencyEmpty icon={ErrorCircle20Regular} title="远程 USB 驱动必须更新" text={environment.usbipProblem || "当前驱动存在系统崩溃风险，USBLink 已阻止连接。"} action="更新到 0.9.8.0" onClick={() => openDownload("usbip")} /> : !selectedPeer ? <DependencyEmpty icon={Copy20Regular} title="等待另一台电脑加入" text="复制配对码发给对方，对方加入后会自动出现在这里。" action="复制配对码" onClick={revealAndCopyCode} /> : <DeviceTable title="远程可用设备" remote devices={devices} selected={selected} all={all} onAll={() => setSelected(all ? new Set() : new Set(available.map((item) => item.busId)))} toggle={toggle} busy={busy} unavailable={!mesh.running || !canUsePeer(selectedPeer) || !!remoteProblem || !attached.ready || !!attached.problem} connectionKnown={attached.ready && !attached.problem && mesh.running && canUsePeer(selectedPeer)} peerProblem={!selectedPeer.online ? "对方 USBLink 未运行或不可达" : selectedPeer.usbReady === false ? "共享服务不可达" : ""} />}
    </div>
    <footer className="command-bar"><button className="text-action" onClick={stopAll} disabled={!!busy}>断开全部 USB</button><div><strong>{selected.size ? `已选择 ${selected.size} 个设备` : attached.devices.length ? (attached.problem || mountsUnavailable ? "USB 连接状态待确认" : `已连接 ${attached.devices.length} 个远程 USB`) : "选择需要连接的设备"}</strong><span>{selectedPeer ? `来自 ${selectedPeer.name}` : "等待另一台电脑"}</span></div><button className="primary-button" disabled={!mesh.running || !canUsePeer(selectedPeer) || !environment.usbipSafe || !attached.ready || !!attached.problem || !!remoteProblem || !selected.size || !selectedPeer || !!busy} onClick={attach}><PlugConnected20Regular />{busy === "attach" ? "正在连接…" : "连接所选设备"}</button></footer>
  </>;
}

function PairingSetup({ createNetwork, joinNetwork, joinCode, setJoinCode, busy }) {
  return <section className="pairing-setup"><div><span className="pairing-icon"><Add20Regular /></span><h2>创建新连接</h2><p>生成一个加密配对码，发给另一台电脑即可。</p><button className="primary-button small" onClick={createNetwork} disabled={!!busy}>{busy === "mesh-create" ? "正在创建…" : "创建连接"}<ArrowRight20Regular /></button></div><i /><div><span className="pairing-icon"><Key20Regular /></span><h2>加入已有连接</h2><p>粘贴另一台电脑生成的 USBLink 配对码。</p><label className="join-field"><input aria-label="配对码" placeholder="粘贴配对码" value={joinCode} onChange={(event) => setJoinCode(event.target.value)} /><button aria-label="加入连接" title="加入连接" onClick={joinNetwork} disabled={!joinCode.trim() || !!busy}><ArrowRight20Regular /></button></label></div></section>;
}

function DeviceTable({ title, devices, selected, all, onAll, toggle, busy, unshare, remote = false, unavailable = false, connectionKnown = true, peerProblem = "" }) {
  return <section className="device-panel"><div className="list-head"><div><Check checked={all} onChange={onAll} label="全选" disabled={unavailable || !!busy} /><strong>{title}</strong><span className="badge">{devices.length}</span></div><span>状态</span><span>设备编号</span><span>操作</span></div><div className="device-list">{devices.length ? devices.map((device) => { const Icon = deviceIcon(device); const confirmedAttached = device.attached && connectionKnown; const unconfirmed = unavailable && !confirmedAttached; return <div key={device.busId} className={`device-row ${selected.has(device.busId) ? "selected" : ""}`}><div className="device-main"><Check checked={selected.has(device.busId)} onChange={() => toggle(device.busId)} label={`选择 ${device.name}`} disabled={unavailable || !!busy || (remote && device.attached)} /><span className="device-icon"><Icon /></span><p><strong>{device.name}</strong><small>{device.detail || device.vidPid}</small></p></div><div className={`device-status ${unconfirmed ? "unconfirmed" : ""}`}><StatusDot online={!unconfirmed} /><span>{remote ? peerProblem ? peerProblem : confirmedAttached ? "已连接" : unavailable ? "状态待确认" : "可连接" : unavailable ? "状态待确认" : device.attached ? "正在被使用" : device.shared ? "已共享" : "可共享"}</span></div><div className="device-id"><strong>{device.busId}</strong><span>{device.vidPid}</span></div><div className="row-action">{!remote && device.shared ? <button className="danger-action" disabled={unavailable || !!busy} onClick={() => unshare(device.busId)}>停止共享</button> : remote ? device.attached ? <Checkmark16Regular /> : <ChevronRight20Regular /> : <button className="icon-button" aria-label={`${device.name} 更多操作`}><MoreHorizontal20Regular /></button>}</div></div>; }) : <div className="empty-list"><UsbPlug20Regular /><strong>{remote ? "没有发现远程 USB 设备" : "没有发现 USB 设备"}</strong><span>{remote ? "确认远程电脑已经共享设备" : "插入设备后点击刷新"}</span></div>}</div></section>;
}

function AttachedDevices({ snapshot, stopAll, busy, peers, networkReady }) {
  if (!snapshot.devices.length && !snapshot.problem) return null;
  return <section className="attached-devices" aria-label="已连接到本机的 USB">
    <header><h2>{snapshot.problem ? "USB 连接状态待确认" : "已连接到本机"}<span className="badge">{snapshot.devices.length}</span></h2><button className="text-action" onClick={stopAll} disabled={!!busy}><PlugDisconnected20Regular />断开全部 USB</button></header>
    {snapshot.problem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />无法读取挂载状态，以下为上次结果：{snapshot.problem}</p>}
    {snapshot.devices.map((device) => { const Icon = deviceIcon(device); const presence = snapshot.problem ? "状态待确认" : mountPresence(device, peers, networkReady); return <div className="attached-row" key={`${device.host}|${device.port}`}>
      <span className="device-icon"><Icon /></span><p><strong>{device.name}</strong><small>{device.host} · {device.busId} · 端口 {device.port}</small></p>
      <span className={`device-status ${presence !== "已连接" ? "unconfirmed" : ""}`}><StatusDot online={presence === "已连接"} />{presence}</span>
    </div>; })}
  </section>;
}

function DependencyEmpty({ icon: Icon = Open20Regular, title, text, action, onClick }) { return <section className="dependency-empty"><span><PlugDisconnected20Regular /></span><h2>{title}</h2><p>{text}</p><button className="primary-button small" onClick={onClick}><Icon />{action}</button></section>; }
function SettingsPage({ environment, mesh, prefs, savingPreference, updatePreference, openDownload, installingComponent, checkingComponents, checkComponents, relayDraft, setRelayDraft, saveRelay, busy }) {
  return <><PageHeader title="设置" subtitle="连接方式与运行偏好" icon={Settings20Regular} onRefresh={checkComponents} spinning={checkingComponents} /><div className="settings-layout"><section><h2>运行偏好</h2><Preference label="自动刷新设备" text="定时更新本机和远程 USB 状态" checked={prefs.autoRefresh} change={(value) => updatePreference("autoRefresh", value)} /><Preference label="开机启动 USBLink" text="登录 Windows 后自动打开管理界面" checked={prefs.autoStart} disabled={savingPreference} change={(value) => updatePreference("autoStart", value)} /></section>
    <section><h2>网络</h2><div className="relay-setting"><p><strong>EasyTier 中继节点</strong><span>直连失败时使用；配对码会把这个地址带给另一台电脑。</span></p><div><input aria-label="EasyTier 中继节点" value={relayDraft} onChange={(event) => setRelayDraft(event.target.value)} /><button className="secondary-button" disabled={!!busy} onClick={saveRelay}>保存</button></div></div></section>
    <section><h2>组件状态</h2><p className="component-help">EasyTier 已内置；USB 驱动安装完成后会自动刷新。</p><Dependency name="EasyTier" ready info={`已内置 ${environment.easytierVersion}`} open={() => openDownload("easytier")} /><Dependency name="usbipd-win" ready={environment.usbipdInstalled} pending={installingComponent === "usbipd"} info={environment.usbipdVersion || "共享服务"} open={() => openDownload("usbipd")} /><Dependency name="usbip-win2" ready={environment.usbipInstalled && environment.usbipSafe} warning={environment.usbipInstalled && !environment.usbipSafe} pending={installingComponent === "usbip"} info={environment.usbipProblem || environment.usbipVersion || "远程驱动"} open={() => openDownload("usbip")} /></section>
    <section><h2>安全</h2><div className="security-note"><ShieldCheckmark20Filled /><p><strong>USB 数据通过 EasyTier 加密传输</strong><span>无需账号；配对信息由 Windows DPAPI 加密保存。社区节点只负责发现或转发，不能读取 USB 内容。</span></p></div></section></div></>;
}
function Preference({ label, text, checked, change, disabled = false }) { return <div className="preference"><p><strong>{label}</strong><span>{text}</span></p><button className={`toggle ${checked ? "on" : ""}`} role="switch" aria-label={label} disabled={disabled} aria-checked={checked} onClick={() => change(!checked)}><i /></button></div>; }
function Dependency({ name, ready, warning, pending, info, open }) { return <div className="dependency"><span className={pending ? "checking" : warning ? "warning" : ready ? "ready" : ""}>{pending ? <ArrowClockwise20Regular className="spin" /> : warning ? <ErrorCircle20Regular /> : ready ? <Checkmark16Regular /> : <PlugDisconnected20Regular />}</span><p><strong>{name}</strong><small>{pending ? "等待安装完成…" : warning || ready ? info : "尚未安装"}</small></p><button className="text-action" onClick={open}>{pending ? "重新打开" : warning ? "更新" : ready ? "查看" : "安装"}<Open20Regular /></button></div>; }
