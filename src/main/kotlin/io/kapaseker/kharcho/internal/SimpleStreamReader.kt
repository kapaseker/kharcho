package io.kapaseker.kharcho.internal

import io.kapaseker.kharcho.annotations.Nullable
import io.kapaseker.kharcho.helper.Validate
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * A simple decoding InputStreamReader that recycles internal buffers.
 */
class SimpleStreamReader(private val `in`: InputStream, charset: Charset) : Reader() {
    private val decoder: CharsetDecoder

    @Nullable
    private var byteBuf: ByteBuffer? // null after close

    init {
        this.decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val buf: ByteArray =
            SimpleBufferedInput.Companion.BufferPool.borrow() // shared w/ SimpleBufferedInput, ControllableInput
        byteBuf = ByteBuffer.wrap(buf)
        byteBuf!!.flip() // limit(0)
    }

    @Throws(IOException::class)
    override fun read(charArray: CharArray, off: Int, len: Int): Int {
        Validate.notNull(byteBuf!!) // can't read after close
        var charBuf = CharBuffer.wrap(charArray, off, len)
        if (charBuf.position() != 0) charBuf = charBuf.slice()

        var readFully = false
        while (true) {
            val result = decoder.decode(byteBuf, charBuf, readFully)
            if (result.isUnderflow()) {
                if (readFully || !charBuf.hasRemaining() || (charBuf.position() > 0) && `in`.available() <= 0) break
                val read = bufferUp()
                if (read < 0) {
                    readFully = true
                    if ((charBuf.position() == 0) && (!byteBuf!!.hasRemaining())) break
                }
                continue
            }
            if (result.isOverflow()) break
            result.throwException()
        }

        if (readFully) decoder.reset()
        if (charBuf.position() == 0) {
            return if (readFully) -1 else 0 // 0 if there was a surrogate and reader tried to read only 1.
        }
        return charBuf.position()
    }

    @Throws(IOException::class)
    private fun bufferUp(): Int {
        checkNotNull(byteBuf) // already validated ^
        byteBuf!!.compact()
        try {
            val pos = byteBuf!!.position()
            val remaining = (byteBuf!!.limit() - pos)
            val read = `in`.read(byteBuf!!.array(), byteBuf!!.arrayOffset() + pos, remaining)
            if (read < 0) return read
            if (read == 0) throw IOException("Underlying input stream returned zero bytes")
            byteBuf!!.position(pos + read)
        } finally {
            byteBuf!!.flip()
        }
        return byteBuf!!.remaining()
    }

    @Throws(IOException::class)
    override fun close() {
        if (byteBuf == null) return
        SimpleBufferedInput.Companion.BufferPool.release(byteBuf!!.array())
        byteBuf = null
        `in`.close()
    }
}
