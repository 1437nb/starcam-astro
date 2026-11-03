package com.starcam.astro.astro

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class CancellableHttpTest {
    private fun exercise(headers: Boolean, action: suspend (okhttp3.Call, CountDownLatch) -> Unit) = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val ready = CountDownLatch(1); val release = CountDownLatch(1)
        val thread = Thread {
            try { server.accept().use { socket ->
                if (headers) socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx".toByteArray()); flush()
                }
                ready.countDown(); release.await(5, TimeUnit.SECONDS)
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
        try { withTimeout(3000) { action(call, ready) } }
        finally {
            call.cancel(); release.countDown(); server.close(); thread.join(1000)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test fun cancellationInterruptsARequestWaitingForHeaders() = exercise(false) { call, ready ->
        coroutineScope {
            val pending = launch { call.awaitText() }
            assertTrue(withContext(Dispatchers.IO) { ready.await(2, TimeUnit.SECONDS) })
            pending.cancelAndJoin(); assertTrue(call.isCanceled())
        }
    }

    @Test fun cancellationStillInterruptsAfterHeadersWhileBodyIsStalled() = exercise(true) { call, ready ->
        coroutineScope {
            val pending = launch { call.awaitText() }
            assertTrue(withContext(Dispatchers.IO) { ready.await(2, TimeUnit.SECONDS) })
            pending.cancelAndJoin(); assertTrue(call.isCanceled())
        }
    }

    @Test fun deadlineCancelsTheUnderlyingCall() = exercise(true) { call, _ ->
        assertNull(withTimeoutOrNull(100) { call.awaitText() })
        assertTrue(call.isCanceled())
    }

    @Test fun tokenCancellationAloneStopsTheUnderlyingCall() = exercise(true) { call, ready ->
        coroutineScope {
            val token = SolveToken()
            val pending = launch { withSolveCancellation(token) { call.awaitText() } }
            assertTrue(withContext(Dispatchers.IO) { ready.await(2, TimeUnit.SECONDS) })
            token.cancel()
            withTimeout(1000) { pending.join() }
            assertTrue(call.isCanceled())
        }
    }
}
