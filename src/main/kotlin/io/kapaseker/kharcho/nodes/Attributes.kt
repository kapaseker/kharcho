package io.kapaseker.kharcho.nodes

import io.kapaseker.kharcho.helper.Validate
import io.kapaseker.kharcho.internal.Normalizer.lowerCase
import io.kapaseker.kharcho.internal.QuietAppendable
import io.kapaseker.kharcho.internal.SharedConstants
import io.kapaseker.kharcho.internal.SharedConstants.AttrRangeKey
import io.kapaseker.kharcho.internal.StringUtil
import io.kapaseker.kharcho.parser.ParseSettings
import org.jetbrains.annotations.Nullable
import java.util.*

/**
 * The attributes of an Element.
 *
 *
 * During parsing, attributes in with the same name in an element are deduplicated, according to the configured parser's
 * attribute case-sensitive setting. It is possible to have duplicate attributes subsequently if
 * [.add] vs [.put] is used.
 *
 *
 *
 * Attribute name and value comparisons are generally **case sensitive**. By default for HTML, attribute names are
 * normalized to lower-case on parsing. That means you should use lower-case strings when referring to attributes by
 * name.
 *
 *
 * @author Jonathan Hedley, jonathan@hedley.net
 */
class Attributes : Iterable<Attribute>, Cloneable {
    // the number of instance fields is kept as low as possible giving an object size of 24 bytes
    @JvmField
    var size: Int = 0 // number of slots used (not total capacity, which is keys.length). Package visible for actual size (incl internal)

    var keys: Array<String?> = Array<String?>(InitialCapacity) { "" } // keys is not null, but contents may be. Same for vals

    var vals: Array<Any?> = Array<Any?>(InitialCapacity) { "" } // Genericish: all non-internal attribute values must be Strings and are cast on access.

    // todo - make keys iterable without creating Attribute objects
    // check there's room for more
    private fun checkCapacity(minNewSize: Int) {
        Validate.isTrue(minNewSize >= size)
        val curCap = keys.size
        if (curCap >= minNewSize) return
        var newCap: Int = if (curCap >= InitialCapacity) size * GrowthFactor else InitialCapacity
        if (minNewSize > newCap) newCap = minNewSize

        keys = keys.copyOf<String?>(newCap)
        vals = vals.copyOf<Any?>(newCap)
    }

    fun indexOfKey(key: String): Int {
        Validate.notNull(key)
        for (i in 0..size) {
            if (key == keys[i]) return i
        }
        return NotFound
    }

    private fun indexOfKeyIgnoreCase(key: String): Int {
        Validate.notNull(key)
        for (i in 0..<size) {
            if (key.equals(keys[i], ignoreCase = true)) return i
        }
        return NotFound
    }

    /**
     * Get an attribute value by key.
     * @param key the (case-sensitive) attribute key
     * @return the attribute value if set; or empty string if not set (or a boolean attribute).
     * @see .hasKey
     */
    fun get(key: String): String? {
        val i = indexOfKey(key)
        return if (i == NotFound) EmptyString else checkNotNull(vals[i])
    }

    /**
     * Get an Attribute by key. The Attribute will remain connected to these Attributes, so changes made via
     * [Attribute.setKey], [Attribute.setValue] etc will cascade back to these Attributes and
     * their owning Element.
     * @param key the (case-sensitive) attribute key
     * @return the Attribute for this key, or null if not present.
     * @since 1.17.2
     */
    fun attribute(key: String): Attribute? {
        val i = indexOfKey(key)
        return if (i == NotFound) null else Attribute(key, checkNotNull(vals[i]), this)
    }

    /**
     * Get an attribute's value by case-insensitive key
     * @param key the attribute name
     * @return the first matching attribute value if set; or empty string if not set (ora boolean attribute).
     */
    fun getIgnoreCase(key: String): String {
        val i = indexOfKeyIgnoreCase(key)
        return if (i == NotFound) EmptyString else checkNotNull(vals[i])
    }

