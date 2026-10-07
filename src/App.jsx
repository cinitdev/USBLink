import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import {
  Add20Regular, ArrowClockwise20Regular, ArrowRight20Regular, ArrowSync20Regular,
  Checkmark16Regular, ChevronDown16Regular, Copy20Regular,
  Desktop20Regular, Phone20Regular, Dismiss16Regular, ErrorCircle20Regular, Key20Regular,
  Link20Regular, LockClosed16Filled, NetworkCheck20Regular,
  Open20Regular, PlugConnected20Regular, PlugDisconnected20Regular, Power20Regular,
  Settings20Regular, ShieldCheckmark20Filled, Storage20Regular, UsbPlug20Regular,
} from "@fluentui/react-icons";
import { invoke } from "@tauri-apps/api/core";
import { useAttachedDevices } from "./useAttachedDevices.js";
import { applyAttachedState, sameDevice } from "./usb-state.mjs";
import { isPhoneDevice } from "./device-categories.mjs";
import { canUsePeer, mergePeerPresence, mountPresence, peerStatusLabel, adbConnectCommand } from "./peer-state.mjs";
import { readStored, readBoolean, writeStored, removeStored, normalizeRelay } from "./preferences.mjs";

const nativeApp = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
const previewUnpaired = !nativeApp && new URLSearchParams(window.location.search).has("unpaired");
const previewLegacy = !nativeApp && new URLSearchParams(window.location.search).has("legacy");
const previewAndroid = !nativeApp && new URLSearchParams(window.location.search).get("android");
const defaultRelay = "tcp://183.230.36.171:11010";
const legacyRelay = "tcp://public.easytier.top:11010";
const demoPairingCode = "USBLINK1-eyJ2ZXJzaW9uIjoxLCJuZXR3b3JrX25hbWUiOiJ1c2JsaW5rLWRlbW8iLCJuZXR3b3JrX3NlY3JldCI6ImRlbW8iLCJyZWxheSI6InRjcDovL3B1YmxpYy5lYXN5dGllci50b3A6MTEwMTAifQ";
const demoPeers = previewAndroid ? [{ name: "USBLink-Android", ip: "10.126.126.2", online: previewAndroid !== 'offline', usbReady: previewAndroid === 'usb', usbKind: previewAndroid.startsWith('usb') ? 'android-adb-experimental' : 'usbip', os: 'android', adbReady: previewAndroid === 'ready', adbState: previewAndroid, latency: '3.5', tunnel: 'udp' }] : [{ name: "OFFICE-PC", ip: "10.126.126.2", online: true, os: "windows", latency: "3.5", tunnel: "udp" }];
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
function Check({ checked, mixed = false, onChange, label, disabled = false }) {
  return <button type="button" role="checkbox" disabled={disabled} aria-checked={mixed ? "mixed" : checked} aria-label={label} className={`check ${checked || mixed ? "checked" : ""}`} onClick={onChange}>{mixed ? <span aria-hidden="true">−</span> : checked && <Checkmark16Regular />}</button>;
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
    // An authenticated source may deliberately return a generic name when the
    // device identity is ambiguous. Never override that with an old port name.
    if (device.friendlyName && name) {
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
  const downloadPending = useRef(false);
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
  const [deviceCategory, setDeviceCategory] = useState("all");
  const [remoteDevices, setRemoteDevices] = useState([]);
  const [remoteProblem, setRemoteProblem] = useState("");
  const [remoteLoading, setRemoteLoading] = useState(false);
  const [remoteSelected, setRemoteSelected] = useState(new Set());
  const [peerMenu, setPeerMenu] = useState(false);
  const [busy, setBusy] = useState("");
  const [activeBusIds, setActiveBusIds] = useState([]);
  const [detachingDevice, setDetachingDevice] = useState(null);
  const [toast, setToast] = useState(null);
  const [pairingCode, setPairingCode] = useState("");
  const [joinCode, setJoinCode] = useState("");
  const [joinProblem, setJoinProblem] = useState("");
  const [relayProblem, setRelayProblem] = useState("");
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [relayDraft, setRelayDraft] = useState(() => {
    const saved = readStored("usblink.relay");
    return !saved || saved === legacyRelay ? defaultRelay : saved;
  });
  const [checkingComponents, setCheckingComponents] = useState(false);
  const [savedRelay, setSavedRelay] = useState(relayDraft);
  const [installingComponents, setInstallingComponents] = useState([]);
  const [openingDownload, setOpeningDownload] = useState("");
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
    setToast({ message, type });
    if (type !== "error") toastTimer.current = window.setTimeout(() => setToast(null), 5000);
  }, []);
  useEffect(() => () => window.clearTimeout(toastTimer.current), []);
  useEffect(() => { setPeerMenu(false); }, [page]);

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
    if (next.configured) {
      setSavedRelay(next.relay || defaultRelay);
      if (!relayDirty.current) setRelayDraft(next.relay || defaultRelay);
    }
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
    setBusy(""); setActiveBusIds([]);
  }, []);

  const refreshEnvironment = useCallback(async () => {
    if (!nativeApp) return demoEnvironment;
    if (environmentPending.current) return environmentPending.current;
    const pending = backend("get_environment_status")
      .then(next => { setEnvironment(next); return next; })
      .catch(error => { notify(String(error), "error"); })
      .finally(() => { if (environmentPending.current === pending) environmentPending.current = null; });
    environmentPending.current = pending;
    return pending;
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
      setSelected((old) => new Set([...old].filter((id) => next.some((item) => item.busId === id && !item.shared))));
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
      const fallback = previewAndroid === 'usb' ? [{busId:'99-1',vidPid:'18d1:4ee7',name:'Redmi K40 · USB ADB（实验）',detail:'手机 · USB ADB 实验通道',shared:true,friendlyName:true}] : demoDevices.map((item) => ({ ...item, shared: true }));
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
    const completed = installingComponents.filter(kind => kind === "usbipd" ? environment.usbipdInstalled : environment.usbipInstalled && environment.usbipSafe);
    if (completed.length) {
      notify(`已检测到 ${completed.map(kind => kind === "usbipd" ? "usbipd-win" : "usbip-win2").join("、")}`);
      setInstallingComponents(old => old.filter(kind => !completed.includes(kind)));
    }
  }, [environment, installingComponents, notify]);

  const toggle = (id, remote = false) => {
    const setter = remote ? setRemoteSelected : setSelected;
    setter((old) => { const next = new Set(old); next.has(id) ? next.delete(id) : next.add(id); return next; });
  };

  const createNetwork = async () => {
    if (!beginOperation("mesh-create")) return;
    try {
      const result = await backend("create_mesh", { relay: savedRelay }, { ...demoMesh, configured: true, running: true, relay: savedRelay, localIp: "10.126.126.1", networkName: "usblink-7f2a94c1830b", pairingCode: demoPairingCode });
      applyMesh(result); setEnvironment((old) => ({ ...old, meshConfigured: true, meshRunning: result.running, meshIp: result.localIp, meshPeerCount: result.peerCount }));
      notify("加密连接已创建，配对码可以发给另一台电脑");
    } catch (error) { notify(String(error), "error"); }
    finally { endOperation(); }
  };

  const joinNetwork = async () => {
    if (!joinCode.trim()) return;
    if (!beginOperation("mesh-join")) return;
    setJoinProblem("");
    try {
      const result = await backend("join_mesh", { pairingCode: joinCode.trim() }, { ...demoMesh, configured: true, running: true, localIp: "10.126.126.2", networkName: "usblink-7f2a94c1830b" });
      applyMesh(result); setEnvironment((old) => ({ ...old, meshConfigured: true, meshRunning: result.running, meshIp: result.localIp, meshPeerCount: result.peerCount }));
      setJoinCode(""); notify("已加入加密连接");
    } catch (error) { setJoinProblem(String(error)); }
    finally { endOperation(); }
  };

  const revealAndCopyCode = async () => {
    if (!beginOperation("mesh-code")) return;
    try {
      let code = pairingCode;
      if (!code) { const result = await backend("get_mesh_status", { includeCode: true }, { ...mesh, pairingCode: demoPairingCode }); code = result.pairingCode; setPairingCode(code || ""); }
      if (!code) throw new Error("暂时无法获取配对码，请刷新网络状态后重试");
      await copyText(code); notify("配对码已复制");
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
    if (!beginOperation("mesh-leave")) return;
    setConfirmLeave(false);
    try {
      await backend("leave_mesh"); const empty = { configured: false, running: false, localIp: null, networkName: null, relay: defaultRelay, peerCount: 0, peers: [], problem: null, needsRepair: false };
      applyMesh(empty); setEnvironment((old) => ({ ...old, meshConfigured: false, meshRunning: false, meshIp: null, meshPeerCount: 0 }));
      setPairingCode(""); setRemoteDevices([]); setRemoteProblem(""); notify("已离开连接");
    } catch (error) { notify(String(error), "error"); }
    finally { endOperation(); }
  };

  const saveRelay = async () => {
    if (relayDraft.trim() === savedRelay) return;
    let relay;
    try { relay = normalizeRelay(relayDraft); }
    catch (error) { setRelayProblem(error.message); return; }
    if (!beginOperation("mesh-relay")) return;
    setRelayProblem("");
    try {
      if (mesh.configured) {
        const result = await backend("change_mesh_relay", { relay }, { ...mesh, relay, pairingCode: null });
        relayDirty.current = false; applyMesh(result);
      } else { relayDirty.current = false; setRelayDraft(relay); }
      writeStored("usblink.relay", relay);
      setSavedRelay(relay);
      notify(mesh.configured ? "中继地址已更新" : "默认中继地址已保存");
    } catch (error) { setRelayProblem(String(error)); }
    finally { endOperation(); }
  };

  const share = async (busIds) => {
    busIds = busIds.filter(id => devices.some(device => device.busId === id && !device.shared));
    if (!busIds.length || localProblem || !mesh.running || !meshServiceReady) return; if (!beginOperation("share")) return;
    setActiveBusIds(busIds);
    try { await backend("share_devices", { busIds }); if (!nativeApp) setDevices((old) => old.map((item) => busIds.includes(item.busId) ? { ...item, shared: true } : item)); notify(`已共享 ${busIds.length} 个 USB 设备`); setSelected(new Set()); }
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
  const attach = async (busIds = [...remoteSelected]) => {
    if (!mesh.running || !meshServiceReady || !environment.usbipSafe || !canUsePeer(selectedPeer) || !busIds.length || usbOperation.current || !attached.ready || attached.problem) return;
    const host = selectedPeer.ip;
    const chosen = remoteRows.filter((item) => busIds.includes(item.busId) && !item.attached);
    if (!chosen.length || remoteProblem) return;
    if (!beginOperation("attach")) return;
    setActiveBusIds(chosen.map(item => item.busId));
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
  const stopDevice = async (device) => {
    if (usbOperation.current || !beginOperation("detach")) return;
    window.clearTimeout(toastTimer.current); setToast(null);
    usbOperation.current = true; attached.pause(); setDetachingDevice(device);
    let confirmed = false;
    try {
      const target = { host: device.host, busId: device.busId, vidPid: device.vidPid, port: device.port };
      const result = await backend("detach_device", { target }, () => {
        previewAttached.current = previewAttached.current.filter(item => !(item.port === target.port && sameDevice(item, target)));
        return previewAttached.current;
      });
      if (!Array.isArray(result) || result.some(item => sameDevice(item, device))) throw new Error("未收到设备断开的确认结果，请刷新连接状态");
      attached.accept(resolveAttachedNames(result)); confirmed = true;
      if (!result.length) removeStored("usblink.lastConnection");
      notify(`已断开 ${device.name}`);
    } catch (error) { notify(error instanceof Error ? error.message : String(error), "error"); }
    finally {
      attached.resume();
      if (!confirmed) await attached.refresh(true);
      usbOperation.current = false; setDetachingDevice(null); endOperation();
      refreshRemote(false, true); refreshMesh();
    }
  };
  const stopAll = async () => {
    if (usbOperation.current) return;
    if (!beginOperation("detach")) return;
    window.clearTimeout(toastTimer.current); setToast(null);
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
    if (downloadPending.current) return;
    downloadPending.current = true; setOpeningDownload(kind);
    const needsInstall = kind === "usbipd" ? !environment.usbipdInstalled : kind === "usbip" && (!environment.usbipInstalled || !environment.usbipSafe);
    try {
      await backend("open_dependency_download", { kind });
      if (needsInstall) setInstallingComponents(old => [...new Set([...old, kind])]);
    }
    catch (error) { notify(String(error), "error"); }
    finally { downloadPending.current = false; setOpeningDownload(""); }
  };
  const checkComponents = async () => {
    if (checkingComponents) return;
    setCheckingComponents(true);
    try { if (await refreshEnvironment()) notify("组件状态已刷新"); }
    finally { setCheckingComponents(false); }
  };
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
        <button aria-current={page === "devices" ? "page" : undefined} aria-label="设备" title="设备" className={page === "devices" ? "active" : ""} onClick={() => setPage("devices")}><UsbPlug20Regular /><span>设备</span></button>
        <button aria-current={page === "connections" ? "page" : undefined} aria-label="连接" title="连接" className={page === "connections" ? "active" : ""} onClick={() => setPage("connections")}><ArrowSync20Regular /><span>连接</span></button>
        <button aria-current={page === "settings" ? "page" : undefined} aria-label="设置" title="设置" className={page === "settings" ? "active" : ""} onClick={() => setPage("settings")}><Settings20Regular /><span>设置</span></button>
      </nav>
      <div className="network-state"><StatusDot online={displayedMesh.running} /><div><strong>{displayedMesh.problem ? "连接异常" : displayedMesh.running ? "加密网络" : mesh.configured ? "正在连接" : "尚未配对"}</strong><span>{mesh.localIp || "无需注册账号"}</span></div></div>
    </aside>
    <main className="workspace">
      {!sharingSession.ready && <p className="usb-status-warning sharing-session-status" role="status"><ErrorCircle20Regular />{sharingSession.problem || (sharingSession.phase === "closing" ? "正在停止共享并断开 USB，完成后退出…" : "正在清理上次遗留的 USB 共享和挂载，请稍候…")}{sharingSession.phase === "failed" && <button className="text-action" disabled={!!busy} onClick={retrySharingCleanup}>重新清理 USB</button>}</p>}
      {page === "devices" && <DevicePage activeBusIds={activeBusIds} category={deviceCategory} setCategory={setDeviceCategory} openingDownload={openingDownload} environment={environment} mesh={displayedMesh} localProblem={localProblem} sharingProblem={sharingProblem} devices={devices} selected={selected} selectedPeer={selectedPeer} peerMenu={peerMenu} setPeerMenu={setPeerMenu} peers={peers} setSelectedPeerIp={setSelectedPeerIp} toggle={toggle} setSelected={setSelected} busy={effectiveBusy} localLoading={localLoading} refresh={() => refreshLocal()} share={share} repairSharing={repairSharing} unshare={unshare} openDownload={openDownload} setPage={setPage} />}
      {page === "connections" && <ConnectionPage activeBusIds={activeBusIds} joinProblem={joinProblem} hidePairingCode={() => setPairingCode("")} openingDownload={openingDownload} environment={environment} mesh={displayedMesh} createNetwork={createNetwork} joinNetwork={joinNetwork} joinCode={joinCode} setJoinCode={value => { setJoinCode(value); setJoinProblem(""); }} pairingCode={pairingCode} revealAndCopyCode={revealAndCopyCode} restartNetwork={restartNetwork} repairNetwork={repairNetwork} leaveNetwork={() => setConfirmLeave(true)} peers={peers} selectedPeer={selectedPeer} setSelectedPeerIp={setSelectedPeerIp} peerMenu={peerMenu} setPeerMenu={setPeerMenu} devices={remoteRows} attached={attached} remoteLoading={remoteLoading} remoteProblem={remoteProblem} selected={remoteSelected} setSelected={setRemoteSelected} toggle={(id) => toggle(id, true)} busy={effectiveBusy || (!meshReady ? "initializing" : "")} refresh={() => { refreshMesh(); refreshRemote(true); attached.refresh(); }} attach={attach} stopAll={stopAll} stopDevice={stopDevice} detachingDevice={detachingDevice} openDownload={openDownload} />}
      {page === "settings" && <SettingsPage environment={environment} mesh={mesh} prefs={prefs} savingPreference={savingPreference} updatePreference={updatePreference} openDownload={openDownload} installingComponents={installingComponents} openingDownload={openingDownload} cancelWaiting={kind => setInstallingComponents(old => old.filter(item => item !== kind))} savedRelay={savedRelay} relayProblem={relayProblem} checkingComponents={checkingComponents} checkComponents={checkComponents} relayDraft={relayDraft} setRelayDraft={(value) => { relayDirty.current = value.trim() !== savedRelay; setRelayDraft(value); setRelayProblem(""); }} saveRelay={saveRelay} busy={effectiveBusy} />}
    </main>
    {confirmLeave && <LeaveDialog busy={effectiveBusy} onCancel={() => setConfirmLeave(false)} onConfirm={leaveNetwork} />}
    {toast && <div className={`toast ${toast.type}`} role={toast.type === "error" ? "alert" : "status"} aria-live={toast.type === "error" ? "assertive" : "polite"}><span>{toast.type === "error" ? <ErrorCircle20Regular /> : <Checkmark16Regular />}</span><p>{toast.message}</p><button className="icon-button" aria-label="关闭通知" onClick={() => setToast(null)}><Dismiss16Regular /></button></div>}
  </div>;
}

function PageHeader({ title, subtitle, icon: Icon, onRefresh, spinning, disabled = false, tone = "normal" }) {
  return <header className="page-header"><div><h1>{title}</h1><p className={tone}><Icon />{subtitle}</p></div>{onRefresh && <button className="secondary-button" aria-label="刷新" aria-busy={!!spinning} disabled={!!spinning || disabled} onClick={onRefresh}><ArrowClockwise20Regular className={spinning ? "spin" : ""} />{spinning ? "刷新中…" : "刷新"}</button>}</header>;
}
function PeerPicker({ selectedPeer, peers, open, setOpen, select, compact = false, disabled = false }) {
  const root = useRef(null);
  const trigger = useRef(null);
  const menuId = useId();
  useEffect(() => {
    if (!open) return;
    if (disabled) { setOpen(false); return; }
    const closeOutside = event => { if (!root.current?.contains(event.target)) setOpen(false); };
    document.addEventListener("pointerdown", closeOutside);
    const selected = root.current?.querySelector('[role="option"][aria-selected="true"]');
    (selected || root.current?.querySelector('[role="option"]'))?.focus();
    return () => document.removeEventListener("pointerdown", closeOutside);
  }, [open, disabled, setOpen]);
  const close = () => { setOpen(false); trigger.current?.focus(); };
  const navigate = event => {
    if (event.key === "Escape") { event.preventDefault(); close(); return; }
    if (event.key === "Tab" && open) { setOpen(false); trigger.current?.focus(); return; }
    const options = [...root.current.querySelectorAll('[role="option"]')];
    const index = options.indexOf(document.activeElement);
    const next = { ArrowDown: (index + 1) % options.length, ArrowUp: (index - 1 + options.length) % options.length, Home: 0, End: options.length - 1 }[event.key];
    if (next !== undefined) { event.preventDefault(); if (!open) setOpen(true); else options[next]?.focus(); }
  };
  return <div ref={root} onKeyDown={navigate} className={`peer-wrap ${compact ? "compact" : ""}`}>
    <button ref={trigger} className="peer-picker" disabled={disabled} aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? menuId : undefined} onClick={() => setOpen(!open)}>
      {selectedPeer?.os === 'android' ? <Phone20Regular /> : <Desktop20Regular />}<div><strong title={selectedPeer?.name}>{selectedPeer?.name || "选择设备"}</strong><span>{selectedPeer ? `${selectedPeer.ip} · ${peerStatusLabel(selectedPeer)}` : "等待对方加入"}</span></div><StatusDot online={!!selectedPeer?.online} /><ChevronDown16Regular />
    </button>
    {open && <div id={menuId} className="peer-menu" role="listbox" aria-label="远程设备">{peers.length ? peers.map(peer => <button role="option" tabIndex={-1} aria-selected={peer.ip === selectedPeer?.ip} key={peer.ip} onClick={() => { select(peer.ip); close(); }}><StatusDot online={!!peer.online} /><span><strong>{peer.name}</strong><small>{peer.ip} · {peerStatusLabel(peer)}</small></span>{peer.ip === selectedPeer?.ip && <Checkmark16Regular />}</button>) : <p>还没有发现其他 USBLink 设备</p>}</div>}
  </div>;
}

function LeaveDialog({ busy, onCancel, onConfirm }) {
  const dialog = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    dialog.current.showModal();
    return () => { dialog.current?.close(); previous?.focus(); };
  }, []);
  return <dialog ref={dialog} className="confirm-dialog" aria-labelledby="leave-title" aria-describedby="leave-description" onCancel={event => { event.preventDefault(); onCancel(); }}>
    <h2 id="leave-title">离开当前连接？</h2><p id="leave-description">将删除本机保存的配对信息，再次加入需要对方的配对码。已有 USB 挂载仍可在连接页断开。</p>
    <footer><button autoFocus className="secondary-button" onClick={onCancel}>取消</button><button className="primary-button small" disabled={!!busy} onClick={onConfirm}>确认离开</button></footer>
  </dialog>;
}

