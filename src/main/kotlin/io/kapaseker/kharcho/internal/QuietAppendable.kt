package io.kapaseker.kharcho.internal

import io.kapaseker.kharcho.SerializationException
import java.io.IOException

/**
 * A jsoup internal class to wrap an Appendable and throw IOExceptions as SerializationExceptions.
 *
 * Only implements the appendable methods we actually use.
 */
abstract class QuietAppendable {

    abstract fun append(csq: CharSequence): QuietAppendable

    abstract fun append(c: Char): QuietAppendable

    abstract fun append(
        chars: CharArray,
        offset: Int,
        len: Int
    ): QuietAppendable // via StringBuilder, not Appendable

    class BaseAppendable constructor(private val a: Appendable) : QuietAppendable() {
        private fun interface Action {
            @Throws(IOException::class)
            fun append()
        }

        private fun quiet(action: Action): BaseAppendable {
            try {
                action.append()
            } catch (e: IOException) {
                throw SerializationException(e)
            }
            return this
        }

        override fun append(csq: CharSequence): BaseAppendable {
            return quiet(Action { a.append(csq) })
        }

        override fun append(c: Char): BaseAppendable {
            return quiet(Action { a.append(c) })
        }

        override fun append(chars: CharArray, offset: Int, len: Int): QuietAppendable {
            return quiet(Action { a.append(String(chars, offset, len)) })
        }
    }

    /** A version that wraps a StringBuilder, and so doesn't need the exception wrap.  */
    class StringBuilderAppendable constructor(private val sb: StringBuilder) :
        QuietAppendable() {
        override fun append(csq: CharSequence): StringBuilderAppendable {
            sb.append(csq)
            return this
        }

        override fun append(c: Char): StringBuilderAppendable {
            sb.append(c)
            return this
        }

        override fun append(chars: CharArray, offset: Int, len: Int): QuietAppendable {
            sb.append(chars, offset, len)
            return this
        }

        override fun toString(): String {
            return sb.toString()
        }
    }

    companion object {
        @JvmStatic
        fun wrap(a: Appendable): QuietAppendable {
            if (a is StringBuilder) return StringBuilderAppendable(a)
            else return BaseAppendable(a)
        }
    }
}