    /**
     * Adds a new attribute. Will produce duplicates if the key already exists.
     * @see Attributes.put
     */
    fun add(key: String, value: String?): Attributes {
        addObject(key, value)
        return this
    }

    private fun addObject(key: String, value: Any?) {
        checkCapacity(size + 1)
        keys[size] = key!!
        vals[size] = value
        size++
    }

    /**
     * Set a new attribute, or replace an existing one by key.
     * @param key case sensitive attribute key (not null)
     * @param value attribute value (which can be null, to set a true boolean attribute)
     * @return these attributes, for chaining
     */
    fun put(key: String, value: String?): Attributes {
        Validate.notNull(key)
        val i = indexOfKey(key)
        if (i != NotFound) vals[i] = value
        else addObject(key, value)
        return this
    }

    /**
     * Get the map holding any user-data associated with these Attributes. Will be created empty on first use. Held as
     * an internal attribute, not a field member, to reduce the memory footprint of Attributes when not used. Can hold
     * arbitrary objects; use for source ranges, connecting W3C nodes to Elements, etc.
     * @return the map holding user-data
     */
    fun userData(): MutableMap<String?, Any?> {
        val userData: MutableMap<String?, Any?>
        val i = indexOfKey(SharedConstants.UserDataKey)
        if (i == NotFound) {
            userData = HashMap<String?, Any?>()
            addObject(SharedConstants.UserDataKey, userData)
        } else {
            userData = vals[i] as MutableMap<String?, Any?>
        }
        kotlin.checkNotNull(userData)
        return userData
    }

    /**
     * Check if these attributes have any user data associated with them.
     */
    fun hasUserData(): Boolean {
        return hasKey(SharedConstants.UserDataKey)
    }

    /**
     * Get an arbitrary user-data object by key.
     * @param key case-sensitive key to the object.
     * @return the object associated to this key, or `null` if not found.
     * @see .userData
     * @since 1.17.1
     */
    fun userData(key: String): Any? {
        Validate.notNull(key)
        if (!hasUserData()) return null // no user data exists

        val userData = userData()
        return userData[key]
    }

    /**
     * Set an arbitrary user-data object by key. Will be treated as an internal attribute, so will not be emitted in HTML.
     * @param key case-sensitive key
     * @param value object value. Providing a `null` value has the effect of removing the key from the userData map.
     * @return these attributes
     * @see .userData
     * @since 1.17.1
     */
    fun userData(key: String, value: Any?): Attributes {
        Validate.notNull(key)
        if (value == null && !hasKey(SharedConstants.UserDataKey)) return this // no user data exists, so short-circuit

        val userData = userData()
        if (value == null) userData.remove(key)
        else userData[key] = value
        return this
    }

    fun putIgnoreCase(key: String, value: String?) {
        val i = indexOfKeyIgnoreCase(key)
        if (i != NotFound) {
            vals[i] = value
            val old = kotlin.checkNotNull(keys[i])
            if (old != key)  // case changed, update
                keys[i] = key
        } else addObject(key, value)
    }

    /**
     * Set a new boolean attribute. Removes the attribute if the value is false.
     * @param key case **insensitive** attribute key
     * @param value attribute value
     * @return these attributes, for chaining
     */
    fun put(key: String, value: Boolean): Attributes {
        if (value) putIgnoreCase(key, null)
        else remove(key)
        return this
    }

    /**
     * Set a new attribute, or replace an existing one by key.
     * @param attribute attribute with case-sensitive key
     * @return these attributes, for chaining
     */
    fun put(attribute: Attribute): Attributes {
        Validate.notNull(attribute)
        put(attribute.getKey(), attribute.value)
        attribute.parent = this
        return this
    }

    // removes and shifts up
    private fun remove(index: Int) {
        Validate.isFalse(index >= size)
        val shifted = size - index - 1
        if (shifted > 0) {
            System.arraycopy(keys, index + 1, keys, index, shifted)
            System.arraycopy(vals, index + 1, vals, index, shifted)
        }
        size--
        keys[size] = null // release hold
        vals[size] = null
    }