function DevicePage({ activeBusIds, category, setCategory, openingDownload, environment, mesh, devices, selected, selectedPeer, peerMenu, setPeerMenu, peers, setSelectedPeerIp, toggle, setSelected, busy, localLoading, localProblem, sharingProblem, refresh, share, repairSharing, unshare, openDownload, setPage }) {
  const phones = devices.filter(isPhoneDevice);
  const categories = [
    { id: "all", label: "全部", devices },
    { id: "phones", label: "手机", devices: phones },
    { id: "other", label: "其他设备", devices: devices.filter(device => !isPhoneDevice(device)) },
  ];
  const current = categories.find(item => item.id === category);
  const available = current.devices.filter(device => !device.shared);
  const visibleSelected = available.filter(device => selected.has(device.busId)).map(device => device.busId);
  const all = available.length > 0 && visibleSelected.length === available.length;
  const changeCategory = (id) => { if (busy || id === category) return; setCategory(id); setSelected(new Set()); };
  const navigateCategory = (event, index) => {
    const next = { ArrowRight: (index + 1) % 3, ArrowLeft: (index + 2) % 3, Home: 0, End: 2 }[event.key];
    if (next === undefined || busy) return;
    event.preventDefault();
    changeCategory(categories[next].id);
    event.currentTarget.parentElement.querySelectorAll('[role="tab"]')[next].focus();
  };
  const hasShared = devices.some((device) => device.shared);
  return <><PageHeader title="设备" subtitle="共享仅在本次运行期间有效，退出时自动停止" icon={ShieldCheckmark20Filled} onRefresh={refresh} spinning={localLoading} />
    {mesh.problem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />{mesh.problem}<button className="text-action" onClick={() => setPage("connections")}>前往连接</button></p>}
    {localProblem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />本机设备状态待确认，请刷新后重试：{localProblem}</p>}
    {sharingProblem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />{sharingProblem}</p>}
    <section className="connection-overview"><div className="machine"><span>本机</span><div><Desktop20Regular /><p><strong>{environment.computerName}</strong><small>{mesh.localIp || "尚未加入加密网络"}</small></p></div></div><div className={`connection-line ${selectedPeer?.online ? "connected" : ""}`}><i /><b><LockClosed16Filled /></b><i /></div><div className="machine remote"><span>远程电脑</span><PeerPicker compact disabled={!!busy} selectedPeer={selectedPeer} peers={peers} open={peerMenu} setOpen={setPeerMenu} select={setSelectedPeerIp} /></div></section>
    {!mesh.configured ? <DependencyEmpty icon={ArrowRight20Regular} title="先连接另一台电脑" text="无需账号，在“连接”页面创建配对码或输入对方的配对码。" action="前往连接" onClick={() => setPage("connections")} /> : !environment.usbipdInstalled ? <DependencyEmpty title="USB 共享服务尚未安装" text="安装免费的 usbipd-win 后即可共享本机 USB 设备。" action={openingDownload === "usbipd" ? "正在打开…" : "打开安装页面"} disabled={!!openingDownload} onClick={() => openDownload("usbipd")} /> : <section className="device-categories">
      <div className="device-tabs" role="tablist" aria-label="USB 设备分类">{categories.map(item => <button key={item.id} id={`device-tab-${item.id}`} type="button" role="tab" aria-selected={category === item.id} aria-controls="device-category-panel" tabIndex={category === item.id ? 0 : -1} disabled={!!busy} onClick={() => changeCategory(item.id)} onKeyDown={event => navigateCategory(event, categories.indexOf(item))}>{item.label}<span className="badge">{item.devices.length}</span></button>)}</div>
      <div className="device-category-panel" id="device-category-panel" role="tabpanel" aria-labelledby={`device-tab-${category}`}>
        <DeviceTable activeBusIds={activeBusIds} title="可共享的 USB 设备" devices={current.devices} selected={selected} all={all} onAll={() => setSelected(all ? new Set() : new Set(available.map(item => item.busId)))} toggle={toggle} busy={busy} loading={localLoading} share={share} unshare={unshare} unavailable={!!localProblem || !mesh.running} stopUnavailable={!!localProblem} connectionKnown={!localProblem} emptyTitle={category === "all" ? undefined : category === "phones" ? "暂未识别到手机" : "没有发现其他 USB 设备"} />
      </div>
    </section>}
    <footer className="command-bar"><div><strong>{busy === "share" ? `正在共享 ${activeBusIds.length} 个设备` : !mesh.running ? "等待加密网络连接" : visibleSelected.length ? `已选择 ${visibleSelected.length} 个设备` : "选择需要共享的设备"}</strong><span>{localProblem ? "请先刷新，确认本机设备状态" : mesh.running ? "只选择尚未共享的设备，数量不限" : "创建或加入连接后即可共享"}</span></div>{visibleSelected.length > 0 && <button className="text-action" disabled={!!busy} onClick={() => setSelected(new Set())}>清除选择</button>}{hasShared && <button className="secondary-button" disabled={!!busy} onClick={repairSharing}><ArrowClockwise20Regular />{busy === "share-repair" ? "正在修复…" : "修复共享"}</button>}<button className="primary-button" disabled={!mesh.running || !!localProblem || !environment.usbipdInstalled || !visibleSelected.length || !!busy} onClick={() => share(visibleSelected)}><Open20Regular />{busy === "share" ? "正在共享…" : "共享所选设备"}</button></footer>
  </>;
}

