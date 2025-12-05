package io.kapaseker.kharcho.internal

import io.kapaseker.kharcho.Progress
import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Validate
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * A jsoup internal class (so don't use it as there is no contract API) that enables controls on a buffered input stream,
 * namely a maximum read size, and the ability to Thread.interrupt() the read.
 */
// reimplemented from ConstrainableInputStream for JDK21 - extending BufferedInputStream will pin threads during read
class ControllableInputStream private constructor(`in`: SimpleBufferedInput, maxSize: Int) :
    FilterInputStream(`in`) {
    private val buff: SimpleBufferedInput // super.in, but typed as SimpleBufferedInput
    private var maxSize: Int
    private var startTime: Long
    private var timeout: Long = 0 // optional max time of request
    private var remaining: Int
    private var markPos: Int
    private var interrupted = false
    private var allowClose =
        true // for cases where we want to re-read the input, can ignore .close() from the parser

    // if we are tracking progress, will have the expected content length, progress callback, connection
    @Nullable
    private var progress: Progress<*>? = null

    @Nullable
    private var progressContext: Any? = null
    private var contentLength = -1
    private var readPos = 0 // amount read; can be reset()

    init {
        Validate.isTrue(maxSize >= 0)
        buff = `in`
        this.maxSize = maxSize
        remaining = maxSize
        markPos = -1
        startTime = System.nanoTime()
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        var len = len
        if (readPos == 0) emitProgress() // emits a progress


        val capped = maxSize != 0
        if (interrupted || capped && remaining <= 0) return -1
        if (Thread.currentThread().isInterrupted()) {
            // interrupted latches, because parse() may call twice
            interrupted = true
            return -1
        }

        if (capped && len > remaining) len =
            remaining // don't read more than desired, even if available


        while (true) { // loop trying to read until we get some data or hit the overall timeout, if we have one
            if (expired()) throw SocketTimeoutException("Read timeout")

            try {
                val read = super.read(b, off, len)
                if (read == -1) { // completed
                    contentLength = readPos
                } else {
                    remaining -= read
                    readPos += read
                }
                emitProgress()
                return read
            } catch (e: SocketTimeoutException) {
                if (expired() || timeout == 0L) throw e
            }
        }
    }

    @Throws(IOException::class)
    override fun reset() {
        super.reset()
        remaining = maxSize - markPos
        readPos = markPos // readPos is used for progress emits
    }

    override fun mark(readlimit: Int) {
        super.mark(readlimit)
        markPos = maxSize - remaining
    }

    /**
     * Check if the underlying InputStream has been read fully. There may still content in buffers to be consumed, and
     * read methods may return -1 if hit the read limit.
     * @return true if the underlying inputstream has been read fully.
     */
    fun baseReadFully(): Boolean {
        return buff.baseReadFully()
    }

    /**
     * Get the max size of this stream (how far at most will be read from the underlying stream)
     * @return the max size
     */
    fun max(): Int {
        return maxSize
    }

    fun max(newMax: Int) {
        remaining += newMax - maxSize // update remaining to reflect the difference in the new maxsize
        maxSize = newMax
    }

    fun allowClose(allowClose: Boolean) {
        this.allowClose = allowClose
    }

    @Throws(IOException::class)
    override fun close() {
        if (allowClose) super.close()
    }

    fun timeout(startTimeNanos: Long, timeoutMillis: Long): ControllableInputStream {
        this.startTime = startTimeNanos
        this.timeout = timeoutMillis * 1000000
        return this
    }

    private fun emitProgress() {
        if (progress == null) return
        // calculate percent complete if contentLength > 0 (and cap to 100.0 if totalRead > contentLength):
        val percent = if (contentLength > 0) min(100f, readPos * 100f / contentLength) else 0f
        (progress as Progress<Any?>).onProgress(
            readPos,
            contentLength,
            percent,
            progressContext
        ) // (not actually unchecked - verified when set)
        if (percent == 100.0f) progress =
            null // detach once we reach 100%, so that any subsequent buffer hits don't report 100 again
    }

    fun <ProgressContext> onProgress(
        contentLength: Int,
        callback: Progress<ProgressContext?>,
        context: ProgressContext?
    ): ControllableInputStream {
        Validate.notNull(callback)
        Validate.notNull(context!!)
        this.contentLength = contentLength
        this.progress = callback
        this.progressContext = context
        return this
    }

    private fun expired(): Boolean {
        if (timeout == 0L) return false

        val now = System.nanoTime()
        val dur = now - startTime
        return (dur > timeout)
    }

    fun inputStream(): BufferedInputStream {
        // called via HttpConnection.Response.bodyStream(), needs an OG BufferedInputStream
        return BufferedInputStream(buff)
    }

    companion object {
        /**
         * If this InputStream is not already a ControllableInputStream, let it be one.
         * @param `in` the input stream to (maybe) wrap. A `null` input will create an empty wrapped stream.
         * @param maxSize the maximum size to allow to be read. 0 == infinite.
         * @return a controllable input stream
         */
        fun wrap(inStream: InputStream?, maxSize: Int): ControllableInputStream {
            // bufferSize currently unused; consider implementing as a min size in the SoftPool recycler
            if (inStream is ControllableInputStream) return inStream
            else return ControllableInputStream(SimpleBufferedInput(inStream), maxSize)
        }

        /**
         * If this InputStream is not already a ControllableInputStream, let it be one.
         * @param in the input stream to (maybe) wrap
         * @param bufferSize the buffer size to use when reading
         * @param maxSize the maximum size to allow to be read. 0 == infinite.
         * @return a controllable input stream
         */
        fun wrap(`in`: InputStream, bufferSize: Int, maxSize: Int): ControllableInputStream {
            // todo - bufferSize currently unused; consider implementing as a min size in the SoftPool recycler; or just deprecate if always DefaultBufferSize
            return wrap(`in`, maxSize)
        }

        /**
         * Reads this inputstream to a ByteBuffer. The supplied max may be less than the inputstream's max, to support
         * reading just the first bytes.
         */
        @Throws(IOException::class)
        fun readToByteBuffer(`in`: InputStream, max: Int): ByteBuffer {
            Validate.isTrue(max >= 0, "maxSize must be 0 (unlimited) or larger")
            Validate.notNull(`in`)
            val capped = max > 0
            val readBuf: ByteArray =
                SimpleBufferedInput.Companion.BufferPool.borrow() // Share the same byte[] pool as SBI
            val outSize = if (capped) min(
                max,
                SharedConstants.DefaultBufferSize
            ) else SharedConstants.DefaultBufferSize
            var outBuf = ByteBuffer.allocate(outSize)

            try {
                var remaining = max
                var read: Int
                while ((`in`.read(
                        readBuf,
                        0,
                        if (capped) min(
                            remaining,
                            SharedConstants.DefaultBufferSize
                        ) else SharedConstants.DefaultBufferSize
                    ).also { read = it }) != -1
                ) {
                    if (outBuf.remaining() < read) { // needs to grow
                        val newCapacity = max(
                            outBuf.capacity() * 1.5,
                            (outBuf.capacity() + read).toDouble()
                        ).toInt()
                        val newBuffer = ByteBuffer.allocate(newCapacity)
                        outBuf.flip()
                        newBuffer.put(outBuf)
                        outBuf = newBuffer
                    }
                    outBuf.put(readBuf, 0, read)
                    if (capped) {
                        remaining -= read
                        if (remaining <= 0) break
                    }
                }
                outBuf.flip() // Prepare the buffer for reading
                return outBuf
            } finally {
                SimpleBufferedInput.Companion.BufferPool.release(readBuf)
            }
        }
    }
}
