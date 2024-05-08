package com.virjar.tk.server.framework.net;

import com.virjar.tk.server.framework.web.core.SpringbootNettyWebServer;
import com.virjar.tk.server.sys.service.env.Environment;
import com.virjar.tk.server.sys.service.metric.embed.MonitorThreadPoolExecutor;
import com.virjar.tk.server.sys.service.trace.Recorder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutorGroup;

import java.util.ArrayDeque;
import java.util.Queue;

public class HttpFirstPacketHandler extends ChannelInboundHandlerAdapter {
    private Queue<HttpObject> httpObjects;

    private final SpringbootNettyWebServer server;
    private final Recorder recorder;

    private static final DefaultEventExecutorGroup servletWorkerGroup =
            new DefaultEventExecutorGroup(Environment.webWorkerThreads,
                    new MonitorThreadPoolExecutor.NamedThreadFactory("web-servlet")
            );

    public HttpFirstPacketHandler(SpringbootNettyWebServer server, Recorder recorder) {
        this.server = server;
        this.recorder = recorder;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        HttpRequest httpRequest;
        if (msg instanceof HttpRequest) {
            if (httpObjects != null) {
                for (HttpObject httpObject : httpObjects) {
                    ReferenceCountUtil.release(httpObject);
                }
            }
            httpObjects = new ArrayDeque<>();
            httpRequest = (HttpRequest) msg;
            httpObjects.add(httpRequest);
            recorder.recordEvent(() -> "http request: " + httpRequest);
        } else if (msg instanceof HttpObject) {
            httpObjects.add((HttpObject) msg);
            return;
        } else {
            recorder.recordEvent(() -> "not handle http message:" + msg);
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        if ("websocket".equalsIgnoreCase(httpRequest.headers().get("Upgrade"))) {
            // todo
//            recorder.recordEvent(() -> "this is websocket request:" + httpRequest);
//            // 所有websocket请求，在netty层面处理，因为tomcat几乎没有能力处理websocket，其网络api就是原生的二进制流
//            ChannelPipeline pipeline = ctx.pipeline();
//            pipeline.addLast(
//                    new IdleStateHandler(45, 30, 0),
//                    new HttpObjectAggregator(1 << 25),
//                    new WebsocketDispatcher()
//            );
//            pipeline.remove(HttpFirstPacketHandler.class);
//            return;
        }

        recorder.recordEvent(() -> "this is web request, forward to springMVC");
        // 其他请求，使用springboot来处理
        server.serveWithWebServer(
                ctx.channel(),
                servletWorkerGroup,
                recorder,
                pipeline -> pipeline.addLast(new HttpFirstPacketHandler(server, recorder))
        );
        ctx.pipeline().remove(HttpFirstPacketHandler.class);
    }





    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (httpObjects != null) {
            HttpObject b;
            while ((b = httpObjects.poll()) != null) {
                ctx.fireChannelRead(b);
            }
        }
        ctx.flush();
        httpObjects = null;
    }
}