function ConnectionPage({ activeBusIds, joinProblem, hidePairingCode, openingDownload, environment, mesh, createNetwork, joinNetwork, joinCode, setJoinCode, pairingCode, revealAndCopyCode, restartNetwork, repairNetwork, leaveNetwork, peers, selectedPeer, setSelectedPeerIp, peerMenu, setPeerMenu, devices, attached, remoteLoading, remoteProblem, selected, setSelected, toggle, busy, refresh, attach, stopAll, stopDevice, detachingDevice, openDownload }) {
  const available = devices.filter((device) => !device.attached);
  const all = available.length > 0 && available.every((device) => selected.has(device.busId));
  const onlineCount = peers.filter(peer => peer.online).length;
  const usbPhone = selectedPeer?.os === "android" && selectedPeer?.usbKind === "android-adb-experimental";
  const androidPeer = selectedPeer?.os === "android" && !usbPhone;
  const mountsUnavailable = attached.devices.some(device => mountPresence(device, peers, mesh.running) !== "已连接");
  const imported = <AttachedDevices snapshot={attached} stopAll={stopAll} stopDevice={stopDevice} detachingDevice={detachingDevice} busy={busy} peers={peers} networkReady={mesh.running} />;
  if (!mesh.configured) return <><PageHeader title="连接" subtitle={mesh.problem || "无需注册账号，使用配对码连接两台电脑"} tone={mesh.problem ? "error" : "normal"} icon={NetworkCheck20Regular} onRefresh={refresh} disabled={!!busy} /><div className="connection-content unpaired-content">{imported}<PairingSetup joinProblem={joinProblem} createNetwork={createNetwork} joinNetwork={joinNetwork} joinCode={joinCode} setJoinCode={setJoinCode} busy={busy} /></div></>;
  return <><PageHeader title="连接" subtitle={mesh.problem || (mesh.running ? "EasyTier 加密网络已启动" : "EasyTier 网络正在启动")} tone={mesh.problem ? "error" : "normal"} icon={mesh.problem ? ErrorCircle20Regular : NetworkCheck20Regular} onRefresh={refresh} spinning={remoteLoading} disabled={!!busy} />
    <div className="connection-content">
    <section className={`mesh-summary ${mesh.problem ? "problem" : ""}`}><div><span className="mesh-icon">{mesh.problem ? <ErrorCircle20Regular /> : <NetworkCheck20Regular />}</span><p><strong>{mesh.problem ? "连接失败" : mesh.running ? onlineCount ? "对方 USBLink 在线" : "暂无在线 USBLink" : "等待网络服务"}</strong><small>{mesh.problem || (mesh.running && !onlineCount ? "配对信息已保存，等待对方打开 USBLink" : mesh.localIp) || "正在分配虚拟地址"}</small></p></div><div className="mesh-meta"><span>应用响应已验证</span><strong>{onlineCount} 台设备</strong></div><div className="mesh-actions">{mesh.needsRepair && <button className="primary-button compact" onClick={repairNetwork} disabled={!!busy}><ArrowClockwise20Regular />{busy === "mesh-repair" ? "正在修复…" : "修复连接"}</button>}<button className="secondary-button" onClick={revealAndCopyCode} disabled={!!busy}><Copy20Regular />{busy === "mesh-code" ? "正在复制…" : "复制配对码"}</button><button className="icon-button" title="重新启动网络" aria-label="重新启动网络" onClick={restartNetwork} disabled={!!busy}>{busy === "mesh-restart" ? <ArrowClockwise20Regular className="spin" /> : <Power20Regular />}</button><button className="danger-action" onClick={leaveNetwork} disabled={!!busy}>离开连接</button></div></section>
    {pairingCode && <div className="pairing-code-band"><Key20Regular /><input aria-label="当前配对码" readOnly value={pairingCode} /><button className="text-action" disabled={!!busy} onClick={revealAndCopyCode}><Copy20Regular />复制</button><button className="text-action" disabled={!!busy} onClick={hidePairingCode}>隐藏配对码</button></div>}
    {imported}
    <section className="remote-source"><span>设备来源</span><PeerPicker disabled={!!busy} selectedPeer={selectedPeer} peers={peers} open={peerMenu} setOpen={setPeerMenu} select={setSelectedPeerIp} /></section>
    {selectedPeer && !androidPeer && !canUsePeer(selectedPeer) && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />{selectedPeer.problem || (selectedPeer.online ? "USBLink 在线，但 USB 共享服务不可达" : "对方 USBLink 未运行或不可达，请双方更新并打开程序")}</p>}
    {usbPhone && <p className="usb-status-warning" role="status"><Phone20Regular />USB ADB 实验通道 · 连接后由电脑现有 ADB 识别，首次需在手机授权。暂不支持 MTP 便携设备。</p>}
    {!androidPeer && remoteProblem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />远程设备列表暂时无法更新：{remoteProblem}</p>}
    {androidPeer ? <AndroidPeerPanel peer={selectedPeer} networkReady={mesh.running} /> : !environment.usbipInstalled ? <DependencyEmpty title="远程 USB 驱动尚未安装" text="安装免费的 usbip-win2 后即可连接远程 USB。" action={openingDownload === "usbip" ? "正在打开…" : "打开安装页面"} disabled={!!openingDownload} onClick={() => openDownload("usbip")} /> : !environment.usbipSafe ? <DependencyEmpty icon={ErrorCircle20Regular} title="远程 USB 驱动必须更新" text={environment.usbipProblem || "当前驱动存在系统崩溃风险，USBLink 已阻止连接。"} action={openingDownload === "usbip" ? "正在打开…" : "更新到 0.9.8.0"} disabled={!!openingDownload} onClick={() => openDownload("usbip")} /> : !selectedPeer ? <DependencyEmpty icon={Copy20Regular} title="等待另一台电脑加入" text="复制配对码发给对方，对方加入后会自动出现在这里。" action={busy === "mesh-code" ? "正在复制…" : "复制配对码"} disabled={!!busy} onClick={revealAndCopyCode} /> : <DeviceTable activeBusIds={activeBusIds} title="远程可用设备" remote devices={devices} selected={selected} all={all} onAll={() => setSelected(all ? new Set() : new Set(available.map((item) => item.busId)))} toggle={toggle} busy={busy} loading={remoteLoading} attach={attach} stopDevice={stopDevice} mounts={attached.devices} host={selectedPeer?.ip} detachingDevice={detachingDevice} unavailable={!mesh.running || !canUsePeer(selectedPeer) || !!remoteProblem || !attached.ready || !!attached.problem} connectionKnown={attached.ready && !attached.problem && mesh.running && canUsePeer(selectedPeer)} peerProblem={!selectedPeer.online ? "对方 USBLink 未运行或不可达" : selectedPeer.usbReady === false ? "共享服务不可达" : ""} />}
    </div>
    {!androidPeer && <footer className="command-bar"><button className="text-action" onClick={stopAll} disabled={!!busy || (!attached.devices.length && !attached.problem)}>{busy === "detach" && !detachingDevice ? "正在断开全部…" : "断开全部 USB"}</button><div><strong>{busy === "attach" ? `正在连接 ${activeBusIds.length} 个设备` : selected.size ? `已选择 ${selected.size} 个设备` : attached.devices.length ? (attached.problem || mountsUnavailable ? "USB 连接状态待确认" : `已连接 ${attached.devices.length} 个远程 USB`) : "选择需要连接的设备"}</strong><span>{!environment.usbipSafe ? "请先安装或更新远程 USB 驱动" : !mesh.running ? "等待加密网络就绪" : selectedPeer && !canUsePeer(selectedPeer) ? "等待对方 USBLink 和共享服务就绪" : remoteProblem || attached.problem ? "请刷新并确认设备状态" : selectedPeer ? `来自 ${selectedPeer.name}` : "等待另一台电脑"}</span></div>{selected.size > 0 && <button className="text-action" disabled={!!busy} onClick={() => setSelected(new Set())}>清除选择</button>}<button className="primary-button" disabled={!mesh.running || !canUsePeer(selectedPeer) || !environment.usbipSafe || !attached.ready || !!attached.problem || !!remoteProblem || !selected.size || !selectedPeer || !!busy} onClick={() => attach()}><PlugConnected20Regular />{busy === "attach" ? "正在连接…" : "连接所选设备"}</button></footer>}
  </>;
}