    /**
     * Remove an attribute by key. **Case sensitive.**
     * @param key attribute key to remove
     */
    fun remove(key: String) {
        val i = indexOfKey(key)
        if (i != NotFound) remove(i)
    }

    /**
     * Remove an attribute by key. **Case insensitive.**
     * @param key attribute key to remove
     */
    fun removeIgnoreCase(key: String) {
        val i = indexOfKeyIgnoreCase(key)
        if (i != NotFound) remove(i)
    }

    /**
     * Tests if these attributes contain an attribute with this key.
     * @param key case-sensitive key to check for
     * @return true if key exists, false otherwise
     */
    fun hasKey(key: String): Boolean {
        return indexOfKey(key) != NotFound
    }

    /**
     * Tests if these attributes contain an attribute with this key.
     * @param key key to check for
     * @return true if key exists, false otherwise
     */
    fun hasKeyIgnoreCase(key: String): Boolean {
        return indexOfKeyIgnoreCase(key) != NotFound
    }

    /**
     * Check if these attributes contain an attribute with a value for this key.
     * @param key key to check for
     * @return true if key exists, and it has a value
     */
    fun hasDeclaredValueForKey(key: String): Boolean {
        val i = indexOfKey(key)
        return i != NotFound && vals[i] != null
    }

    /**
     * Check if these attributes contain an attribute with a value for this key.
     * @param key case-insensitive key to check for
     * @return true if key exists, and it has a value
     */
    fun hasDeclaredValueForKeyIgnoreCase(key: String): Boolean {
        val i = indexOfKeyIgnoreCase(key)
        return i != NotFound && vals[i] != null
    }

    /**
     * Get the number of attributes in this set, excluding any internal-only attributes (e.g. user data).
     *
     * Internal attributes are excluded from the [.html], [.asList], and [.iterator]
     * methods.
     *
     * @return size
     */
    fun size(): Int {
        if (size == 0) return 0
        var count = 0
        for (i in 0..size) {
            if (keys[i]?.let { !isInternalKey(it) } ?: false) count++
        }
        return count
    }

    val isEmpty: Boolean
        /**
         * Test if this Attributes list is empty.
         *
         * This does not include internal attributes, such as user data.
         */
        get() = size() == 0

    /**
     * Add all the attributes from the incoming set to this set.
     * @param incoming attributes to add to these attributes.
     */
    fun addAll(incoming: Attributes) {
        val incomingSize = incoming.size() // not adding internal
        if (incomingSize == 0) return
        checkCapacity(size + incomingSize)

        val needsPut = size != 0 // if this set is empty, no need to check existing set, so can add() vs put()
        // (and save bashing on the indexOfKey()
        for (attr in incoming) {
            if (needsPut) put(attr)
            else addObject(attr.getKey(), attr.value)
        }
    }

    /**
     * Get the source ranges (start to end position) in the original input source from which this attribute's **name**
     * and **value** were parsed.
     *
     * Position tracking must be enabled prior to parsing the content.
     * @param key the attribute name
     * @return the ranges for the attribute's name and value, or `untracked` if the attribute does not exist or its range
     * was not tracked.
     * @see io.kapaseker.kharcho.parser.Parser.setTrackPosition
     * @see Attribute.sourceRange
     * @see Node.sourceRange
     * @see Element.endSourceRange
     * @since 1.17.1
     */
    fun sourceRange(key: String): Range.AttributeRange {
        if (!hasKey(key)) return Range.AttributeRange.UntrackedAttr
        val ranges: MutableMap<String, Range.AttributeRange> = this.ranges ?: return Range.AttributeRange.UntrackedAttr
        val range: Range.AttributeRange? = ranges[key]
        return range ?: Range.AttributeRange.UntrackedAttr
    }

    val ranges: MutableMap<String, Range.AttributeRange>?
        /** Get the Ranges, if tracking is enabled; null otherwise.  */
        get() = userData(AttrRangeKey) as? MutableMap<String, Range.AttributeRange>

