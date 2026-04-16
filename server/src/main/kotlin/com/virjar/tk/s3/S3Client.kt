package com.virjar.tk.s3

import com.virjar.tk.env.ThreadIOGuard
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.DefaultPromise
import io.netty.util.concurrent.Promise
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI

/**
 * 基于 Netty HttpClientCodec 的 S3 客户端。
 * 使用 Netty 原生 Promise/Future 体系，接受外部 [EventLoopGroup]。
 */
class S3Client(
    endpoint: String,
    private val accessKey: String,
    private val secretKey: String,
    private val bucket: String,
    private val eventLoopGroup: EventLoopGroup,
) {
    private val logger = LoggerFactory.getLogger(S3Client::class.java)

    private val host: String
    private val port: Int

    init {
        val uri = URI(endpoint)
        host = uri.host
        port = if (uri.port > 0) uri.port else 80
    }

    /**
     * 建立 TCP 连接并配置 HttpClientCodec，返回 Promise<Channel>。
     * Codec 在 ChannelInitializer 中添加，保证 pipeline 完整后再使用。
     */
    private fun connect(): Promise<Channel> {
        val promise: Promise<Channel> = DefaultPromise(eventLoopGroup.next())
        val bootstrap = Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(HttpClientCodec())
                }
            })

        bootstrap.connect(host, port).addListener { f ->
            if (f.isSuccess) {
                logger.debug("[S3] Connected to {}:{}", host, port)
                promise.trySuccess((f as ChannelFuture).channel())
            } else {
                logger.error("[S3] Connect failed to {}:{}: {}", host, port, f.cause()?.message)
                promise.tryFailure(f.cause())
            }
        }
        return promise
    }

    /**
     * HEAD — 获取对象元数据
     */
    fun headObject(objectKey: String): Promise<S3ObjectMeta?> {
        ThreadIOGuard.check("S3")
        val path = "/$bucket/$objectKey"
        val resultPromise: Promise<S3ObjectMeta?> = DefaultPromise(eventLoopGroup.next())

        connect().addListener { f ->
            if (!f.isSuccess) {
                logger.error("[S3] headObject connect failed for {}: {}", objectKey, f.cause()?.message)
                resultPromise.tryFailure(f.cause())
                return@addListener
            }
            @Suppress("UNCHECKED_CAST")
            val channel = (f as io.netty.util.concurrent.Future<Channel>).getNow()
            logger.debug("[S3] headObject connected for {}, pipeline: {}", objectKey, channel.pipeline().names())
            val responsePromise: Promise<S3Response> = DefaultPromise(eventLoopGroup.next())
            // HEAD 响应没有 body，不能用 HttpObjectAggregator（它会检查 Content-Length 导致误报超限）
            channel.pipeline().addLast(S3FullResponseHandler(responsePromise))

            val request = buildRequest(HttpMethod.HEAD, path)
            logger.debug("[S3] headObject sending: {} {}", request.method(), request.uri())
            channel.writeAndFlush(request).addListener { wf ->
                if (!wf.isSuccess) {
                    logger.error("[S3] headObject write failed: {}", wf.cause()?.message)
                    resultPromise.tryFailure(wf.cause())
                }
            }

            responsePromise.addListener { rf ->
                try {
                    if (!rf.isSuccess) {
                        resultPromise.tryFailure(rf.cause())
                        return@addListener
                    }
                    val response = (rf as io.netty.util.concurrent.Future<S3Response>).getNow()
                    if (response.statusCode == 404) {
                        resultPromise.trySuccess(null)
                    } else if (response.isSuccessful()) {
                        val contentLength = response.headers.get(HttpHeaderNames.CONTENT_LENGTH)?.toLongOrNull() ?: 0L
                        resultPromise.trySuccess(
                            S3ObjectMeta(
                                contentLength = contentLength,
                                contentType = response.headers.get(HttpHeaderNames.CONTENT_TYPE) ?: "application/octet-stream",
                                etag = response.headers.get(HttpHeaderNames.ETAG),
                                lastModified = response.headers.get(HttpHeaderNames.LAST_MODIFIED),
                            )
                        )
                    } else {
                        resultPromise.tryFailure(RuntimeException("S3 HEAD failed: ${response.statusCode}"))
                    }
                } finally {
                    channel.close()
                }
            }
        }
        return resultPromise
    }

    /**
     * GET — 小文件全量下载
     */
    fun getObject(objectKey: String, range: String? = null): Promise<S3Response> {
        ThreadIOGuard.check("S3")
        val path = "/$bucket/$objectKey"
        val resultPromise: Promise<S3Response> = DefaultPromise(eventLoopGroup.next())

        connect().addListener { f ->
            if (!f.isSuccess) {
                resultPromise.tryFailure(f.cause())
                return@addListener
            }
            @Suppress("UNCHECKED_CAST")
            val channel = (f as io.netty.util.concurrent.Future<Channel>).getNow()
            val responsePromise: Promise<S3Response> = DefaultPromise(eventLoopGroup.next())
            channel.pipeline().addLast(HttpObjectAggregator(16 * 1024 * 1024))
            channel.pipeline().addLast(S3FullResponseHandler(responsePromise))

            channel.writeAndFlush(buildRequest(HttpMethod.GET, path, range = range))

            responsePromise.addListener { rf ->
                try {
                    if (!rf.isSuccess) {
                        resultPromise.tryFailure(rf.cause())
                        return@addListener
                    }
                    val response = (rf as io.netty.util.concurrent.Future<S3Response>).getNow()
                    if (response.statusCode == 404) {
                        resultPromise.tryFailure(RuntimeException("Object not found: $objectKey"))
                    } else {
                        resultPromise.trySuccess(response)
                    }
                } finally {
                    channel.close()
                }
            }
        }
        return resultPromise
    }

    /**
     * GET — 流式下载（大文件/视频，支持 Range）
     */
    fun getObjectStreaming(objectKey: String, consumer: S3StreamConsumer, range: String? = null): Promise<Unit> {
        ThreadIOGuard.check("S3")
        val path = "/$bucket/$objectKey"
        val resultPromise: Promise<Unit> = DefaultPromise(eventLoopGroup.next())

        connect().addListener { f ->
            if (!f.isSuccess) {
                resultPromise.tryFailure(f.cause())
                return@addListener
            }
            @Suppress("UNCHECKED_CAST")
            val channel = (f as io.netty.util.concurrent.Future<Channel>).getNow()
            val streamPromise: Promise<Unit> = DefaultPromise(eventLoopGroup.next())
            channel.pipeline().addLast(S3StreamingResponseHandler(consumer, streamPromise))

            channel.writeAndFlush(buildRequest(HttpMethod.GET, path, range = range))

            streamPromise.addListener { sf ->
                try {
                    if (!sf.isSuccess) {
                        resultPromise.tryFailure(sf.cause())
                    } else {
                        resultPromise.trySuccess(Unit)
                    }
                } finally {
                    channel.close()
                }
            }
        }
        return resultPromise
    }

    /**
     * PUT — 上传字节数组（小文件、缩略图）
     */
    fun putObject(objectKey: String, data: ByteArray, contentType: String): Promise<Unit> {
        ThreadIOGuard.check("S3")
        val path = "/$bucket/$objectKey"
        val resultPromise: Promise<Unit> = DefaultPromise(eventLoopGroup.next())
        val payloadHash = AwsV4Signer.sha256Hex(data)

        connect().addListener { f ->
            if (!f.isSuccess) {
                resultPromise.tryFailure(f.cause())
                return@addListener
            }
            @Suppress("UNCHECKED_CAST")
            val channel = (f as io.netty.util.concurrent.Future<Channel>).getNow()
            val responsePromise: Promise<S3Response> = DefaultPromise(eventLoopGroup.next())
            channel.pipeline().addLast(HttpObjectAggregator(16 * 1024 * 1024))
            channel.pipeline().addLast(S3FullResponseHandler(responsePromise))

            val content = Unpooled.wrappedBuffer(data)
            val request = buildRequest(HttpMethod.PUT, path, payloadHash = payloadHash)
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.size.toString())
            channel.writeAndFlush(request.replace(content))

            responsePromise.addListener { rf ->
                try {
                    if (!rf.isSuccess) {
                        resultPromise.tryFailure(rf.cause())
                        return@addListener
                    }
                    val response = (rf as io.netty.util.concurrent.Future<S3Response>).getNow()
                    if (response.isSuccessful()) {
                        resultPromise.trySuccess(Unit)
                    } else {
                        resultPromise.tryFailure(
                            RuntimeException("S3 PUT failed: ${response.statusCode} - ${String(response.body, Charsets.UTF_8)}")
                        )
                    }
                } finally {
                    channel.close()
                }
            }
        }
        return resultPromise
    }

    /**
     * PUT — 流式上传（从 InputStream，大文件）
     */
    fun putObjectStreaming(
        objectKey: String,
        contentLength: Long,
        contentType: String,
        inputStream: InputStream,
    ): Promise<Unit> {
        ThreadIOGuard.check("S3")
        val path = "/$bucket/$objectKey"
        val resultPromise: Promise<Unit> = DefaultPromise(eventLoopGroup.next())

        connect().addListener { f ->
            if (!f.isSuccess) {
                resultPromise.tryFailure(f.cause())
                return@addListener
            }
            @Suppress("UNCHECKED_CAST")
            val channel = (f as io.netty.util.concurrent.Future<Channel>).getNow()
            val responsePromise: Promise<S3Response> = DefaultPromise(eventLoopGroup.next())
            channel.pipeline().addLast(HttpObjectAggregator(16 * 1024 * 1024))
            channel.pipeline().addLast(S3FullResponseHandler(responsePromise))

            val request = buildRequest(HttpMethod.PUT, path, payloadHash = AwsV4Signer.UNSIGNED_PAYLOAD)
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength.toString())

            channel.write(request)

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                channel.write(DefaultHttpContent(Unpooled.wrappedBuffer(buffer, 0, bytesRead)))
            }
            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)

            responsePromise.addListener { rf ->
                try {
                    if (!rf.isSuccess) {
                        resultPromise.tryFailure(rf.cause())
                        return@addListener
                    }
                    val response = (rf as io.netty.util.concurrent.Future<S3Response>).getNow()
                    if (response.isSuccessful()) {
                        resultPromise.trySuccess(Unit)
                    } else {
                        resultPromise.tryFailure(
                            RuntimeException("S3 PUT streaming failed: ${response.statusCode}")
                        )
                    }
                } finally {
                    channel.close()
                }
            }
        }
        return resultPromise
    }

    /**
     * DELETE
     */
    fun deleteObject(objectKey: String): Promise<Unit> {
        ThreadIOGuard.check("S3")
        val path = "/$bucket/$objectKey"
        val resultPromise: Promise<Unit> = DefaultPromise(eventLoopGroup.next())

        connect().addListener { f ->
            if (!f.isSuccess) {
                resultPromise.tryFailure(f.cause())
                return@addListener
            }
            @Suppress("UNCHECKED_CAST")
            val channel = (f as io.netty.util.concurrent.Future<Channel>).getNow()
            val responsePromise: Promise<S3Response> = DefaultPromise(eventLoopGroup.next())
            channel.pipeline().addLast(HttpObjectAggregator(16 * 1024 * 1024))
            channel.pipeline().addLast(S3FullResponseHandler(responsePromise))

            channel.writeAndFlush(buildRequest(HttpMethod.DELETE, path))

            responsePromise.addListener { rf ->
                try {
                    if (!rf.isSuccess) {
                        resultPromise.tryFailure(rf.cause())
                        return@addListener
                    }
                    val response = (rf as io.netty.util.concurrent.Future<S3Response>).getNow()
                    if (response.statusCode == 204 || response.statusCode == 404) {
                        resultPromise.trySuccess(Unit)
                    } else {
                        resultPromise.tryFailure(RuntimeException("S3 DELETE failed: ${response.statusCode}"))
                    }
                } finally {
                    channel.close()
                }
            }
        }
        return resultPromise
    }

    /**
     * 检查 Bucket 是否存在
     */
    fun bucketExists(): Promise<Boolean> {
        ThreadIOGuard.check("S3")
        val path = "/$bucket"
        val resultPromise: Promise<Boolean> = DefaultPromise(eventLoopGroup.next())

        connect().addListener { f ->
            if (!f.isSuccess) {
                resultPromise.tryFailure(f.cause())
                return@addListener
            }
            @Suppress("UNCHECKED_CAST")
            val channel = (f as io.netty.util.concurrent.Future<Channel>).getNow()
            val responsePromise: Promise<S3Response> = DefaultPromise(eventLoopGroup.next())
            // HEAD 响应没有 body
            channel.pipeline().addLast(S3FullResponseHandler(responsePromise))

            channel.writeAndFlush(buildRequest(HttpMethod.HEAD, path))

            responsePromise.addListener { rf ->
                try {
                    if (!rf.isSuccess) {
                        resultPromise.tryFailure(rf.cause())
                        return@addListener
                    }
                    val response = (rf as io.netty.util.concurrent.Future<S3Response>).getNow()
                    resultPromise.trySuccess(response.statusCode in 200..299)
                } finally {
                    channel.close()
                }
            }
        }
        return resultPromise
    }

    /**
     * 创建 Bucket
     */
    fun makeBucket(): Promise<Unit> {
        ThreadIOGuard.check("S3")
        val path = "/$bucket"
        val resultPromise: Promise<Unit> = DefaultPromise(eventLoopGroup.next())

        connect().addListener { f ->
            if (!f.isSuccess) {
                resultPromise.tryFailure(f.cause())
                return@addListener
            }
            @Suppress("UNCHECKED_CAST")
            val channel = (f as io.netty.util.concurrent.Future<Channel>).getNow()
            val responsePromise: Promise<S3Response> = DefaultPromise(eventLoopGroup.next())
            channel.pipeline().addLast(HttpObjectAggregator(16 * 1024 * 1024))
            channel.pipeline().addLast(S3FullResponseHandler(responsePromise))

            val request = buildRequest(HttpMethod.PUT, path, payloadHash = AwsV4Signer.EMPTY_PAYLOAD_HASH)
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0")
            channel.writeAndFlush(request)

            responsePromise.addListener { rf ->
                try {
                    if (!rf.isSuccess) {
                        resultPromise.tryFailure(rf.cause())
                        return@addListener
                    }
                    val response = (rf as io.netty.util.concurrent.Future<S3Response>).getNow()
                    if (response.isSuccessful()) {
                        resultPromise.trySuccess(Unit)
                    } else {
                        resultPromise.tryFailure(RuntimeException("S3 makeBucket failed: ${response.statusCode}"))
                    }
                } finally {
                    channel.close()
                }
            }
        }
        return resultPromise
    }

    fun shutdown() {
        // EventLoopGroup 由外部管理
    }

    /**
     * 构建带 V4 签名的 HTTP 请求。
     */
    private fun buildRequest(
        method: HttpMethod,
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        range: String? = null,
        payloadHash: String = AwsV4Signer.EMPTY_PAYLOAD_HASH,
    ): DefaultFullHttpRequest {
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path)
        request.headers().set(HttpHeaderNames.HOST, "$host:$port")
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0")
        request.headers().set("x-amz-content-sha256", payloadHash)

        if (range != null) {
            request.headers().set(HttpHeaderNames.RANGE, range)
        }

        val headersToSign = mutableMapOf<String, String>()
        headersToSign["host"] = "$host:$port"
        headersToSign["x-amz-content-sha256"] = payloadHash
        if (range != null) {
            headersToSign["range"] = range
        }

        val signingResult = AwsV4Signer.sign(
            method = method.name(),
            path = path,
            queryParams = queryParams,
            headers = headersToSign,
            payloadHash = payloadHash,
            accessKey = accessKey,
            secretKey = secretKey,
        )

        request.headers().set("x-amz-date", signingResult.dateTime)
        request.headers().set(HttpHeaderNames.AUTHORIZATION, signingResult.authorization)

        return request
    }
}
