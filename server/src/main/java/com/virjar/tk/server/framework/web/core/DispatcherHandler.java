package com.virjar.tk.server.framework.web.core;

import com.virjar.tk.server.sys.service.trace.Recorder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import static com.google.common.base.Preconditions.checkNotNull;

class DispatcherHandler extends SimpleChannelInboundHandler<RequestSession> {
    private final NettyServletContext context;
    private final Recorder recorder;

    DispatcherHandler(NettyServletContext context, Recorder recorder) {
        this.context = checkNotNull(context);
        this.recorder = recorder;
    }


    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RequestSession requestSession) throws Exception {
        String requestURI = requestSession.getServletRequest().getRequestURI();
        try {
            recorder.attach();
            NettyRequestDispatcher dispatcher = (NettyRequestDispatcher) context.getRequestDispatcher(requestURI);
            if (dispatcher == null) {
                recorder.recordEvent(() -> "can not find dispatcher for uri:" + requestURI);
                requestSession.getServletResponse().sendError(404);
                return;
            }
            dispatcher.dispatch(requestSession);
        } finally {
            if (!requestSession.getServletRequest().isAsyncStarted()) {
                requestSession.destroy();
            }
            recorder.detach();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        recorder.recordEvent(() -> "Unexpected exception caught during request", cause);
        ctx.close();
    }
}