function AndroidPeerPanel({ peer, networkReady }) {
  const command = networkReady ? adbConnectCommand(peer) : '';
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState('');
  useEffect(() => { setCopied(false); setCopyError(''); }, [command]);
  const ready = !!command;
  const title = !networkReady || !peer.online ? '手机状态未确认' : ready ? '手机 ADB 共享已就绪' : peer.adbState === 'off' ? '手机在线，共享已关闭' : peer.adbState === 'preparing' ? '手机在线，正在准备共享' : '手机在线，共享未就绪';
  return <section className="android-peer-panel" aria-label="手机调试共享"><div className="android-peer-heading"><Phone20Regular /><div><h2>{title}</h2><p>使用电脑现有的 ADB / Android Studio 调试这部手机。</p></div><StatusDot online={ready} /></div>
    {ready ? <><label htmlFor="phone-adb-command">电脑连接命令</label><div className="android-adb-command"><input id="phone-adb-command" readOnly value={command} /><button className="secondary-button" onClick={async () => { try { await copyText(command); setCopied(true); setCopyError(''); } catch { setCopyError('复制失败，请选中命令手动复制'); } }}>{copied ? <Checkmark16Regular /> : <Copy20Regular />}{copied ? '已复制' : '复制命令'}</button></div><p>执行后，在手机上确认电脑 RSA 授权。这里表示共享通道就绪，实际调试连接请在 ADB 或 Android Studio 中查看。</p></> : <p role="status">{!networkReady ? '本机网络未就绪，请先恢复加密连接。' : !peer.online ? '等待手机模块重新响应；仅加入设备网络不能证明共享服务在线。' : peer.adbState === 'off' ? '请在手机模块 WebUI 的设备页开启“共享本机调试”。' : peer.problem || '请在手机 WebUI 检查 USB 调试、共享开关与服务状态。'}</p>}
    {copyError && <p className="usb-status-warning" role="alert">{copyError}</p>}
    <p className="android-peer-note">手机模块目前提供本机 ADB 调试共享，未提供 USB/IP、外接 U 盘或外接手机共享，无需安装远程 USB 驱动。</p>
  </section>;
}

