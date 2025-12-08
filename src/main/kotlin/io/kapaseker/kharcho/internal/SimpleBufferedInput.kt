package io.kapaseker.kharcho.internal

import io.kapaseker.kharcho.helper.Validate
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.function.Supplier
import kotlin.math.min

/**
 * A simple implementation of a buffered input stream, in which we can control the byte[] buffer to recycle it. Not safe for
 * use between threads; no sync or locks. The buffer is borrowed on initial demand in fill.
 * @since 1.18.2
 */
internal class SimpleBufferedInput(inStream: InputStream?) : FilterInputStream(inStream) {
    private var byteBuf:ByteArray? = null // the byte buffer; recycled via SoftPool. Created in fill if required
    private var bufPos = 0
    private var bufLength = 0
    private var bufMark = -1
    private var inReadFully = false // true when the underlying inputstream has been read fully

    init {
        if (inStream == null) inReadFully = true // effectively an empty stream
    }

    @Throws(IOException::class)
    override fun read(): Int {
        if (bufPos >= bufLength) {
            fill()
            if (bufPos >= bufLength) return -1
        }
        return this.buf!![bufPos++].toInt() and 0xff
    }

    @Throws(IOException::class)
    override fun read(dest: ByteArray, offset: Int, desiredLen: Int): Int {
        Validate.notNull(dest)
        if (offset < 0 || desiredLen < 0 || desiredLen > dest.size - offset) {
            throw IndexOutOfBoundsException()
        } else if (desiredLen == 0) {
            return 0
        }

        var bufAvail = bufLength - bufPos
        if (bufAvail <= 0) { // can't serve from the buffer
            if (!inReadFully && bufMark < 0) {
                // skip creating / copying into a local buffer; just pass through
                val read = `in`.read(dest, offset, desiredLen)
                closeIfDone(read)
                return read
            }
            fill()
            bufAvail = bufLength - bufPos
        }

        val read = min(bufAvail, desiredLen)
        if (read <= 0) {
            return -1
        }

        System.arraycopy(this.buf, bufPos, dest, offset, read)
        bufPos += read
        return read
    }

    @Throws(IOException::class)
    private fun fill() {
        if (inReadFully) return
        if (byteBuf == null) { // get one on first demand
            byteBuf = BufferPool.borrow()
        }

        if (bufMark < 0) { // no mark, can lose buffer (assumes we've read to bufLen)
            bufPos = 0
        } else if (bufPos >= BufferSize) { // no room left in buffer
            if (bufMark > 0) { // can throw away early part of the buffer
                val size = bufPos - bufMark
                System.arraycopy(byteBuf, bufMark, byteBuf, 0, size)
                bufPos = size
                bufMark = 0
            } else { // invalidate mark
                bufMark = -1
                bufPos = 0
            }
        }
        bufLength = bufPos
        var read = `in`.read(byteBuf, bufPos, byteBuf!!.size - bufPos)
        if (read > 0) {
            bufLength = read + bufPos
            while (byteBuf!!.size - bufLength > 0) { // read in more if we have space, without blocking
                if (`in`.available() < 1) break
                read = `in`.read(byteBuf, bufLength, byteBuf!!.size - bufLength)
                if (read <= 0) break
                bufLength += read
            }
        }
        closeIfDone(read)
    }

    @Throws(IOException::class)
    private fun closeIfDone(read: Int) {
        if (read == -1) {
            inReadFully = true
            super.close() // close underlying stream immediately; frees resources a little earlier
        }
    }

    val buf: ByteArray?
        get() {
            Validate.notNull(byteBuf!!)
            return byteBuf
        }

    /**
     * Check if the underlying InputStream has been read fully. There may still content in this buffer to be consumed.
     * @return true if the underlying inputstream has been read fully.
     */
    fun baseReadFully(): Boolean {
        return inReadFully
    }

    @Throws(IOException::class)
    override fun available(): Int {
        if (byteBuf != null && bufLength - bufPos > 0) return bufLength - bufPos // doesn't include those in.available(), but mostly used as a block test

        return if (inReadFully) 0 else `in`.available()
    }

    override fun mark(readlimit: Int) {
        require(readlimit <= BufferSize) { "Read-ahead limit is greater than buffer size" }
        bufMark = bufPos
    }

    @Throws(IOException::class)
    override fun reset() {
        if (bufMark < 0) throw IOException("Resetting to invalid mark")
        bufPos = bufMark
    }

    @Throws(IOException::class)
    override fun close() {
        if (`in` != null) super.close()
        val byteBuf = byteBuf ?: return

        BufferPool.release(byteBuf) // return the buffer to the pool
        this.byteBuf = null // NPE further attempts to read
    }

    companion object {
        val BufferSize: Int = SharedConstants.DefaultBufferSize
        val BufferPool: SoftPool<ByteArray> =
            SoftPool<ByteArray>(Supplier { ByteArray(BufferSize) })
    }
}
