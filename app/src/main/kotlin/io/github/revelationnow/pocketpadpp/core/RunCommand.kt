package io.github.revelationnow.pocketpadpp.core

/** The variables of Notepad++'s Run dialog that have a meaning on a phone. */
object RunCommand {
    /** Fills in the variables. The word and the file name are made safe for use inside an address. */
    fun expand(template: String, word: String, fileName: String, path: String): String = template
        .replace("\$(CURRENT_WORD)", encode(word))
        .replace("\$(FILE_NAME)", encode(fileName))
        .replace("\$(NAME_PART)", encode(fileName.substringBeforeLast('.', fileName)))
        .replace("\$(EXT_PART)", encode(if ('.' in fileName) "." + fileName.substringAfterLast('.') else ""))
        .replace("\$(FULL_CURRENT_PATH)", path)

    private fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