function PairingSetup({ createNetwork, joinNetwork, joinCode, setJoinCode, joinProblem, busy }) {
  return <section className="pairing-setup"><div><span className="pairing-icon"><Add20Regular /></span><h2>创建新连接</h2><p>生成一个加密配对码，发给另一台电脑即可。</p><button className="primary-button small" onClick={createNetwork} disabled={!!busy}>{busy === "mesh-create" ? "正在创建…" : "创建连接"}<ArrowRight20Regular /></button></div><i /><div><span className="pairing-icon"><Key20Regular /></span><h2>加入已有连接</h2><p>粘贴另一台电脑生成的 USBLink 配对码。</p>
    <form className="join-form" onSubmit={event => { event.preventDefault(); joinNetwork(); }}><div className="join-field"><input aria-label="配对码" aria-invalid={!!joinProblem} aria-describedby={joinProblem ? "join-problem" : "join-hint"} placeholder="粘贴配对码" autoComplete="off" spellCheck={false} disabled={!!busy} value={joinCode} onChange={event => setJoinCode(event.target.value)} /><button type="submit" aria-label="加入连接" title="加入连接" disabled={!joinCode.trim() || !!busy}>{busy === "mesh-join" ? <ArrowClockwise20Regular className="spin" /> : <ArrowRight20Regular />}</button></div>
    {joinProblem ? <p id="join-problem" className="field-error" role="alert">{joinProblem}</p> : <small id="join-hint" className="field-hint">{busy === "mesh-join" ? "正在验证配对码并加入…" : "粘贴后按 Enter 即可加入"}</small>}</form>
    </div></section>;
}

