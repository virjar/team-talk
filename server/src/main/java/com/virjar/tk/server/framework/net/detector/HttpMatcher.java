package com.virjar.tk.server.framework.net.detector;


import com.google.common.collect.Sets;
import com.virjar.tk.server.sys.service.trace.Recorder;
import com.virjar.tk.server.framework.net.HttpFirstPacketHandler;
import com.virjar.tk.server.framework.web.core.SpringbootNettyWebServer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;

import java.util.Set;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Matcher for plain http request.
 */
public class HttpMatcher extends ProtocolMatcher {

    private final SpringbootNettyWebServer server;
    private final Recorder recorder;


    private static final Set<String> methods = Sets.newHashSet("GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE",
            "TRACE");

    public HttpMatcher(SpringbootNettyWebServer server, Recorder recorder) {
        this.server = server;
        this.recorder = recorder;
    }


    @Override
    public int match(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return PENDING;
        }

        int index = buf.indexOf(0, 8, (byte) ' ');
        if (index < 0) {
            return MISMATCH;
        }

        int firstURIIndex = index + 1;
        if (buf.readableBytes() < firstURIIndex + 1) {
            return PENDING;
        }

        String method = buf.toString(0, index, US_ASCII);
        char firstURI = (char) (buf.getByte(firstURIIndex + buf.readerIndex()) & 0xff);
        if (!methods.contains(method) || firstURI != '/') {
            return MISMATCH;
        }

        return MATCH;
    }

    @Override
    public void handlePipeline(ChannelPipeline pipeline) {
        pipeline.addLast(
                new HttpServerCodec(),
                new HttpFirstPacketHandler(server, recorder)
        );
    }
}
