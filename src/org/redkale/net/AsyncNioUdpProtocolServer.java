/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import org.redkale.boot.Application;
import org.redkale.util.*;

/**
 * 协议底层Server
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
class AsyncNioUdpProtocolServer extends ProtocolServer {

    private DatagramChannel serverChannel;

    private Selector selector;

    private AsyncIOGroup ioGroup;

    private Thread acceptThread;

    private boolean closed;

    private Supplier<Response> responseSupplier;

    private Consumer<Response> responseConsumer;

    public AsyncNioUdpProtocolServer(Context context) {
        super(context);
    }

    @Override
    public void open(AnyValue config) throws IOException {
        this.serverChannel = DatagramChannel.open();
        this.serverChannel.configureBlocking(false);
        this.selector = Selector.open();
        final Set<SocketOption<?>> options = this.serverChannel.supportedOptions();
        if (options.contains(StandardSocketOptions.TCP_NODELAY)) {
            this.serverChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
        if (options.contains(StandardSocketOptions.SO_KEEPALIVE)) {
            this.serverChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
        }
        if (options.contains(StandardSocketOptions.SO_REUSEADDR)) {
            this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        }
        if (options.contains(StandardSocketOptions.SO_RCVBUF)) {
            this.serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, 16 * 1024);
        }
        if (options.contains(StandardSocketOptions.SO_SNDBUF)) {
            this.serverChannel.setOption(StandardSocketOptions.SO_SNDBUF, 16 * 1024);
        }
    }

    @Override
    public void bind(SocketAddress local, int backlog) throws IOException {
        this.serverChannel.bind(local);
    }

    @Override
    public <T> void setOption(SocketOption<T> name, T value) throws IOException {
        this.serverChannel.setOption(name, value);
    }

    @Override
    public <T> Set<SocketOption<?>> supportedOptions() {
        return this.serverChannel.supportedOptions();
    }

    @Override
    public void accept(Application application, Server server) throws IOException {
        AtomicLong createBufferCounter = new AtomicLong();
        AtomicLong cycleBufferCounter = new AtomicLong();

        ObjectPool<ByteBuffer> safeBufferPool = server.createBufferPool(createBufferCounter, cycleBufferCounter, server.bufferPoolSize);
        AtomicLong createResponseCounter = new AtomicLong();
        AtomicLong cycleResponseCounter = new AtomicLong();
        ObjectPool<Response> safeResponsePool = server.createResponsePool(createResponseCounter, cycleResponseCounter, server.responsePoolSize);
        final int threads = Runtime.getRuntime().availableProcessors();
        ThreadLocal<ObjectPool<Response>> localResponsePool = ThreadLocal.withInitial(() -> {
            if (!(Thread.currentThread() instanceof WorkThread)) return null;
            return ObjectPool.createUnsafePool(safeResponsePool, safeResponsePool.getCreatCounter(),
                safeResponsePool.getCycleCounter(), 16, safeResponsePool.getCreator(), safeResponsePool.getPrepare(), safeResponsePool.getRecycler());
        });
        this.responseSupplier = () -> {
            ObjectPool<Response> pool = localResponsePool.get();
            return pool == null ? safeResponsePool.get() : pool.get();
        };
        this.responseConsumer = (v) -> {
            ObjectPool<Response> pool = localResponsePool.get();
            (pool == null ? safeResponsePool : pool).accept(v);
        };
        final String threadPrefixName = server.name == null || server.name.isEmpty() ? "Redkale-IOServletThread" : ("Redkale-" + server.name.replace("Server-", "") + "-IOServletThread");
        this.ioGroup = new AsyncIOGroup(threadPrefixName, null, threads, server.bufferCapacity, safeBufferPool);
        this.ioGroup.start();
        this.serverChannel.register(this.selector, SelectionKey.OP_READ);

        this.acceptThread = new Thread() {
            ObjectPool<ByteBuffer> unsafeBufferPool = ObjectPool.createUnsafePool(safeBufferPool, safeBufferPool.getCreatCounter(),
                safeBufferPool.getCycleCounter(), 512, safeBufferPool.getCreator(), safeBufferPool.getPrepare(), safeBufferPool.getRecycler());

            {
                setName(threadPrefixName.replace("ServletThread", "AcceptThread"));
            }

            @Override
            public void run() {
                while (!closed) {
                    final ByteBuffer buffer = unsafeBufferPool.get();
                    try {
                        SocketAddress address = serverChannel.receive(buffer);
                        buffer.flip();
                        accept(address, buffer);
                    } catch (Throwable t) {
                        unsafeBufferPool.accept(buffer);
                    }
                }
            }
        };
        this.acceptThread.start();
    }

    private void accept(SocketAddress address, ByteBuffer buffer) throws IOException {
        AsyncIOThread readThread = ioGroup.nextIOThread();
        ioGroup.connCreateCounter.incrementAndGet();
        ioGroup.connLivingCounter.incrementAndGet();
        AsyncNioUdpConnection conn = new AsyncNioUdpConnection(false, ioGroup, readThread, ioGroup.connectThread(), this.serverChannel, context.getSSLContext(), address, ioGroup.connLivingCounter, ioGroup.connClosedCounter);
        ProtocolCodec codec = new ProtocolCodec(context, responseSupplier, responseConsumer, conn);
        conn.protocolCodec = codec;
        codec.run(buffer);
    }

    @Override
    public void close() throws IOException {
        if (this.closed) return;
        this.closed = true;
        this.ioGroup.close();
        this.serverChannel.close();
    }

    @Override
    public long getCreateConnectionCount() {
        return -1;
    }

    @Override
    public long getClosedConnectionCount() {
        return -1;
    }

    @Override
    public long getLivingConnectionCount() {
        return -1;
    }
}