function DeviceTable({ activeBusIds, title, devices, selected, all, onAll, toggle, busy, loading, share, unshare, attach, stopDevice, mounts = [], host, detachingDevice, remote = false, unavailable = false, stopUnavailable = unavailable, connectionKnown = true, peerProblem = "", emptyTitle }) {
  const available = devices.filter(device => remote ? !device.attached : !device.shared);
  const selectedCount = available.filter(device => selected.has(device.busId)).length;
  return <section className="device-panel" aria-label={title} aria-busy={!!loading}>
    <div className="list-head"><div><Check checked={all} mixed={!all && selectedCount > 0} onChange={onAll} label="全选" disabled={unavailable || !!busy || !available.length} /><strong>{title}</strong><span className="badge">{devices.length}</span></div><span>状态</span><span>设备编号</span><span>操作</span></div>
    <div className="device-list">{devices.length ? devices.map(device => {
      const Icon = deviceIcon(device);
      const confirmedAttached = device.attached && connectionKnown;
      const unconfirmed = unavailable && !confirmedAttached;
      const selectable = !unavailable && !busy && (remote ? !device.attached : !device.shared);
      const mount = remote && mounts.find(item => sameDevice(item, { ...device, host }));
      const disconnecting = !!mount && !!detachingDevice && mount.port === detachingDevice.port && sameDevice(mount, detachingDevice);
      const stopping = !remote && busy === device.busId;
      const starting = activeBusIds.includes(device.busId) && busy === (remote ? "attach" : "share");
      return <div key={device.busId} aria-busy={starting || disconnecting || stopping} className={`device-row ${selected.has(device.busId) && (remote ? !device.attached : !device.shared) ? "selected" : ""} ${selectable ? "selectable" : ""}`} onClick={event => { if (selectable && !event.target.closest('button, a, input')) toggle(device.busId); }}>
        <div className="device-main"><Check checked={selected.has(device.busId) && (remote ? !device.attached : !device.shared)} onChange={() => toggle(device.busId)} label={`选择 ${device.name}`} disabled={!selectable} /><span className="device-icon"><Icon /></span><p><strong title={device.name}>{device.name}</strong><small title={device.detail || device.vidPid}>{device.detail || device.vidPid}</small></p></div>
        <div className={`device-status ${unconfirmed ? "unconfirmed" : ""}`}><StatusDot online={!unconfirmed} /><span>{starting ? remote ? "正在连接…" : "正在共享…" : disconnecting ? "正在断开…" : stopping ? "正在停止共享…" : remote ? peerProblem || (confirmedAttached ? "已连接" : unavailable ? "状态待确认" : "可连接") : stopUnavailable ? "状态待确认" : device.attached ? "正在被使用" : device.shared ? "已共享" : "可共享"}</span></div>
        <div className="device-id"><strong>{device.busId}</strong><span>{device.vidPid}</span></div>
        <div className="row-action">{remote ? mount ? <button className="text-action" disabled={!!busy} aria-label={`断开 ${device.name}`} onClick={() => stopDevice(mount)}>{disconnecting ? "正在断开…" : "断开"}</button> : <button className="text-action" disabled={!selectable} aria-label={`连接 ${device.name}`} onClick={() => attach([device.busId])}>{busy === "attach" && activeBusIds.includes(device.busId) ? "正在连接…" : "连接"}</button> : device.shared ? <button className="danger-action" disabled={stopUnavailable || !!busy} onClick={() => unshare(device.busId)}>{stopping ? "正在停止…" : "停止共享"}</button> : <button className="text-action" disabled={!selectable} aria-label={`共享 ${device.name}`} onClick={() => share([device.busId])}>{busy === "share" && activeBusIds.includes(device.busId) ? "正在共享…" : "共享"}</button>}</div>
      </div>;
    }) : <div className="empty-list">{loading ? <ArrowClockwise20Regular className="spin" /> : <UsbPlug20Regular />}<strong>{loading ? "正在读取设备…" : peerProblem || (unavailable ? "设备状态待确认" : emptyTitle || (remote ? "没有发现远程 USB 设备" : "没有发现 USB 设备"))}</strong><span>{loading ? "设备列表就绪后会显示在这里" : peerProblem ? "对方打开 USBLink 并共享后会自动更新" : unavailable ? "请检查上方提示，恢复后刷新" : remote ? "确认远程电脑已经共享设备" : "插入设备后点击刷新"}</span></div>}</div>
  </section>;
}

