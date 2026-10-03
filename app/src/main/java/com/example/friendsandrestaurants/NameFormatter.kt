package com.example.friendsandrestaurants

object NameFormatter {

    /**
     * Formats a name string by capitalizing the first letter of each word separated by
     * commas (,), full stops (.), spaces ( ), or hyphens (-), and cleaning up spacing.
     *
     * Example:
     * "arib,nanbun" -> "Arib, Nanbun"
     * "arib.nanbun" -> "Arib. Nanbun"
     * "arib nanbun" -> "Arib Nanbun"
     * "arib, nanbun. charlie david" -> "Arib, Nanbun. Charlie David"
     */
    fun formatName(name: String): String {
        if (name.isBlank()) return ""

        var result = name.trim().replace(Regex("^[,.\\s]+|[,.\\s]+$"), "")
        if (result.isBlank()) return ""

        result = result.replace(Regex("\\s*,\\s*"), ", ")
                       .replace(Regex("\\s*\\.\\s*"), ". ")
                       .replace(Regex("\\s+"), " ")

        val sb = StringBuilder()
        var capitalizeNext = true

        for (i in result.indices) {
            val ch = result[i]
            if (ch.isLetterOrDigit()) {
                if (capitalizeNext) {
                    sb.append(ch.uppercaseChar())
                    capitalizeNext = false
                } else {
                    sb.append(ch.lowercaseChar())
                }
            } else {
                sb.append(ch)
                if (ch == ' ' || ch == ',' || ch == '.' || ch == '-') {
                    capitalizeNext = true
                }
            }
        }

        return sb.toString().trim()
    }
}
