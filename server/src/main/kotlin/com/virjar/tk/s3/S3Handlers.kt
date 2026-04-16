package com.virjar.tk.s3

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.Promise
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture

/**
 * S3 响应元数据（HEAD 请求返回）
 */
data class S3ObjectMeta(
    val contentLength: Long,
    val contentType: String,
    val etag: String?,
    val lastModified: String?,
)

/**
 * S3 完整响应（聚合模式：HEAD、PUT、DELETE、小文件 GET）
 */
data class S3Response(
    val statusCode: Int,
    val headers: HttpHeaders,
    val body: ByteArray,
) {
    fun isSuccessful(): Boolean = statusCode in 200..299
}

/**
 * S3 流式响应回调接口（大文件 GET）
 */
interface S3StreamConsumer {
    /** 收到响应头后调用，返回 true 继续接收 body，返回 false 中止 */
    fun onHeaders(response: HttpResponse): Boolean

    /** 收到 body chunk */
    fun onContent(content: ByteBuf)

    /** 整个响应结束 */
    fun onComplete()

    /** 发生错误 */
    fun onError(throwable: Throwable)
}

/**
 * 聚合模式 Handler：收集完整 HTTP 响应后通过 Netty Promise 返回。
 * 用于 HEAD、PUT、DELETE、小文件 GET。
 */
class S3FullResponseHandler(
    private val promise: Promise<S3Response>,
) : ChannelInboundHandlerAdapter() {

    private val logger = LoggerFactory.getLogger(S3FullResponseHandler::class.java)
    private var responseHeaders: HttpHeaders? = null
    private var statusCode: Int = 0
    private val baos = ByteArrayOutputStream()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is FullHttpResponse -> {
                logger.debug("[S3] FullResponse received: status={}", msg.status().code())
                promise.trySuccess(
                    S3Response(msg.status().code(), msg.headers(), copyToByteArray(msg.content()))
                )
                ctx.pipeline().remove(this)
            }
            is HttpResponse -> {
                logger.debug("[S3] HttpResponse received: status={}", msg.status().code())
                responseHeaders = msg.headers()
                statusCode = msg.status().code()
            }
            is HttpContent -> {
                val buf = msg.content()
                val bytes = ByteArray(buf.readableBytes())
                buf.getBytes(buf.readerIndex(), bytes)
                baos.write(bytes)
                if (msg is LastHttpContent) {
                    logger.debug("[S3] LastHttpContent received, total body size={}", baos.size())
                    promise.trySuccess(
                        S3Response(statusCode, responseHeaders ?: EmptyHttpHeaders.INSTANCE, baos.toByteArray())
                    )
                    ctx.pipeline().remove(this)
                }
            }
            else -> {
                logger.warn("[S3] Unexpected message type: {}", msg.javaClass.simpleName)
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("S3 request failed", cause)
        promise.tryFailure(cause)
        ctx.pipeline().remove(this)
    }

    private fun copyToByteArray(buf: ByteBuf): ByteArray {
        val bytes = ByteArray(buf.readableBytes())
        buf.getBytes(buf.readerIndex(), bytes)
        return bytes
    }
}

/**
 * 流式模式 Handler：将 chunk 通过 [S3StreamConsumer] 回调传递。
 * 用于大文件 GET（支持 Range）。
 */
class S3StreamingResponseHandler(
    private val consumer: S3StreamConsumer,
    private val promise: Promise<Unit>,
) : ChannelInboundHandlerAdapter() {

    private val logger = LoggerFactory.getLogger(S3StreamingResponseHandler::class.java)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is HttpResponse -> {
                val shouldContinue = consumer.onHeaders(msg)
                if (!shouldContinue) {
                    ctx.close()
                    promise.trySuccess(Unit)
                    ctx.pipeline().remove(this)
                }
            }
            is HttpContent -> {
                val retained = msg.content().retain()
                consumer.onContent(retained)
                if (msg is LastHttpContent) {
                    consumer.onComplete()
                    promise.trySuccess(Unit)
                    ctx.pipeline().remove(this)
                }
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error("S3 streaming request failed", cause)
        consumer.onError(cause)
        promise.tryFailure(cause)
        ctx.pipeline().remove(this)
    }
}
