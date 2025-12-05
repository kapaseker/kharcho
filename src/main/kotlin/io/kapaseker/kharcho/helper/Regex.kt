package io.kapaseker.kharcho.helper

import io.kapaseker.kharcho.internal.SharedConstants
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A regular expression abstraction. Allows jsoup to optionally use the re2j regular expression engine (linear time)
 * instead of the JDK's backtracking regex implementation.
 *
 *
 * If the `com.google.re2j` library is found on the classpath, by default it will be used. You can override this
 * by setting `-Djsoup.useRe2j=false` to explicitly disable, and use the JDK regex engine.
 *
 *
 * (Currently this a simplified implementation for jsoup's specific use; can extend as required.)
 */
class Regex internal constructor(private val jdkPattern: Pattern) {
    fun matcher(input: CharSequence): Matcher {
        return JdkMatcher(jdkPattern.matcher(input))
    }

    override fun toString(): String {
        return jdkPattern.toString()
    }

    interface Matcher {
        fun find(): Boolean
    }

    private class JdkMatcher(private val delegate: java.util.regex.Matcher) : Matcher {
        override fun find(): Boolean {
            return delegate.find()
        }
    }

    companion object {
        private val hasRe2j: Boolean = hasRe2j()

        /**
         * Compile a regex, using re2j if enabled and available; otherwise JDK regex.
         *
         * @param regex the regex to compile
         * @return the compiled regex
         * @throws ValidationException if the regex is invalid
         */
        @JvmStatic
        fun compile(regex: String): Regex {
            try {
                return Regex(Pattern.compile(regex))
            } catch (e: PatternSyntaxException) {
                throw ValidationException("Pattern syntax error: " + e.message)
            }
        }

        /** Wraps an existing JDK Pattern (for API compat); doesn't switch  */
        fun fromPattern(pattern: Pattern): Regex {
            return Regex(pattern)
        }

        /**
         * Checks if re2j is available (on classpath) and enabled (via system property).
         * @return true if re2j is available and enabled
         */
        fun usingRe2j(): Boolean {
            return hasRe2j && wantsRe2j()
        }

        fun wantsRe2j(): Boolean {
            return System.getProperty(SharedConstants.UseRe2j, "true").toBoolean()
        }

        fun wantsRe2j(use: Boolean) {
            System.setProperty(SharedConstants.UseRe2j, use.toString())
        }

        fun hasRe2j(): Boolean {
            try {
                val re2 = Class.forName(
                    "com.google.re2j.Pattern",
                    false,
                    Regex::class.java.getClassLoader()
                ) // check if re2j is in classpath
                try {
                    // if it is, and we are on JVM9+, we need to dork around with modules, because re2j doesn't publish a module name.
                    // done via reflection so we can still run on JVM 8.
                    // todo remove if re2j publishes as a module
                    val moduleCls = Class.forName("java.lang.Module")
                    val getModule = Class::class.java.getMethod("getModule")
                    val jsoupMod = getModule.invoke(Regex::class.java)
                    val re2Mod = getModule.invoke(re2)
                    val reads = moduleCls.getMethod("canRead", moduleCls)
                        .invoke(jsoupMod, re2Mod) as Boolean
                    if (!reads) moduleCls.getMethod("addReads", moduleCls).invoke(jsoupMod, re2Mod)
                } catch (ignore: ClassNotFoundException) {
                    // jvm8 - no Module class; so we can use as-is
                }
                return true
            } catch (e: ClassNotFoundException) {
                return false // no re2j
            } catch (e: ReflectiveOperationException) {
                // unexpectedly couldn’t wire modules on 9+; return false to avoid IllegalAccessError later
                System.err.println("Warning: (bug? please report) couldn't access re2j from jsoup due to modules: " + e)
                return false
            }
        }
    }
}
