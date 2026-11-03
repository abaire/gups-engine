package gups.net

import gups.core.ClientInputEvent
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.WorldInbox
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A client whose unsent output passes this many bytes is disconnected. */
const val OUTPUT_BUFFER_MAX = 256 * 1024

/**
 * A telnet listener on Netty: raw telnet, or telnet over TLS when [tls] is set, the two sharing one
 * pipeline with an optional `SslHandler` in front. All socket I/O runs on Netty's event loops; the
 * world only ever sees [ClientInputEvent]s and per-session [OutputSink]s that never block it.
 * Option negotiation and framing stay in [TelnetConnection], byte in and byte out.
 */
class TelnetServer(
  private val inbox: WorldInbox,
  private val host: String = "127.0.0.1",
  private val port: Int = 4000,
  /** Shared with other listeners so session ids never collide. */
  private val sessionIds: AtomicLong = AtomicLong(1),
  /** Telnet over TLS; null serves raw telnet. */
  private val tls: ServerTls? = null,
) {
  private var boss: EventLoopGroup? = null
  private var workers: EventLoopGroup? = null
  private var channel: Channel? = null

  /** `telnet` or `tls`, as sessions report their transport. */
  val transport: String
    get() = if (tls != null) "tls" else "telnet"

  /** Binds and starts accepting. Returns the bound port (useful with port 0). */
  suspend fun start(): Int =
    withContext(Dispatchers.IO) {
      val b = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()).also { boss = it }
      val w = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory()).also { workers = it }
      val bound =
        ServerBootstrap()
          .group(b, w)
          .channel(NioServerSocketChannel::class.java)
          .childOption(ChannelOption.TCP_NODELAY, true)
          .childHandler(
            object : ChannelInitializer<SocketChannel>() {
              override fun initChannel(ch: SocketChannel) {
                tls?.let { ch.pipeline().addLast("tls", it.context().newHandler(ch.alloc())) }
                ch.pipeline().addLast("telnet", Connection())
              }
            }
          )
          .bind(host, port)
          .sync()
          .channel()
      channel = bound
      (bound.localAddress() as InetSocketAddress).port
    }

  fun stop() {
    runCatching { channel?.close()?.syncUninterruptibly() }
    for (g in listOfNotNull(boss, workers)) runCatching {
      g.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly()
    }
  }

  /** One client: bridges Netty's bytes to a [TelnetConnection] and a world session. */
  private inner class Connection : ChannelInboundHandlerAdapter() {
    private val id = SessionId(sessionIds.getAndIncrement())
    private lateinit var ch: Channel
    private val buffered = AtomicLong()
    private val closed = AtomicBoolean(false)
    private var begun = false
    private val conn = TelnetConnection { enqueue(it) }

    private fun enqueue(bytes: ByteArray) {
      if (closed.get() || bytes.isEmpty()) return
      if (buffered.addAndGet(bytes.size.toLong()) > OUTPUT_BUFFER_MAX) {
        // A slow client: drop it; the character goes link-dead.
        if (closed.compareAndSet(false, true)) {
          ch.close()
          inbox.submit(WorldControlEvent.Disconnect(id))
        }
        return
      }
      ch.writeAndFlush(Unpooled.wrappedBuffer(bytes)).addListener {
        buffered.addAndGet(-bytes.size.toLong())
      }
    }

    private val sink =
      object : OutputSink {
        override fun send(text: String) = enqueue(conn.encodeText(text))

        override fun prompt(text: String) = enqueue(conn.encodePrompt(text))

        override fun hideInput(hidden: Boolean) = enqueue(conn.encodeEcho(hidden))

        override fun gmcp(pkg: String, data: Any?) {
          if (conn.wantsGmcp(pkg)) enqueue(conn.encodeGmcp(pkg, data))
        }

        override fun wantsGmcp(pkg: String): Boolean = conn.wantsGmcp(pkg)

        override fun close(reason: String) {
          if (closed.get()) return
          enqueue(conn.encodeText(reason + "\n"))
          if (closed.compareAndSet(false, true))
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
        }
      }

    override fun channelActive(ctx: ChannelHandlerContext) {
      ch = ctx.channel()
      // Over TLS the session starts once the handshake succeeds, so failed handshakes make none.
      if (tls == null) begin()
      ctx.fireChannelActive()
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
      if (evt is SslHandshakeCompletionEvent) {
        if (evt.isSuccess) begin() else ctx.close()
      }
      ctx.fireUserEventTriggered(evt)
    }

    private fun begin() {
      if (begun) return
      begun = true
      conn.start()
      val remote = (ch.remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
      inbox.submit(WorldControlEvent.Connect(id, sink, remote, transport))
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
      val buf = msg as ByteBuf
      try {
        if (!begun || closed.get()) return
        val bytes = ByteArray(buf.readableBytes()).also(buf::readBytes)
        conn.receive(bytes, { pkg, body -> inbox.submit(gmcpEvent(id, pkg, body)) }) {
          line,
          truncated ->
          if (truncated)
            sink.send("Your line was longer than $MAX_LINE_BYTES bytes and was cut short.\n")
          inbox.submit(ClientInputEvent(id, line, System.nanoTime()))
        }
        if (conn.errors >= MAX_PROTOCOL_ERRORS) sink.close("Too many malformed telnet sequences.")
      } finally {
        buf.release()
      }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
      closed.set(true)
      // A dropped link makes the character link-dead; after a world-side close it is a no-op.
      if (begun) inbox.submit(WorldControlEvent.Disconnect(id))
      ctx.fireChannelInactive()
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
      // Connection resets, bad TLS records and the like: treated as a dropped link.
      ctx.close()
    }
  }
}
