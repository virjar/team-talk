package com.virjar.tk.server.framework.web.core;

import com.google.common.base.Preconditions;
import com.virjar.tk.server.sys.service.trace.Recorder;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;

import java.util.function.Consumer;

/**
 * virjar迁移自：<a href="https://github.com/Leibnizhu/spring-boot-starter-netty">spring-boot-starter-netty</a>
 *
 * <ul>
 *     <li>适配springboot2.x</li>
 *     <li>抽象netty入口层，从netty路由自本模块</li>
 *     <li>todo：原作者异步MVC似乎存在bug</li>
 *     <li>后置调用Servlet.init()，避免spring容器启动可能存在循环依赖</li>
 *     <li>实现servlet的getRequestURL，此方法导致swagger的内部servlet服务空指针无法服务</li>
 *     <li>静态资源解析时，404是常见情况，不应该打印报错日志</li>
 *     <li>servlet解析Data类型的header时，和nettyAPI接口相似但行为不一致</li>
 *     <li>HttpServletResponse在多段write时，outputStream被提前commit，导致http返回数据不完整</li>
 *     <li>简单支持了forward，这是因为静态资源服务器访问根目录是，springboot要求forward到index.html</li>
 *     <li>重写了InputStream，OutputStream等同步流试读写实现，保证性能和代码可读性</li>
 *     <li>实现了multipart，重写postBody的解析（部分参数来自multipart）</li>
 * </ul>
 * 使用sbnetty替代tomcat的原因：我们使用netty处理很多除开web以外的请求，但是希望只开启一个业务端口，
 * 同时基于业务便利，普通的web情况需要运行在springMVC之上，故以netty开放tcp服务端口，在netty做协议识别的模块路由，
 * 当确认为http请求时，直接从netty调用springMVC，故需要实现netty作为底层服务器的springMVC容器
 * <p>
 * 否则我们需要在127.0.0.1启动tomcat服务器，并且直接通过网络转发的方式调用以tomcat为底层容器的web服务
 * <p>
 * <p>
 * Netty servle容器
 * 处理请求，返回响应
 * 目前不支持JSP，考虑到SpringBoot多用于REST+前后端分离，也不会去实现JSP
 *
 * @author Leibniz
 * @author virjar
 */
@Slf4j
public class SpringbootNettyWebServer implements WebServer {

    private final NettyServletContext servletContext; //Context


    public SpringbootNettyWebServer(NettyServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Override
    public void start() throws WebServerException {
        // 在我们的修改版本中，start，stop，getPort都没有内容，因为实际上服务器启动不再这里控制
    }


    @Override
    public void stop() throws WebServerException {
        log.info("Embedded Netty Servlet Container(by Leibniz.Hu) is now shuting down.");
    }

    @Override
    public int getPort() {
        return -1;
    }

    public void serveWithWebServer(Channel channel, EventExecutorGroup eventExecutors, Recorder recorder
            , Consumer<ChannelPipeline> resetFunc) {
        Preconditions.checkNotNull(eventExecutors);

        EventLoopGroup channelEventLoopGroup = channel.eventLoop().parent();
        Preconditions.checkArgument(channelEventLoopGroup != eventExecutors,
                "the channel eventLoopGroup is  the same as the eventExecutors");

        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(ChunkedWriteHandler.class) == null) {
            pipeline.addLast(new ChunkedWriteHandler());
        }

        pipeline.addLast(new SessionResetHandler(resetFunc));

        // running on netty thread
        pipeline.addLast(new RequestSessionAggregator(recorder));

        // running on dispatch thread
        pipeline.addLast(eventExecutors, new DispatcherHandler(servletContext, recorder));
    }

    private class SessionResetHandler extends ChannelOutboundHandlerAdapter {
        private final Consumer<ChannelPipeline> resetFunc;

        public SessionResetHandler(Consumer<ChannelPipeline> resetFunc) {
            this.resetFunc = resetFunc;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof LastHttpContent) {
                if (promise.isVoid()) {
                    promise = ctx.newPromise();
                }
                promise.addListener((ChannelFutureListener) channelFuture -> {
                    if (channelFuture.isSuccess()) {
                        resetSession(ctx);
                    }
                });
            }
            super.write(ctx, msg, promise);
        }

        private void resetSession(ChannelHandlerContext ctx) {
            ChannelPipeline pipeline = ctx.pipeline();
            pipeline.remove(SessionResetHandler.class);
            pipeline.remove(RequestSessionAggregator.class);
            pipeline.remove(DispatcherHandler.class);
            resetFunc.accept(pipeline);
        }
    }

    /**
     * 不使用HttpObjectAggregator，因为在处理文件上传时，HttpObjectAggregator将文件内容存储在内存中，
     * 在处理大文件上传时，会有内存溢出风险
     */
    class RequestSessionAggregator extends SimpleChannelInboundHandler<HttpObject> {

        private RequestSession requestSession;
        private final Recorder recorder;

        RequestSessionAggregator(Recorder recorder) {
            this.recorder = recorder;
        }


        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
            recorder.recordEvent(() -> "servlet prepare get httpMsg:" + msg);
            if (msg instanceof HttpRequest) {
                HttpRequest request = ReferenceCountUtil.retain((HttpRequest) msg);
                requestSession = new RequestSession(ctx, request, servletContext, recorder);
                if (HttpUtil.is100ContinueExpected(request)) { //请求头包含Expect: 100-continue
                    ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
                }
            } else if (msg instanceof HttpContent) {
                requestSession.offer((HttpContent) msg);
                if (msg instanceof LastHttpContent) {
                    ctx.fireChannelRead(requestSession);
                    requestSession = null;
                }
            } else {
                recorder.recordEvent(() -> "not handle http message:" + msg);
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            recorder.recordEvent(() -> "request channel inactive");
            if (requestSession != null) {
                requestSession.destroy();
            }
        }
    }
}
