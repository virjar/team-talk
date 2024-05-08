package com.virjar.tk.server.framework.web.core;

import com.virjar.tk.server.framework.web.servlet.HttpRequestInputStream;
import com.virjar.tk.server.framework.web.servlet.NettyHttpServletRequest;
import com.virjar.tk.server.sys.service.trace.Recorder;
import com.virjar.tk.server.framework.web.servlet.NettyHttpServletResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public class RequestSession {
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    private final HttpRequest nettyRequest;
    private final NettyHttpServletRequest servletRequest;
    private final NettyHttpServletResponse servletResponse;

    private final HttpRequestInputStream inputStream;
    private HttpPostRequestDecoder httpPostRequestDecoder;

    private final Recorder recorder;

    public RequestSession(ChannelHandlerContext ctx, HttpRequest request, NettyServletContext servletContext, Recorder recorder) {
        this.nettyRequest = request;
        this.inputStream = new HttpRequestInputStream();
        this.recorder = recorder;

        if (request.method().equals(HttpMethod.POST)) {
            String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
            if (HttpPostRequestDecoder.isMultipart(request)
                    || StringUtils.startsWithIgnoreCase(contentType, "application/x-www-form-urlencoded")
            ) {
                // 对于x-www-form-urlencoded和multipart，使用netty的解析器来处理
                httpPostRequestDecoder = new HttpPostRequestDecoder(request);
            }
        }
        this.servletRequest = new NettyHttpServletRequest(ctx, servletContext, request, httpPostRequestDecoder, inputStream);
        this.servletResponse = new NettyHttpServletResponse(ctx, servletContext, servletRequest);
    }


    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }

        IOUtils.closeQuietly(inputStream);
        IOUtils.closeQuietly(servletResponse.getOutputStream());

        if (httpPostRequestDecoder != null) {
            httpPostRequestDecoder.destroy();
            httpPostRequestDecoder = null;
        }
        ReferenceCountUtil.release(nettyRequest);
    }

    public void offer(HttpContent msg) {
        if (destroyed.get()) {
            return;
        }

        if (httpPostRequestDecoder != null) {
            httpPostRequestDecoder.offer(msg);
        } else {
            inputStream.offer(msg);
        }
    }
}