function AttachedDevices({ snapshot, stopAll, stopDevice, detachingDevice, busy, peers, networkReady }) {
  if (!snapshot.devices.length && !snapshot.problem) return null;
  return <section className="attached-devices" aria-label="已连接到本机的 USB">
    <header><h2>{snapshot.problem ? "USB 连接状态待确认" : "已连接到本机"}<span className="badge">{snapshot.devices.length}</span></h2><button className="text-action" onClick={stopAll} disabled={!!busy}><PlugDisconnected20Regular />{busy === "detach" && !detachingDevice ? "正在断开全部…" : "断开全部 USB"}</button></header>
    {snapshot.problem && <p className="usb-status-warning" role="status"><ErrorCircle20Regular />无法读取挂载状态，以下为上次结果：{snapshot.problem}</p>}
    {snapshot.devices.map((device) => { const Icon = deviceIcon(device); const disconnecting = !!detachingDevice && detachingDevice.port === device.port && sameDevice(detachingDevice, device); const presence = disconnecting ? "正在断开…" : snapshot.problem ? "状态待确认" : mountPresence(device, peers, networkReady); return <div className="attached-row" key={`${device.host}|${device.port}`} aria-busy={disconnecting}>
      <span className="device-icon"><Icon /></span><p><strong>{device.name}</strong><small>{device.host} · {device.busId} · 端口 {device.port}</small></p>
      <span className={`device-status ${presence !== "已连接" ? "unconfirmed" : ""}`}><StatusDot online={presence === "已连接"} />{presence}</span>
      <button className="text-action disconnect-action" disabled={!!busy} onClick={() => stopDevice(device)} aria-label={`断开 ${device.name}（${device.host}，端口 ${device.port}）`}>{disconnecting ? <ArrowClockwise20Regular className="spin" /> : <PlugDisconnected20Regular />}{disconnecting ? "正在断开…" : "断开"}</button>
    </div>; })}
  </section>;
}