    /**
     * Set the source ranges (start to end position) from which this attribute's **name** and **value** were parsed.
     * @param key the attribute name
     * @param range the range for the attribute's name and value
     * @return these attributes, for chaining
     * @since 1.18.2
     */
    fun sourceRange(key: String, range: Range.AttributeRange): Attributes {
        Validate.notNull(key)
        Validate.notNull(range)
        val ranges: MutableMap<String, Range.AttributeRange> = this.ranges ?: HashMap<String, Range.AttributeRange>().also { userData(AttrRangeKey, it) }
        ranges[key] = range
        return this
    }


    override fun iterator(): MutableIterator<Attribute> {
        return object : MutableIterator<Attribute> {
            var expectedSize: Int = size
            var i: Int = 0

            override fun hasNext(): Boolean {
                checkModified()
                while (i < size) {
                    val key = kotlin.checkNotNull(keys[i])
                    if (isInternalKey(key))  // skip over internal keys
                        i++
                    else break
                }

                return i < size
            }

            override fun next(): Attribute {
                checkModified()
                if (i >= size) throw NoSuchElementException()
                val key = kotlin.checkNotNull(keys[i])
                val attr = Attribute(key, vals[i] as String?, this@Attributes)
                i++
                return attr
            }

            fun checkModified() {
                if (size != expectedSize) throw ConcurrentModificationException("Use Iterator#remove() instead to remove attributes while iterating.")
            }

            override fun remove() {
                this@Attributes.remove(--i) // next() advanced, so rewind
                expectedSize--
            }
        }
    }

    /**
     * Get the attributes as a List, for iteration.
     * @return a view of the attributes as an unmodifiable List.
     */
    fun asList(): MutableList<Attribute?> {
        val list = ArrayList<Attribute?>(size)
        for (i in 0..<size) {
            val key = kotlin.checkNotNull(keys[i])
            if (isInternalKey(key)) continue  // skip internal keys

            val attr = Attribute(key, vals[i] as String?, this@Attributes)
            list.add(attr)
        }
        return Collections.unmodifiableList<Attribute?>(list)
    }

    /**
     * Retrieves a filtered view of attributes that are HTML5 custom data attributes; that is, attributes with keys
     * starting with `data-`.
     * @return map of custom data attributes.
     */
    fun dataset(): MutableMap<String?, String?> {
        return Dataset(this)
    }

    /**
     * Get the HTML representation of these attributes.
     * @return HTML
     */
    fun html(): String {
        val sb = StringUtil.borrowBuilder()
        html(
            QuietAppendable.wrap(sb), Document.OutputSettings()
        ) // output settings a bit funky, but this html() seldom used
        return StringUtil.releaseBuilder(sb)
    }

    fun html(accum: QuietAppendable, out: Document.OutputSettings) {
        val sz = size
        for (i in 0..sz) {
            val key = kotlin.checkNotNull(keys[i])
            if (isInternalKey(key)) continue
            val validated = Attribute.getValidKey(key, out.syntax())
            if (validated != null) Attribute.htmlNoValidate(
                validated, vals[i] as String?, accum.append(' '), out
            )
        }
    }

    override fun toString(): String {
        return html()
    }

    /**
     * Checks if these attributes are equal to another set of attributes, by comparing the two sets. Note that the order
     * of the attributes does not impact this equality (as per the Map interface equals()).
     * @param o attributes to compare with
     * @return if both sets of attributes have the same content
     */
    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false

