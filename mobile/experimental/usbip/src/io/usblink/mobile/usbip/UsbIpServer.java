package io.usblink.mobile.usbip;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded userspace USB/IP v1.1.1 experiment: one ADB device, one manual import. */
public final class UsbIpServer implements Closeable {
    public interface Backend { Socket connect() throws IOException; }
    public enum EndReason {
        CLIENT_CLOSED("电脑侧 USB/IP 连接已结束，可能是断开操作或网络中断",false),
        CLIENT_IO("电脑侧 USB/IP 读写中断，请检查电脑与网络",false),
        ADB_CLOSED("手机系统 adbd 已结束调试连接",false),
        ADB_IO("手机系统 adbd 读写中断",false),
        INVALID_USB("收到不支持的 USB/IP 请求，会话已停止",false),
        INVALID_ADB("手机调试数据格式无效，会话已停止",false),
        QUEUE_LIMIT("USB 传输等待队列超过限制，会话已停止",false),
        NETWORK_UNAVAILABLE("手机无法确认设备网络，共享会话已撤销",false),
        NETWORK_PROCESS_EXITED("手机加密网络进程已退出，共享会话已撤销",false),
        NETWORK_INTERFACE_LOST("手机虚拟网络接口或地址已消失／重建，共享会话已撤销",false),
        NETWORK_ROUTE_UNAVAILABLE("手机网络路由无法确认或存在冲突，共享会话已撤销",false),
        NETWORK_ROUTE_QUERY_FAILED("连续两次读取手机路由或规则失败／超时，共享会话已撤销",false),
        NETWORK_ROUTE_MISSING("手机虚拟网络缺少匹配路由或规则，共享会话已撤销",false),
        NETWORK_ROUTE_CONFLICT("手机虚拟网络路由规则或所有权记录冲突，共享会话已撤销",false),
        NETWORK_STATUS_UNCONFIRMED("无法验证本机网络地址，共享会话已撤销",false),
        ADB_UNAVAILABLE("手机系统调试服务暂不可用，共享会话已撤销",false),
        SHARING_ERROR("共享检查失败，会话已撤销；请查看共享状态",false),
        LISTENER_CHANGED("共享监听地址或端口发生变化，旧会话已撤销",false),
        USER_DISCONNECT("已在手机断开此会话",true),
        ADB_ROOT_CHANGED("正在切换系统 adbd 权限，原会话已结束，请在电脑手动连接",true),
        SHARING_STOPPED("共享服务已停止",true);
        final String message;final boolean intentional;
        EndReason(String message,boolean intentional){this.message=message;this.intentional=intentional;}
    }
    private final AdbUsbDevice device;
    private final Backend backend;
    private final Predicate<InetAddress> allowedPeer;
    private final Consumer<String> log;
    private final Consumer<Map<String,Object>> onDisconnect;
    private final Function<String,Consumer<byte[]>> observeUpload;
    private final ServerSocket listener = new ServerSocket();
    private final Set<Socket> clients = new HashSet<>();
    private final Set<Socket> targets = new HashSet<>();
    private volatile Session session;
    private final AtomicBoolean imported = new AtomicBoolean();
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r,"usbip-probe-client"); t.setDaemon(true); return t;
    });
    private volatile boolean closed;
    private volatile EndReason closeReason=EndReason.SHARING_STOPPED;
    private volatile long firstImportNanos = -1;

    public UsbIpServer(InetAddress bind, int port, InetAddress peer, AdbUsbDevice device,
                       Backend backend, Consumer<String> log) throws IOException {
        this(bind,port,address->address.equals(peer),device,backend,log);
    }
    public UsbIpServer(InetAddress bind, int port, Predicate<InetAddress> peer, AdbUsbDevice device,
                       Backend backend, Consumer<String> log) throws IOException {
        this(bind,port,peer,device,backend,log,event->{});
    }
    public UsbIpServer(InetAddress bind, int port, Predicate<InetAddress> peer, AdbUsbDevice device,
                       Backend backend, Consumer<String> log, Consumer<Map<String,Object>> onDisconnect) throws IOException {
        this(bind,port,peer,device,backend,log,onDisconnect,address->bytes->{});
    }
    public UsbIpServer(InetAddress bind, int port, Predicate<InetAddress> peer, AdbUsbDevice device,
                       Backend backend, Consumer<String> log, Consumer<Map<String,Object>> onDisconnect,
                       Function<String,Consumer<byte[]>> observeUpload) throws IOException {
        this.device=device; this.backend=backend; this.allowedPeer=peer; this.log=log;this.onDisconnect=onDisconnect;this.observeUpload=observeUpload;
        try {
            // Devlist replies and revoked imports actively close accepted sockets. Linux
            // retains their local port in TIME_WAIT; allow the next share to bind it again.
            // This is not SO_REUSEPORT: an existing live listener must still reject a bind.
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(bind,port),4);
        } catch(IOException | RuntimeException e) {
            close(); // A failed constructor has no owner to release its socket/executor.
            if(e instanceof BindException)throw new IOException("USB 共享端口 TCP "+port+" 已被占用，请检查旧共享服务是否退出或有其他服务占用",e);
            if(e instanceof IOException)throw new IOException("USB 共享监听启动失败（TCP "+port+"），请检查虚拟网络地址和模块状态",e);
            throw e;
        }
        Thread accept = new Thread(this::accept,"usbip-probe-listener"); accept.setDaemon(true); accept.start();
    }
    public int port() { return listener.getLocalPort(); }
    public boolean active(){return !closed && !listener.isClosed();}
    public List<Map<String,Object>> sessions(){
        Session s=session;if(s==null || s.client.isClosed())return Collections.emptyList();
        Map<String,Object> value=new LinkedHashMap<>();value.put("id",s.id);value.put("peer",s.client.getInetAddress().getHostAddress());
        value.put("kind","usb-adb");value.put("startedAt",s.startedAt);value.put("bytes",s.bytes.get());return Collections.singletonList(value);
    }
    public void disconnect(String id)throws IOException {
        Session s=session;if(s==null || !s.id.equals(id))throw new IOException("该连接已经结束，请刷新列表");
        s.end(EndReason.USER_DISCONNECT);
    }
    public long firstImportNanos() { return firstImportNanos; }
    private void accept() {
        while (!closed) {
            try {
                Socket client=listener.accept();
                synchronized(clients) {
                    if(closed || !allowedPeer.test(client.getInetAddress()) || clients.size()>=4) {client.close();continue;}
                    clients.add(client);
                }
                try {workers.execute(()->handle(client));}
                catch(RejectedExecutionException e){release(client);}
            } catch(IOException e){if(!closed)log.accept("listener failed");close();return;}
        }
    }
    private void release(Socket s){synchronized(clients){clients.remove(s);}quietClose(s);}
    private void handle(Socket socket) {
        boolean owns=false;
        try {
            socket.setTcpNoDelay(true);socket.setSoTimeout(5000);
            DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            int version=in.readUnsignedShort(),op=in.readUnsignedShort(),status=in.readInt();
            if(version!=0x111 || status!=0)throw new IOException("invalid negotiation");
            if(op==0x8005) {
                reply(out,5,0);out.writeInt(1);out.write(device.record());out.write(new byte[]{(byte)255,0x42,1,0});return;
            }
            if(op!=0x8003)throw new IOException("unknown operation");
            byte[] bus=new byte[32];in.readFully(bus);
            byte[] expected=new byte[32];byte[] name=AdbUsbDevice.BUS_ID.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name,0,expected,0,name.length);
            if(!Arrays.equals(bus,expected) || !(owns=imported.compareAndSet(false,true))) {reply(out,3,1);return;}
            device.reset();
            try(Socket target=backend.connect()) {
                synchronized(clients){if(closed)throw new IOException("sharing stopped");targets.add(target);}
                try {
                target.setTcpNoDelay(true);target.setSoTimeout(0);
                if(firstImportNanos<0)firstImportNanos=System.nanoTime();
                reply(out,3,0);out.write(device.record());socket.setSoTimeout(0);
                log.accept("import accepted");
                Session s=new Session(socket,target,in,out);session=s;if(closed)s.end(closeReason);
                try{s.run();}finally{session=null;}
                }finally{synchronized(clients){targets.remove(target);}}
            }
        } catch(IOException|RuntimeException e) {log.accept("session closed: "+e.getClass().getSimpleName());}
        finally {if(owns)imported.set(false);release(socket);}
    }
    private static void reply(DataOutputStream out,int op,int status)throws IOException {
        out.writeShort(0x111);out.writeShort(op);out.writeInt(status);
    }
    @Override public void close() {
        close(EndReason.SHARING_STOPPED);
    }
    public void close(EndReason reason) {
        closeReason=reason;closed=true;
        Session s=session;if(s!=null)s.end(reason);
        quietClose(listener);
        synchronized(clients){for(Socket client:clients)quietClose(client);for(Socket target:targets)quietClose(target);targets.clear();}
        workers.shutdownNow();
    }
    private static void quietClose(Closeable value){try{value.close();}catch(IOException ignored){}}

    private final class Session {
        final String id=UUID.randomUUID().toString();
        final long startedAt=System.currentTimeMillis();
        final AtomicLong bytes=new AtomicLong();
        private final Socket client,target;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final LinkedHashMap<Integer,Request> pending=new LinkedHashMap<>();
        private final LinkedHashMap<Integer,WriteRequest> outgoing=new LinkedHashMap<>();
        private int outgoingBytes;
        private final ArrayDeque<byte[]> chunks=new ArrayDeque<>();
        private int chunkOffset,queuedBytes;
        private volatile boolean stopped;
        private final AtomicReference<EndReason> reason=new AtomicReference<>();
        private Consumer<byte[]> uploadObserver;
        Session(Socket client,Socket target,DataInputStream input,DataOutputStream output) {
            this.client=client;this.target=target;this.input=input;this.output=output;
            try{uploadObserver=observeUpload.apply(client.getInetAddress().getHostAddress());}catch(RuntimeException ignored){uploadObserver=null;}
        }
        void run()throws IOException {
            Thread reader=new Thread(this::readAdb,"usbip-probe-adbd");reader.setDaemon(true);reader.start();
            Thread writer=new Thread(this::writeAdb,"usbip-probe-upload");writer.setDaemon(true);writer.start();
            try {
                while(true) {
                    byte[] header=new byte[48];input.readFully(header);
                    ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
                    int command=b.getInt(),seq=b.getInt(),id=b.getInt(),direction=b.getInt(),ep=b.getInt();
                    if(id!=AdbUsbDevice.DEVICE_ID || (direction!=0 && direction!=1))throw new ProtocolException("invalid request identity");
                    if(command==2) {if(ep!=0)throw new ProtocolException("invalid unlink");unlink(seq,b.getInt());continue;}
                    if(command!=1)throw new ProtocolException("invalid request command");
                    int flags=b.getInt(),length=b.getInt();b.getInt();int packets=b.getInt();b.getInt();
                    byte[] setup=new byte[8];b.get(setup);
                    if(length<0 || length>AdbUsbDevice.MAX_TRANSFER || (packets!=0 && packets!=-1))throw new ProtocolException("unsupported transfer");
                    byte[] data=new byte[direction==0?length:0];input.readFully(data);
                    Request request=new Request(seq,direction,length);
                    if(ep==0) {
                        if(((setup[0]&0x80)!=0)!=(direction==1) || data.length!=0 ||
                            length!=(ByteBuffer.wrap(setup).order(ByteOrder.LITTLE_ENDIAN).getShort(6)&65535)) {
                            complete(request,-32,new byte[0],0);continue;
                        }
                        byte[] answer=device.control(setup);
                        log.accept("control "+String.format("%02x/%02x/%02x%02x",setup[0]&255,setup[1]&255,setup[3]&255,setup[2]&255)+" -> "+(answer==null?"stall":answer.length));
                        complete(request,answer==null?-32:0,answer==null?new byte[0]:answer,answer==null?0:answer.length);
                    } else if(!device.configured())complete(request,-32,new byte[0],0);
                    else if(ep==2 && direction==0) {
                        // USB packet/ZLP boundaries disappear on the TCP stream. Bytes/auth are unmodified.
                        enqueueWrite(request,data);
                    } else if(ep==1 && direction==1) {
                        if(length==0){complete(request,0,new byte[0],0);continue;}
                        synchronized(this) {
                            if(pending.containsKey(seq) || outgoing.containsKey(seq))throw new ProtocolException("duplicate request");
                            if(pending.size()>=32){reason.compareAndSet(null,EndReason.QUEUE_LIMIT);throw new IOException("too many pending reads");}
                            pending.put(seq,request);drain();
                        }
                    } else complete(request,-32,new byte[0],0);
                }
            } catch(IOException e) {
                reason.compareAndSet(null,e instanceof ProtocolException?EndReason.INVALID_USB:e instanceof EOFException?EndReason.CLIENT_CLOSED:EndReason.CLIENT_IO);
                throw e;
            } finally {
                stopped=true;
                // Unblock socket writes before acquiring the queue/output lock.
                quietClose(target);quietClose(client);
                synchronized(this){notifyAll();}
                try{reader.join(1000);writer.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                EndReason ended=reason.get();if(ended==null)ended=EndReason.CLIENT_IO;
                Map<String,Object> event=new LinkedHashMap<>();event.put("at",System.currentTimeMillis());
                event.put("code",ended.name());event.put("message",ended.message);event.put("intentional",ended.intentional);event.put("bytes",bytes.get());
                onDisconnect.accept(Collections.unmodifiableMap(event));
            }
        }
        void end(EndReason why){reason.compareAndSet(null,why);quietClose(client);quietClose(target);}
        private synchronized void enqueueWrite(Request request,byte[] data)throws IOException {
            if(pending.containsKey(request.seq) || outgoing.containsKey(request.seq))throw new ProtocolException("duplicate request");
            if(outgoing.size()>=32 || outgoingBytes+data.length>4*AdbUsbDevice.MAX_TRANSFER) {
                reason.compareAndSet(null,EndReason.QUEUE_LIMIT);throw new IOException("too many pending writes");
            }
            outgoing.put(request.seq,new WriteRequest(request,data));outgoingBytes+=data.length;notifyAll();
        }
        private void writeAdb() {
            try {
                while(true) {
                    WriteRequest write;
                    synchronized(this) {
                        while(!stopped && outgoing.isEmpty())wait();
                        if(stopped)return;
                        write=outgoing.values().iterator().next();write.started=true;
                    }
                    // Preserve byte order with one writer, but never block IN/unlink dispatch
                    // while adbd applies upload backpressure. Complete only after the write.
                    target.getOutputStream().write(write.data);bytes.addAndGet(write.data.length);
                    try{if(uploadObserver!=null)uploadObserver.accept(write.data);}catch(RuntimeException ignored){uploadObserver=null;}
                    synchronized(this) {
                        outgoing.remove(write.request.seq);outgoingBytes-=write.data.length;
                        complete(write.request,0,new byte[0],write.data.length);
                    }
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();if(!stopped)end(EndReason.ADB_IO);}
            catch(IOException e){if(!stopped)end(EndReason.ADB_IO);}
        }
        private void readAdb() {
            try {
                DataInputStream source=new DataInputStream(target.getInputStream());
                while(true) {
                    byte[] header=new byte[24];source.readFully(header);
                    ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                    int command=b.getInt(0),length=b.getInt(12),magic=b.getInt(20);
                    if(magic!=(command^0xffffffff) || length<0 || length>AdbUsbDevice.MAX_TRANSFER)throw new ProtocolException("invalid ADB packet");
                    offer(header);
                    if(length>0){byte[] payload=new byte[length];source.readFully(payload);offer(payload);}
                }
            }catch(IOException e){if(!stopped)end(e instanceof ProtocolException?EndReason.INVALID_ADB:e instanceof EOFException?EndReason.ADB_CLOSED:EndReason.ADB_IO);}
        }
        private synchronized void offer(byte[] bytes)throws IOException {
            while(!stopped && queuedBytes+bytes.length>2*AdbUsbDevice.MAX_TRANSFER) {
                try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            }
            if(stopped)throw new EOFException();chunks.add(bytes);queuedBytes+=bytes.length;drain();
        }
        private void drain()throws IOException {
            while(!pending.isEmpty() && !chunks.isEmpty()) {
                Request r=pending.values().iterator().next();pending.remove(r.seq);
                byte[] data=chunks.peek();int length=Math.min(r.length,data.length-chunkOffset);
                byte[] result=Arrays.copyOfRange(data,chunkOffset,chunkOffset+length);
                chunkOffset+=length;queuedBytes-=length;
                if(chunkOffset==data.length){chunks.remove();chunkOffset=0;}
                complete(r,0,result,length);notifyAll();
            }
        }
        private synchronized void unlink(int seq,int targetSeq)throws IOException {
            boolean removed=pending.remove(targetSeq)!=null;
            WriteRequest write=outgoing.get(targetSeq);
            if(write!=null && !write.started){outgoing.remove(targetSeq);outgoingBytes-=write.data.length;removed=true;}
            ByteBuffer b=ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
            b.putInt(4).putInt(seq).putInt(0).putInt(0).putInt(0).putInt(removed?-104:0);
            try{output.write(b.array());}catch(IOException e){reason.compareAndSet(null,EndReason.CLIENT_IO);throw e;}
        }
        private synchronized void complete(Request r,int status,byte[] data,int length)throws IOException {
            ByteBuffer b=ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
            b.putInt(3).putInt(r.seq).putInt(0).putInt(0).putInt(0).putInt(status).putInt(length).putInt(0).putInt(0).putInt(0);
            try{output.write(b.array());if(r.direction==1){output.write(data);bytes.addAndGet(data.length);}}
            catch(IOException e){reason.compareAndSet(null,EndReason.CLIENT_IO);throw e;}
        }
    }
    private static final class Request {
        final int seq,direction,length;
        Request(int seq,int direction,int length){this.seq=seq;this.direction=direction;this.length=length;}
    }
    private static final class WriteRequest {
        final Request request;final byte[] data;boolean started;
        WriteRequest(Request request,byte[] data){this.request=request;this.data=data;}
    }
}
