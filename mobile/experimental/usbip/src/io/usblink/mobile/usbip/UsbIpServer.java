package io.usblink.mobile.usbip;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded userspace USB/IP v1.1.1 experiment: one ADB device, one manual import. */
public final class UsbIpServer implements Closeable {
    public interface Backend { Socket connect() throws IOException; }
    private final AdbUsbDevice device;
    private final Backend backend;
    private final Predicate<InetAddress> allowedPeer;
    private final Consumer<String> log;
    private final ServerSocket listener = new ServerSocket();
    private final Set<Socket> clients = new HashSet<>();
    private final Set<Socket> targets = new HashSet<>();
    private volatile Session session;
    private final AtomicBoolean imported = new AtomicBoolean();
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r,"usbip-probe-client"); t.setDaemon(true); return t;
    });
    private volatile boolean closed;
    private volatile long firstImportNanos = -1;

    public UsbIpServer(InetAddress bind, int port, InetAddress peer, AdbUsbDevice device,
                       Backend backend, Consumer<String> log) throws IOException {
        this(bind,port,address->address.equals(peer),device,backend,log);
    }
    public UsbIpServer(InetAddress bind, int port, Predicate<InetAddress> peer, AdbUsbDevice device,
                       Backend backend, Consumer<String> log) throws IOException {
        this.device=device; this.backend=backend; this.allowedPeer=peer; this.log=log;
        listener.setReuseAddress(false); listener.bind(new InetSocketAddress(bind,port),4);
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
        quietClose(s.client);quietClose(s.target);
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
                Session s=new Session(socket,target,in,out);session=s;
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
        closed=true;quietClose(listener);
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
        private final ArrayDeque<byte[]> chunks=new ArrayDeque<>();
        private int chunkOffset,queuedBytes;
        private boolean stopped;
        Session(Socket client,Socket target,DataInputStream input,DataOutputStream output) {
            this.client=client;this.target=target;this.input=input;this.output=output;
        }
        void run()throws IOException {
            Thread reader=new Thread(this::readAdb,"usbip-probe-adbd");reader.setDaemon(true);reader.start();
            try {
                while(true) {
                    byte[] header=new byte[48];input.readFully(header);
                    ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
                    int command=b.getInt(),seq=b.getInt(),id=b.getInt(),direction=b.getInt(),ep=b.getInt();
                    if(id!=AdbUsbDevice.DEVICE_ID || (direction!=0 && direction!=1))throw new IOException("invalid request identity");
                    if(command==2) {if(ep!=0)throw new IOException("invalid unlink");unlink(seq,b.getInt());continue;}
                    if(command!=1)throw new IOException("invalid request command");
                    int flags=b.getInt(),length=b.getInt();b.getInt();int packets=b.getInt();b.getInt();
                    byte[] setup=new byte[8];b.get(setup);
                    if(length<0 || length>AdbUsbDevice.MAX_TRANSFER || (packets!=0 && packets!=-1))throw new IOException("unsupported transfer");
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
                        target.getOutputStream().write(data);bytes.addAndGet(length);complete(request,0,new byte[0],length);
                    } else if(ep==1 && direction==1) {
                        if(length==0){complete(request,0,new byte[0],0);continue;}
                        synchronized(this) {
                            if(pending.containsKey(seq) || pending.size()>=32)throw new IOException("too many pending reads");
                            pending.put(seq,request);drain();
                        }
                    } else complete(request,-32,new byte[0],0);
                }
            } finally {
                synchronized(this){stopped=true;notifyAll();}
                quietClose(target);quietClose(client);
                try{reader.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
        }
        private void readAdb() {
            try {
                DataInputStream source=new DataInputStream(target.getInputStream());
                while(true) {
                    byte[] header=new byte[24];source.readFully(header);
                    ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                    int command=b.getInt(0),length=b.getInt(12),magic=b.getInt(20);
                    if(magic!=(command^0xffffffff) || length<0 || length>AdbUsbDevice.MAX_TRANSFER)throw new IOException("invalid ADB packet");
                    offer(header);
                    if(length>0){byte[] payload=new byte[length];source.readFully(payload);offer(payload);}
                }
            }catch(IOException e){quietClose(client);}
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
            ByteBuffer b=ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
            b.putInt(4).putInt(seq).putInt(0).putInt(0).putInt(0).putInt(removed?-104:0);
            output.write(b.array());
        }
        private synchronized void complete(Request r,int status,byte[] data,int length)throws IOException {
            ByteBuffer b=ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
            b.putInt(3).putInt(r.seq).putInt(0).putInt(0).putInt(0).putInt(status).putInt(length).putInt(0).putInt(0).putInt(0);
            output.write(b.array());if(r.direction==1){output.write(data);bytes.addAndGet(data.length);}
        }
    }
    private static final class Request {
        final int seq,direction,length;
        Request(int seq,int direction,int length){this.seq=seq;this.direction=direction;this.length=length;}
    }
}
