package com.virjar.tk.server.framework.net.detector;


import com.virjar.tk.server.framework.net.NettyUtil;
import com.virjar.tk.server.sys.service.trace.Recorder;
import com.virjar.tk.server.framework.web.core.SpringbootNettyWebServer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslContext;

public class SSLMatcher extends ProtocolMatcher {
    private final SslContext sslContext;
    private final SpringbootNettyWebServer server;
    private final Recorder recorder;

    public SSLMatcher(SslContext sslContext, SpringbootNettyWebServer server, Recorder recorder) {
        this.sslContext = sslContext;
        this.recorder = recorder;
        this.server = server;
        if (sslContext == null) {
            throw new IllegalStateException("no ssl context configured,please check you config");
        }
    }


    @Override
    public int match(ByteBuf buf) {
        if (buf.readableBytes() < 3) {
            return PENDING;
        }
        byte first = buf.getByte(buf.readerIndex());
        byte second = buf.getByte(buf.readerIndex() + 1);
        byte third = buf.getByte(buf.readerIndex() + 2);
        if (first == 22 && second <= 3 && third <= 3) {
            return MATCH;
        }
        return MISMATCH;
    }


    @Override
    public void handlePipeline(ChannelPipeline pipeline) {
        pipeline.addLast(
                // 解密ssl流量
                sslContext.newHandler(pipeline.channel().alloc()),
                // 之后把它当作普通的http流量识别
                new ProtocolDetector(
                        recorder,
                        (ctx, buf) -> {
                            recorder.recordEvent("unsupported protocol");
                            buf.release();
                            NettyUtil.closeOnFlush(ctx.channel());
                        },
                        new HttpMatcher(server, recorder)
                )
        );
    }
}
