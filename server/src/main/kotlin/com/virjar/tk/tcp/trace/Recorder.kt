package com.virjar.tk.tcp.trace

import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.util.*
import java.util.function.Supplier

/**
 * 1. 在Netty中，线程为多个NIO EventLoop，传统基于线程变量的日志上下文在Netty将会失去上下文意义，这是因为Netty负载的逻辑在频繁切换线程（他是事件驱动，一个小的片段仅仅推动某个业务流程一个小状态的流程前进）
 * 2. Netty是海量网络流量处理，完整打印全部日志数量非常大。所以大概率需要采样。但是采样获得的日志实际上丢失了完整上下文，随机采样大概率某个流程没有完整的trace
 * 3. 对于NIO场景，日志写入可能也是引起请求阻塞的，毕竟一个线程可能处理了上百万个用户连接，此时即使是写日志浪费的时间产生的影响也会被放大
 *
 *  解决方案
 * 专门设计一个日志打印对象，他自TCP连接创建后产生，存储于netty各种handler处理器之间、绑定在tcp连接上下文之上，包含tcp身份（用户、device等），
 * 提供基于确定用户全流程采样功能、提供固定空间大小的采样槽位进行基于用户维护的采样，保证在海量用户情况不会全部打印日志，然而被开启采样的设备，将会保持完整的采样日志。
 * 采样系统使用懒加载接口获取日志消息，这避免日志消息在没有开启采样的时候也发生了日志拼接，进而产生日志碎片和无意义的外部调用
 *
 * 需要注意，在懒加载和异步构建之后，日志消息将会在未来惰性求值、对于GC敏感型对象需要避免惰性求值带来gc hold，这很可能导致内存无法快速回收
 */
class Recorder {
    companion object {
        private const val MAX_CACHE_SIZE = 30
        private val NETTY_ATTR = AttributeKey.newInstance<Recorder>("TRACE_RECORD")

        @Synchronized
        fun touch(channel: Channel): Recorder {
            val attr = channel.attr(NETTY_ATTR)
            var recorder = attr.get()
            if (recorder != null) {
                return recorder
            }
            recorder = Recorder()
            attr.set(recorder)

            channel.closeFuture().addListener {
                recorder.record("tcp connection closed")
                recorder.release()
            }

            return recorder
        }
    }

    private val cache: MutableList<RecordEntry> = Collections.synchronizedList(LinkedList())
    private var writer: LogWriter? = null

    fun record(message: String, throwable: Throwable? = null) {
        record({ message }, throwable)
    }

    fun record(message: Supplier<String>) {
        record(message, null)
    }

    fun record(message: Supplier<String>, throwable: Throwable? = null) {
        val w = writer
        if (w != null) {
            w.write(message, throwable)
            return
        }
        if (cache.size == MAX_CACHE_SIZE) {
            cache.add(RecordEntry({
                "trace message buffer overflow, some trace log has been dropped"
            }, null))
            return
        }
        if (cache.size > MAX_CACHE_SIZE) {
            return
        }
        cache.add(RecordEntry(message, throwable))
    }

    /**
     * 可以在确定当前采样工作生效之后再进入日志打印流程，避免无意义的计算
     */
    fun enable(): Boolean {
        return writer?.enable() ?: true
    }

    fun upgrade(uid: String, deviceId: String) {
        val acquireWriter = SamplingManager.acquireWriter(false, uid, deviceId)
        if (acquireWriter.enable()) {
            for (entry in cache) {
                acquireWriter.write(entry.message, entry.throwable)
            }
        }
        writer = acquireWriter
        cache.clear()
    }

    fun release() {
        writer?.apply {
            SamplingManager.releaseWriter(this)
        }
        // todo 没有认证成功的时候，不会调用upgrade就release，此时这里的日志应该刷到磁盘下,因为这是认证失败的case，这种量级不大，但是需要被记录
        cache.clear()
    }
}


interface LogWriter {
    /**
     *  需要注意，在懒加载和异步构建之后，日志消息将会在未来惰性求值、对于GC敏感型对象需要避免惰性求值带来gc hold，这很可能导致内存无法快速回收.
     *  所以netty的连接层面的对象参与日志，比如立即求值（如：channel、ChannelContext等）
     *
     */
    fun write(message: Supplier<String>, throwable: Throwable? = null)
    fun enable(): Boolean
}

private data class RecordEntry(
    val message: Supplier<String>,
    val throwable: Throwable?
)
