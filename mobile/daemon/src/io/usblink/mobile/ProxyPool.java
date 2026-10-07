package io.usblink.mobile;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Opaque TCP transport: Android RSA authentication remains between desktop adb and system adbd. */
public final class ProxyPool implements SharingTransport {
    private final Map<String,Listener> listeners = new HashMap<>();
    private final Map<String,Session> sessions = new HashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 24, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), runnable -> { Thread t = new Thread(runnable, "usblink-transfer"); t.setDaemon(true); return t; });
    private String lastProblem;
    private boolean closed;

    public synchronized void configure(String kind, String bindIp, int bindPort, int targetPort) throws IOException {
        if (closed) throw new IOException("转发服务已关闭");
        if (!kind.equals("adb")) throw new IOException("不支持的通道类型");
        Listener old = listeners.get(kind);
        if (old != null && old.ip.equals(bindIp) && old.port == bindPort && old.target == targetPort && !old.socket.isClosed()) return;
        stop(kind);
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(bindIp), bindPort), 8);
            Listener listener = new Listener(kind, bindIp, bindPort, targetPort, socket);
            listeners.put(kind, listener);
            Thread accept = new Thread(() -> accept(listener), "usblink-" + kind);
            accept.setDaemon(true); accept.start(); lastProblem = null;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket); throw new IOException("无法监听共享端口，请检查组网地址或端口占用", e);
        }
    }
    public synchronized boolean active(String kind) {
        Listener listener = listeners.get(kind);
        return listener != null && !listener.socket.isClosed();
    }
    public synchronized String problem() { return lastProblem; }
    public synchronized int boundPort(String kind) { Listener l = listeners.get(kind); return l == null ? 0 : l.socket.getLocalPort(); }
    public synchronized void stop(String kind) {
        Listener old = listeners.remove(kind);
        if (old != null) closeQuietly(old.socket);
        for (Session s : new ArrayList<>(sessions.values())) if (s.kind.equals(kind)) s.close();
    }
    public synchronized void stopAll() { stop("adb"); }
    public synchronized void disconnect(String id) throws IOException {
        Session session = sessions.get(id);
        if (session == null) throw new IOException("该连接已经结束，请刷新列表");
        session.close();
    }
    public synchronized List<Map<String,Object>> snapshot() {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Session s : sessions.values()) out.add(Json.map("id",s.id,"peer",s.client.getInetAddress().getHostAddress(),
            "kind",s.kind,"startedAt",s.startedAt,"bytes",s.bytes.get()));
        return out;
    }
    private void accept(Listener listener) {
        while (!listener.socket.isClosed()) {
            Socket client = null;
            try {
                client = listener.socket.accept();
                client.setTcpNoDelay(true);
                Session session;
                synchronized (this) {
                    if (closed || listeners.get(listener.kind) != listener || sessions.size() >= 8) { closeQuietly(client); continue; }
                    session = new Session(listener.kind, client);
                    sessions.put(session.id, session);
                }
                try { workers.execute(() -> connect(session, listener.target)); }
                catch (RuntimeException e) { session.close(); }
            } catch (IOException e) {
                closeQuietly(client);
                synchronized (this) {
                    if (listeners.get(listener.kind) == listener) {
                        lastProblem = "共享监听中断，请关闭后重新开启共享";
                        stop(listener.kind);
                    }
                }
                return;
            }
        }
    }
    private void connect(Session session, int targetPort) {
        try {
            // The target socket is registered before connect so disabling sharing cancels in-flight connects too.
            session.target.connect(new InetSocketAddress("127.0.0.1", targetPort), 3000);
            session.target.setTcpNoDelay(true);
            synchronized (this) { if (!sessions.containsKey(session.id)) { session.close(); return; } }
            workers.execute(() -> pump(session, session.client, session.target));
            workers.execute(() -> pump(session, session.target, session.client));
        } catch (IOException | RuntimeException e) { session.close(); }
    }
    private void pump(Session session, Socket from, Socket to) {
        try {
            InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream();
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); session.bytes.addAndGet(count); }
            // Preserve the response after a peer half-closes its sending side (including large transfers).
            to.shutdownOutput();
            if (session.finished.incrementAndGet() == 2) session.close();
        } catch (IOException | RuntimeException e) { session.close(); }
    }
    @Override public synchronized void close() { closed=true; stopAll(); workers.shutdownNow(); }
    private static void closeQuietly(Closeable value) { try { if(value != null) value.close(); } catch(IOException ignored) {} }
    private static final class Listener {
        final String kind, ip; final int port,target; final ServerSocket socket;
        Listener(String kind,String ip,int port,int target,ServerSocket socket) { this.kind=kind;this.ip=ip;this.port=port;this.target=target;this.socket=socket; }
    }
    private final class Session {
        final String id = UUID.randomUUID().toString(); final String kind;
        final Socket client,target = new Socket(); final long startedAt=System.currentTimeMillis();
        final AtomicInteger finished=new AtomicInteger(); final AtomicLong bytes=new AtomicLong();
        Session(String kind,Socket client) { this.kind=kind;this.client=client; }
        void close() { synchronized(ProxyPool.this) { closeQuietly(client); closeQuietly(target); sessions.remove(id); } }
    }
}