function DependencyEmpty({ icon: Icon = Open20Regular, title, text, action, onClick, disabled = false }) { return <section className="dependency-empty"><span><PlugDisconnected20Regular /></span><h2>{title}</h2><p>{text}</p><button className="primary-button small" disabled={disabled} onClick={onClick}><Icon />{action}</button></section>; }
function SettingsPage({ environment, prefs, savingPreference, updatePreference, openDownload, installingComponents, openingDownload, cancelWaiting, checkingComponents, checkComponents, relayDraft, setRelayDraft, savedRelay, relayProblem, saveRelay, busy }) {
  const dirty = relayDraft.trim() !== savedRelay;
  return <><PageHeader title="设置" subtitle="连接方式与运行偏好" icon={Settings20Regular} onRefresh={checkComponents} spinning={checkingComponents} /><div className="settings-layout">
    <section><h2>运行偏好</h2><Preference label="自动刷新设备" text="定时更新本机和远程 USB 状态" checked={prefs.autoRefresh} change={value => updatePreference("autoRefresh", value)} /><Preference label="开机启动 USBLink" text={savingPreference ? "正在保存 Windows 启动设置…" : "登录 Windows 后自动打开管理界面"} checked={prefs.autoStart} disabled={savingPreference} change={value => updatePreference("autoStart", value)} /></section>
    <section><h2>网络</h2><form className="relay-setting" onSubmit={event => { event.preventDefault(); saveRelay(); }}><p><strong>EasyTier 中继节点</strong><span>直连失败时使用；配对码会把这个地址带给另一台电脑。</span></p><div><input aria-label="EasyTier 中继节点" aria-invalid={!!relayProblem} aria-describedby="relay-feedback" autoComplete="off" spellCheck={false} disabled={!!busy} value={relayDraft} onChange={event => setRelayDraft(event.target.value)} /><button type="submit" className="secondary-button" disabled={!!busy || !dirty}>{busy === "mesh-relay" ? "正在保存…" : "保存"}</button></div>
    <div className="field-feedback"><span id="relay-feedback" className={relayProblem ? "field-error" : "field-hint"} role={relayProblem ? "alert" : undefined}>{relayProblem || (busy === "mesh-relay" ? "正在应用中继地址，请稍候" : dirty ? "有未保存的修改，按 Enter 保存" : "当前地址已保存")}</span>{dirty && <button type="button" className="text-action" disabled={!!busy} onClick={() => setRelayDraft(savedRelay)}>撤销修改</button>}</div></form></section>
    <section><h2>组件状态</h2><p className="component-help">EasyTier 已内置；USB 驱动安装完成后会自动刷新。</p>
      <Dependency name="EasyTier" ready info={`已内置 ${environment.easytierVersion}`} opening={openingDownload === "easytier"} disabled={!!openingDownload} open={() => openDownload("easytier")} />
      <Dependency name="usbipd-win" ready={environment.usbipdInstalled} pending={installingComponents.includes("usbipd")} info={environment.usbipdVersion || "共享服务"} opening={openingDownload === "usbipd"} disabled={!!openingDownload} open={() => openDownload("usbipd")} cancelWaiting={() => cancelWaiting("usbipd")} />
      <Dependency name="usbip-win2" ready={environment.usbipInstalled && environment.usbipSafe} warning={environment.usbipInstalled && !environment.usbipSafe} pending={installingComponents.includes("usbip")} info={environment.usbipProblem || environment.usbipVersion || "远程驱动"} opening={openingDownload === "usbip"} disabled={!!openingDownload} open={() => openDownload("usbip")} cancelWaiting={() => cancelWaiting("usbip")} />
    </section><section><h2>安全</h2><div className="security-note"><ShieldCheckmark20Filled /><p><strong>USB 数据通过 EasyTier 加密传输</strong><span>无需账号；配对信息由 Windows DPAPI 加密保存。社区节点只负责发现或转发，不能读取 USB 内容。</span></p></div></section>
  </div></>;
}
function Preference({ label, text, checked, change, disabled = false }) { return <div className="preference"><p><strong>{label}</strong><span>{text}</span></p><button className={`toggle ${checked ? "on" : ""}`} role="switch" aria-label={label} disabled={disabled} aria-checked={checked} aria-busy={disabled} onClick={() => change(!checked)}><i /></button></div>; }
function Dependency({ name, ready, warning, pending, info, open, opening, disabled, cancelWaiting }) {
  return <div className="dependency"><span className={pending ? "checking" : warning ? "warning" : ready ? "ready" : ""}>{pending ? <ArrowClockwise20Regular className="spin" /> : warning ? <ErrorCircle20Regular /> : ready ? <Checkmark16Regular /> : <PlugDisconnected20Regular />}</span><p><strong>{name}</strong><small>{pending ? "等待安装完成…" : warning || ready ? info : "尚未安装"}</small></p>{pending && <button className="text-action" disabled={disabled} title="收起等待提示，稍后仍会自动检测组件" onClick={cancelWaiting}>暂不安装</button>}<button className="text-action" disabled={disabled} onClick={open}>{opening ? "正在打开…" : pending ? "重新打开" : warning ? "更新" : ready ? "查看" : "安装"}<Open20Regular /></button></div>;
}
