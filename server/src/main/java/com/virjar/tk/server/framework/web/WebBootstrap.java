package com.virjar.tk.server.framework.web;

import com.virjar.tk.server.sys.service.config.Configs;
import com.virjar.tk.server.sys.service.env.Environment;
import com.virjar.tk.server.sys.service.trace.Recorder;
import com.virjar.tk.server.sys.service.trace.impl.SubscribeRecorders;
import com.virjar.tk.server.framework.net.KeyStoreLoader;
import com.virjar.tk.server.framework.net.detector.HttpMatcher;
import com.virjar.tk.server.framework.net.detector.ProtocolDetector;
import com.virjar.tk.server.framework.net.detector.ProtocolMatcher;
import com.virjar.tk.server.framework.net.detector.SSLMatcher;
import com.virjar.tk.server.framework.web.core.SpringbootNettyWebServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class WebBootstrap implements ApplicationListener<WebServerInitializedEvent> {
    private static final NioEventLoopGroup serverBossGroup = new NioEventLoopGroup(
            Runtime.getRuntime().availableProcessors() * 2,
            new DefaultThreadFactory("web-boss")
    );

    private static final NioEventLoopGroup serverWorkerGroup = new NioEventLoopGroup(
            Runtime.getRuntime().availableProcessors() * 3,
            new DefaultThreadFactory("web-worker")
    );

    public static final byte[] UN_SUPPORT_PROTOCOL_MSG = "unknown protocol".getBytes();

    public ProtocolDetector buildProtocolDetector(Recorder detectRecord, SpringbootNettyWebServer server) {
        SslContext customSslContext = KeyStoreLoader.getCustomSslContext();
        ProtocolMatcher[] protocolMatchers = customSslContext == null ? new ProtocolMatcher[]{
                new HttpMatcher(server, detectRecord)
        } : new ProtocolMatcher[]{
                new HttpMatcher(server, detectRecord), new SSLMatcher(customSslContext, server, detectRecord)
        };

        return new ProtocolDetector(detectRecord,
                (ctx, buf) -> {
                    detectRecord.recordEvent("unsupported protocol");
                    buf.release();
                    ctx.channel().writeAndFlush(Unpooled.wrappedBuffer(UN_SUPPORT_PROTOCOL_MSG))
                            .addListener(ChannelFutureListener.CLOSE);
                },
                protocolMatchers
        );
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        SpringbootNettyWebServer webServer = (SpringbootNettyWebServer) event.getWebServer();
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(serverBossGroup, serverWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        Recorder detectRecord = SubscribeRecorders.USER_SESSION.acquireRecorder(socketChannel.toString(),
                                Environment.isLocalDebug, "default");
                        detectRecord.recordEvent(() -> "new request");
                        socketChannel.pipeline().addLast(buildProtocolDetector(detectRecord, webServer));
                    }
                });
        String webPortStr = Configs.getConfig("server.port");
        log.info("start netty server,port:{}", webPortStr);
        serverBootstrap.bind(Integer.parseInt(webPortStr)).addListener(future -> {
            if (future.isSuccess()) {
                log.info("netty server start success");
            } else {
                log.info("netty server start failed", future.cause());
            }
        });
    }
}
