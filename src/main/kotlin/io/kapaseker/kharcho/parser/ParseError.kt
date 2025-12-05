package io.kapaseker.kharcho.parser

/**
 * A Parse Error records an error in the input HTML that occurs in either the tokenisation or the tree building phase.
 */
class ParseError {
    /**
     * Retrieves the offset of the error.
     * @return error offset within input
     */
    val position: Int

    /**
     * Get the formatted line:column cursor position where the error occurred.
     * @return line:number cursor position
     */
    val cursorPos: String

    /**
     * Retrieve the error message.
     * @return the error message.
     */
    val errorMessage: String?

    internal constructor(reader: CharacterReader, errorMsg: String?) {
        this.position = reader.pos()
        cursorPos = reader.posLineCol()
        this.errorMessage = errorMsg
    }

    internal constructor(reader: CharacterReader, errorFormat: String, vararg args: Any?) {
        this.position = reader.pos()
        cursorPos = reader.posLineCol()
        this.errorMessage = String.format(errorFormat, *args)
    }

    internal constructor(pos: Int, errorMsg: String?) {
        this.position = pos
        cursorPos = pos.toString()
        this.errorMessage = errorMsg
    }

    internal constructor(pos: Int, errorFormat: String, vararg args: Any?) {
        this.position = pos
        cursorPos = pos.toString()
        this.errorMessage = String.format(errorFormat, *args)
    }

    override fun toString(): String {
        return "<" + cursorPos + ">: " + this.errorMessage
    }
}
