package io.kapaseker.kharcho.helper

/**
 * Validators to check that method arguments meet expectations.
 */
object Validate {
    /**
     * Validates that the object is not null
     * @param obj object to test
     * @throws ValidationException if the object is null
     */
    @JvmStatic
    fun notNull(obj: Any?) {
        if (obj == null) throw ValidationException("Object must not be null")
    }

    /**
     * Validates that the parameter is not null
     *
     * @param obj the parameter to test
     * @param param the name of the parameter, for presentation in the validation exception.
     * @throws ValidationException if the object is null
     */
    @JvmStatic
    fun notNullParam(obj: Any?, param: String?) {
        if (obj == null) throw ValidationException(
            String.format(
                "The parameter '%s' must not be null.",
                param
            )
        )
    }

    /**
     * Validates that the object is not null
     * @param obj object to test
     * @param msg message to include in the Exception if validation fails
     * @throws ValidationException if the object is null
     */
    @JvmStatic
    fun notNull(obj: Any?, msg: String?) {
        if (obj == null) throw ValidationException(msg)
    }

    /**
     * Verifies the input object is not null, and returns that object. Effectively this casts a nullable object to a non-
     * null object. (Works around lack of Objects.requestNonNull in Android version.)
     * @param obj nullable object to cast to not-null
     * @return the object, or throws an exception if it is null
     * @throws ValidationException if the object is null
     */
    @Deprecated("prefer to use {@link #expectNotNull(Object, String, Object...)} instead")
    fun ensureNotNull(obj: Any?): Any {
        if (obj == null) throw ValidationException("Object must not be null")
        else return obj
    }

    /**
     * Verifies the input object is not null, and returns that object. Effectively this casts a nullable object to a non-
     * null object. (Works around lack of Objects.requestNonNull in Android version.)
     * @param obj nullable object to cast to not-null
     * @param msg the String format message to include in the validation exception when thrown
     * @param args the arguments to the msg
     * @return the object, or throws an exception if it is null
     * @throws ValidationException if the object is null
     */
    @Deprecated("prefer to use {@link #expectNotNull(Object, String, Object...)} instead")
    fun ensureNotNull(obj: Any?, msg: String, vararg args: Any?): Any {
        if (obj == null) throw ValidationException(String.format(msg, *args))
        else return obj
    }

    /**
     * Verifies the input object is not null, and returns that object, maintaining its type. Effectively this casts a
     * nullable object to a non-null object.
     *
     * @param obj nullable object to cast to not-null
     * @return the object, or throws an exception if it is null
     * @throws ValidationException if the object is null
     */
    fun <T> expectNotNull(obj: T?): T {
        if (obj == null) throw ValidationException("Object must not be null")
        else return obj
    }

    /**
     * Verifies the input object is not null, and returns that object, maintaining its type. Effectively this casts a
     * nullable object to a non-null object.
     *
     * @param obj nullable object to cast to not-null
     * @param msg the String format message to include in the validation exception when thrown
     * @param args the arguments to the msg
     * @return the object, or throws an exception if it is null
     * @throws ValidationException if the object is null
     */
    fun <T> expectNotNull(obj: T?, msg: String, vararg args: Any?): T {
        if (obj == null) throw ValidationException(String.format(msg, *args))
        else return obj
    }

    /**
     * Validates that the value is true
     * @param val object to test
     * @throws ValidationException if the object is not true
     */
    @JvmStatic
    fun isTrue(`val`: Boolean) {
        if (!`val`) throw ValidationException("Must be true")
    }

    /**
     * Validates that the value is true
     * @param val object to test
     * @param msg message to include in the Exception if validation fails
     * @throws ValidationException if the object is not true
     */
    @JvmStatic
    fun isTrue(`val`: Boolean, msg: String?) {
        if (!`val`) throw ValidationException(msg)
    }

    /**
     * Validates that the value is false
     * @param val object to test
     * @throws ValidationException if the object is not false
     */
    @JvmStatic
    fun isFalse(`val`: Boolean) {
        if (`val`) throw ValidationException("Must be false")
    }

    /**
     * Validates that the value is false
     * @param `val` object to test
     * @param msg message to include in the Exception if validation fails
     * @throws ValidationException if the object is not false
     */
    @JvmStatic
    fun isFalse(testBool: Boolean, msg: String?) {
        if (testBool) throw ValidationException(msg)
    }

    /**
     * Validates that the string is not null and is not empty
     * @param string the string to test
     * @throws ValidationException if the string is null or empty
     */
    @JvmStatic
    fun notEmpty(string: String?) {
        if (string == null || string.isEmpty()) throw ValidationException("String must not be empty")
    }

    /**
     * Validates that the string parameter is not null and is not empty
     * @param string the string to test
     * @param param the name of the parameter, for presentation in the validation exception.
     * @throws ValidationException if the string is null or empty
     */
    @JvmStatic
    fun notEmptyParam(string: String?, param: String?) {
        if (string == null || string.isEmpty()) throw ValidationException(
            String.format(
                "The '%s' parameter must not be empty.",
                param
            )
        )
    }

    /**
     * Validates that the string is not null and is not empty
     * @param string the string to test
     * @param msg message to include in the Exception if validation fails
     * @throws ValidationException if the string is null or empty
     */
    fun notEmpty(string: String?, msg: String?) {
        if (string == null || string.isEmpty()) throw ValidationException(msg)
    }

    /**
     * Blow up if we reach an unexpected state.
     * @param msg message to think about
     * @throws IllegalStateException if we reach this state
     */
    @JvmStatic
    fun wtf(msg: String?) {
        throw IllegalStateException(msg)
    }

    /**
     * Cause a failure.
     * @param msg message to output.
     * @throws IllegalStateException if we reach this state
     */
    @JvmStatic
    fun fail(msg: String?) {
        throw ValidationException(msg)
    }

    /**
     * Cause a failure.
     * @param msg message to output.
     * @param args the format arguments to the msg
     * @throws IllegalStateException if we reach this state
     */
    @JvmStatic
    fun fail(msg: String, vararg args: Any?) {
        throw ValidationException(String.format(msg, *args))
    }
}
