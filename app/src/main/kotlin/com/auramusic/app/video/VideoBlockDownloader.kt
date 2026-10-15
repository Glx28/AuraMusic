/**
 * Auramusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.auramusic.app.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Fetches a file from a remote by taking it in blocks, several at a time.
 *
 * One connection for a whole video is one connection's worth of a connection: the
 * stream is answered as fast as one request can be made, however fast the link
 * underneath it happens to be. Splitting the file into blocks and fetching several
 * concurrently uses what the connection actually has, and a block that fails is
 * fetched again on its own rather than failing the whole transfer with everything
 * already downloaded.
 *
 * Each block is written straight to its own offset in the file, so a block can be
 * retried without re-reading or re-writing what has already landed.
 */
class VideoBlockDownloader(
    private val clientProvider: (Int) -> OkHttpClient,
) {

    /**
     * Downloads [url] into [target].
     *
     * [totalBytes] is what the caller believes the file is. When the remote disagrees,
     * the remote is believed and [onTotalChanged] is called, because a percentage
     * against the wrong total is worse than a percentage that settles a moment later.
     *
     * Returns the number of bytes written, or throws if the transfer could not be
     * completed - a caller that wants to try again gets its chance.
     */
    suspend fun download(
        url: String,
        target: File,
        totalBytes: Long,
        threads: Int,
        onBytes: (written: Long, total: Long) -> Unit,
        onTotalChanged: (Long) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        // The remote caps some stream URLs by embedding a range in them. Left in
        // place it would cap every block to the same few bytes no matter what each one
        // asks for, so the blocks would all arrive holding the head of the file.
        val baseUrl = stripRangeParam(url)
        val client = clientProvider(threads)

        var knownTotal = totalBytes
        if (knownTotal <= 0L) {
            knownTotal = lengthOf(url, baseUrl, client)
            if (knownTotal > 0L) onTotalChanged(knownTotal)
        }
        if (knownTotal <= 0L) {
            // Nothing to divide up. A single stream is still better than none.
            return@withContext downloadInOnePiece(url, target, client, onBytes, knownTotal)
        }

        val blockCount = ((knownTotal + BLOCK_SIZE - 1) / BLOCK_SIZE).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        prepareFile(target, knownTotal)

        val written = AtomicInteger(0)
        val nextBlock = AtomicInteger(0)
        val failed = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        coroutineScope {
            val workers = min(threads.coerceAtLeast(1), blockCount)
            repeat(workers) {
                launch(Dispatchers.IO) {
                    while (true) {
                        if (failed.get() != null) return@launch
                        val block = nextBlock.getAndIncrement()
                        if (block >= blockCount) return@launch
                        val start = block.toLong() * BLOCK_SIZE
                        val end = min(start + BLOCK_SIZE, knownTotal) - 1
                        val size = fetchBlock(baseUrl, start, end, target, client)
                        if (size < 0) {
                            // A plain failure rather than a cancellation: this is a
                            // block that could not be had, not a transfer that was
                            // called off, and the caller decides what to do about it.
                            failed.compareAndSet(
                                null,
                                IOException("block $block of ${target.name} could not be fetched"),
                            )
                            return@launch
                        }
                        written.addAndGet(size.toInt())
                        onBytes(written.get().toLong(), knownTotal)
                    }
                }
            }
        }

        failed.get()?.let { throw it }
        written.get().toLong().coerceAtLeast(knownTotal)
    }

    /**
     * One block, retried rather than given up on.
     *
     * A single request failing is a moment of a bad connection rather than the end of
     * the transfer: the whole point of blocks is that the rest of the file is already
     * safely on disk while this one is being tried again. It gives up after
     * [MAX_ATTEMPTS], by which time something larger is wrong.
     */
    private fun fetchBlock(
        url: String,
        start: Long,
        end: Long,
        target: File,
        client: OkHttpClient,
    ): Long {
        var attempt = 0
        var backoff = INITIAL_BACKOFF_MS
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val outcome = runCatching {
                client.newCall(rangedRequest(url, start, end)).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty body")
                    RandomAccessFile(target, "rw").use { file ->
                        file.seek(start)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            var moved = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                file.write(buffer, 0, read)
                                moved += read
                            }
                            moved
                        }
                    }
                }
            }
            outcome.getOrNull()?.let { return it }
            if (attempt >= MAX_ATTEMPTS) break
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return -1L
    }

    /** The whole file down one connection, for a remote that will not say how big it is. */
    private suspend fun downloadInOnePiece(
        url: String,
        target: File,
        client: OkHttpClient,
        onBytes: (Long, Long) -> Unit,
        total: Long,
    ): Long = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        var moved = 0L
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty body")
            val length = body.contentLength().coerceAtLeast(0L)
            body.byteStream().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        moved += read
                        onBytes(moved, if (total > 0L) total else moved)
                    }
                }
            }
        }
        moved
    }

    /**
     * How long the file is, without starting it.
     *
     * A stream URL usually states its own length in the address, which costs nothing
     * to read; only when it does not is a request made to ask. The embedded one is
     * taken from the address as it arrived, before the range is stripped off it.
     */
    fun lengthOf(originalUrl: String, strippedUrl: String, client: OkHttpClient): Long {
        lengthFromUrl(originalUrl)?.let { return it }
        return runCatching {
            client.newCall(Request.Builder().url(strippedUrl).head().build()).execute().use { response ->
                if (!response.isSuccessful) return@use -1L
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L } ?: -1L
            }
        }.getOrDefault(-1L)
    }

    companion object {
        /** Small enough that a lost connection costs little, large enough to be worth a request. */
        private const val BLOCK_SIZE = 2L * 1024 * 1024
        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_BACKOFF_MS = 1_500L
        private const val MAX_BACKOFF_MS = 15_000L

        /** How many blocks are fetched at once. */
        const val DEFAULT_THREADS = 3

        fun prepareFile(file: File, length: Long) {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() != length) raf.setLength(length)
            }
        }

        private fun rangedRequest(url: String, start: Long, end: Long): Request =
            Request.Builder()
                .url(url)
                .header("Range", "bytes=$start-$end")
                .build()

        /** Reads a length stated in the address itself, if there is one. */
        fun lengthFromUrl(url: String): Long = runCatching {
            val query = url.substringAfter('?', "").substringBefore('#')
            query.split('&')
                .firstOrNull { it.startsWith("clen=") }
                ?.substringAfter('=')
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: -1L
        }.getOrDefault(-1L)

        /**
         * Removes a range that was embedded in the address.
         *
         * Stream URLs sometimes carry one, and leaving it in place caps every block
         * this fetches to the same span however much each one asks for.
         */
        fun stripRangeParam(url: String): String {
            val fragmentIndex = url.indexOf('#')
            val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
            val base = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
            val queryIndex = base.indexOf('?')
            if (queryIndex < 0) return url
            val path = base.substring(0, queryIndex)
            val kept = base.substring(queryIndex + 1)
                .split('&')
                .filter { it.isNotEmpty() && !it.startsWith("range=") }
            return if (kept.isEmpty()) path + fragment else path + "?" + kept.joinToString("&") + fragment
        }
    }
}