        val that = o as Attributes
        if (size != that.size) return false
        for (i in 0..size) {
            val key = kotlin.checkNotNull(keys[i])
            val thatI = that.indexOfKey(key)
            if (thatI == NotFound || vals[i] != that.vals[thatI]) return false
        }
        return true
    }

    /**
     * Calculates the hashcode of these attributes, by iterating all attributes and summing their hashcodes.
     * @return calculated hashcode
     */
    override fun hashCode(): Int {
        var result = size
        result = 31 * result + keys.contentHashCode()
        result = 31 * result + vals.contentHashCode()
        return result
    }

    public override fun clone(): Attributes {
        val clone: Attributes
        try {
            clone = super.clone() as Attributes
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
        clone.size = size
        clone.keys = keys.copyOf<String?>(size)
        clone.vals = vals.copyOf<Any?>(size)

        // make a copy of the user data map. (Contents are shallow).
        val i = indexOfKey(SharedConstants.UserDataKey)
        if (i != NotFound) {
            vals[i] = HashMap<String?, Any?>(vals[i] as MutableMap<String?, Any?>?)
        }

        return clone
    }

    /**
     * Internal method. Lowercases all (non-internal) keys.
     */
    fun normalize() {
        for (i in 0..<size) {
            kotlin.checkNotNull(keys[i])
            val key = kotlin.checkNotNull(keys[i])
            if (!isInternalKey(key)) keys[i] = lowerCase(key)
        }
    }

    /**
     * Internal method. Removes duplicate attribute by name. Settings for case sensitivity of key names.
     * @param settings case sensitivity
     * @return number of removed dupes
     */
    fun deduplicate(settings: ParseSettings): Int {
        if (size == 0) return 0
        val preserve = settings.preserveAttributeCase()
        var dupes = 0
        for (i in 0..<size) {
            val keyI = kotlin.checkNotNull(keys[i])
            var j = i + 1
            while (j < size) {
                if ((preserve && keyI == keys[j]) || (!preserve && keyI.equals(
                        keys[j], ignoreCase = true
                    ))
                ) {
                    dupes++
                    remove(j)
                    j--
                }
                j++
            }
        }
        return dupes
    }

    private class Dataset(private val attributes: Attributes) : AbstractMap<String, String>() {
        override fun entrySet(): MutableSet<MutableMap.MutableEntry<String?, String?>> {
            return Dataset.EntrySet()
        }

        override fun put(key: String, value: String?): String? {
            val dataKey: String = dataKey(key)
            val oldValue = if (attributes.hasKey(dataKey)) attributes.get(dataKey) else null
            attributes.put(dataKey, value)
            return oldValue
        }

        private inner class EntrySet : AbstractSet<MutableMap.MutableEntry<String, String>>() {
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, String>> {
                return Dataset.DatasetIterator()
            }

            override fun size(): Int {
                var count = 0
                val iter: MutableIterator<MutableMap.MutableEntry<String?, String?>?> = Dataset.DatasetIterator()
                while (iter.hasNext()) count++
                return count
            }
        }

        private inner class DatasetIterator : MutableIterator<MutableMap.MutableEntry<String, String>> {
            private val attrIter = attributes.iterator()
            private var attr: Attribute? = null

            override fun hasNext(): Boolean {
                while (attrIter.hasNext()) {
                    val attr = attrIter.next()
                    this.attr = attr
                    if (attr.isDataAttribute) return true
                }
                return false
            }

            override fun next(): MutableMap.MutableEntry<String, String> {
                return Attribute(attr!!.getKey().substring(dataPrefix.length), attr!!.value)
            }

            override fun remove() {
                attributes.remove(attr!!.getKey())
            }
        }
    }

    companion object {
        // The Attributes object is only created on the first use of an attribute; the Element will just have a null
        // Attribute slot otherwise
        const val InternalPrefix: Char = '/' // Indicates an internal key. Can't be set via HTML. (It could be set via accessor, but not too worried about that. Suppressed from list, iter, size.)
        const val dataPrefix: String = "data-" // data attributes
        private const val EmptyString = ""

        // manages the key/val arrays
        private const val InitialCapacity = 3 // sampling found mean count when attrs present = 1.49; 1.08 overall. 2.6:1 don't have any attrs.
        private const val GrowthFactor = 2
        val NotFound: Int = -1

        // we track boolean attributes as null in values - they're just keys. so returns empty for consumers
        // casts to String, so only for non-internal attributes
        fun checkNotNull(@Nullable `val`: Any?): String {
            return if (`val` == null) EmptyString else `val` as String
        }

        private fun dataKey(key: String): String {
            return dataPrefix + key
        }

        @JvmStatic
        fun internalKey(key: String): String {
            return InternalPrefix.toString() + key
        }

        fun isInternalKey(key: String): Boolean {
            return key.length > 1 && key.get(0) == InternalPrefix
        }
    }
}
