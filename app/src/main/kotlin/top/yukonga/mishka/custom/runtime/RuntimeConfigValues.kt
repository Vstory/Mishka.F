package top.yukonga.mishka.custom.runtime

/**
 * Small, dependency-free reader for the scalar config values Mishka needs before starting mihomo.
 *
 * This intentionally handles ordinary YAML block scalars plus a flow-style `tun: { ... }` map;
 * it is not a general YAML parser. Complex values/aliases are left unresolved rather than guessed.
 */
object RuntimeConfigValues {

    /** Return a top-level scalar, preserving an explicitly empty/null value as `""`. */
    fun topLevelScalar(yaml: String, key: String): String? {
        for (original in yaml.lineSequence()) {
            val line = original.removePrefix("\uFEFF")
            if (leadingIndent(line) != 0) continue
            val content = stripComment(line).trimEnd()
            if (content.isBlank() || content == "---" || content == "...") continue
            val colon = findColon(content)
            if (colon < 0 || parseKey(content.substring(0, colon).trim()) != key) continue
            return parseScalar(content.substring(colon + 1))
        }
        return null
    }

    /** Return a direct child scalar such as `tun.device`, including a flow-style parent map. */
    fun nestedScalar(yaml: String, parentKey: String, childKey: String): String? {
        var inParent = false
        var childIndent = -1

        for (original in yaml.lineSequence()) {
            val line = original.removePrefix("\uFEFF")
            val contentWithIndent = stripComment(line).trimEnd()
            if (contentWithIndent.isBlank()) continue
            val indent = leadingIndent(contentWithIndent)
            val content = contentWithIndent.drop(indent)

            if (indent == 0) {
                val colon = findColon(content)
                if (colon < 0) {
                    inParent = false
                    childIndent = -1
                    continue
                }
                val key = parseKey(content.substring(0, colon).trim())
                inParent = key == parentKey
                childIndent = -1
                if (inParent) {
                    val flowValue = parseFlowMapScalar(content.substring(colon + 1), childKey)
                    if (flowValue != null) return flowValue
                }
                continue
            }

            if (!inParent) continue
            val colon = findColon(content)
            if (colon < 0) continue // comments, sequence items, and nested block markers
            if (childIndent < 0) childIndent = indent
            if (indent != childIndent) continue
            if (parseKey(content.substring(0, colon).trim()) == childKey) {
                return parseScalar(content.substring(colon + 1))
            }
        }
        return null
    }

    /** Profile > app override > empty/no-auth; explicit empty profile secrets remain authoritative. */
    fun selectSecret(profileSecret: String?, appSecret: String?): String =
        profileSecret ?: appSecret?.trim()?.takeIf { it.isNotEmpty() } ?: ""

    /** Profile > app override > default. Blank controller values are treated as unset. */
    fun selectExternalController(profileValue: String?, appValue: String?, defaultValue: String): String =
        profileValue?.trim()?.takeIf { it.isNotEmpty() }
            ?: appValue?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultValue

    /** Active profile > app override > historical default; invalid TPROXY ports are ignored. */
    fun selectRootTproxyPort(profilePort: Int?, appPort: Int?, defaultPort: Int): Int =
        profilePort?.takeIf { it in 1..65535 }
            ?: appPort?.takeIf { it in 1..65535 }
            ?: defaultPort

    /** Profile > app override > ROOT setting > historical default. Blank names are treated as unset. */
    fun selectTunDevice(
        profileValue: String?,
        appValue: String?,
        rootSetting: String?,
        defaultValue: String,
    ): String = profileValue?.trim()?.takeIf { it.isNotEmpty() }
        ?: appValue?.trim()?.takeIf { it.isNotEmpty() }
        ?: rootSetting?.trim()?.takeIf { it.isNotEmpty() }
        ?: defaultValue

    /**
     * Convert a wildcard bind address into a loopback address for requests made by Mishka itself.
     * This does not alter the address passed to mihomo, so the configured listener remains intact.
     */
    fun apiClientAddress(bindAddress: String): String {
        val address = bindAddress.trim()
        return when {
            address == "0.0.0.0" -> "127.0.0.1"
            address.startsWith("0.0.0.0:") -> "127.0.0.1:${address.substringAfter(':')}"
            address == "*" -> "127.0.0.1"
            address.startsWith("*:") -> "127.0.0.1:${address.substringAfter(':')}"
            address == "[::]" -> "[::1]"
            address.startsWith("[::]:") -> "[::1]:${address.substringAfter("]:")}"
            else -> address
        }
    }

    private fun parseFlowMapScalar(raw: String, childKey: String): String? {
        val value = stripComment(raw).trim()
        val opening = value.indexOf('{')
        if (opening < 0) return null
        val prefix = value.substring(0, opening).trim()
        if (prefix.isNotEmpty() && !prefix.startsWith('&')) return null
        val closing = findMatchingBrace(value, opening) ?: return null
        if (value.substring(closing + 1).isNotBlank()) return null
        val body = value.substring(opening + 1, closing)
        for (entry in splitFlowEntries(body)) {
            val colon = findColon(entry)
            if (colon < 0 || parseKey(entry.substring(0, colon).trim()) != childKey) continue
            return parseScalar(entry.substring(colon + 1))
        }
        return null
    }

    private fun splitFlowEntries(body: String): List<String> {
        val entries = mutableListOf<String>()
        var start = 0
        var braces = 0
        var brackets = 0
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < body.length) {
            val char = body[index]
            when (quote) {
                '"' -> {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quote = null
                }
                '\'' -> {
                    if (char == '\'') {
                        if (index + 1 < body.length && body[index + 1] == '\'') index++
                        else quote = null
                    }
                }
                else -> when (char) {
                    '"', '\'' -> quote = char
                    '{' -> braces++
                    '}' -> braces--
                    '[' -> brackets++
                    ']' -> brackets--
                    ',' -> if (braces == 0 && brackets == 0) {
                        entries += body.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
            index++
        }
        entries += body.substring(start).trim()
        return entries.filter { it.isNotEmpty() }
    }

    private fun findMatchingBrace(value: String, opening: Int): Int? {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var index = opening
        while (index < value.length) {
            val char = value[index]
            when (quote) {
                '"' -> {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quote = null
                }
                '\'' -> {
                    if (char == '\'') {
                        if (index + 1 < value.length && value[index + 1] == '\'') index++
                        else quote = null
                    }
                }
                else -> when (char) {
                    '"', '\'' -> quote = char
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }
        return null
    }

    private fun findColon(value: String): Int {
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when (quote) {
                '"' -> {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quote = null
                }
                '\'' -> {
                    if (char == '\'') {
                        if (index + 1 < value.length && value[index + 1] == '\'') index++
                        else quote = null
                    }
                }
                else -> when (char) {
                    '"', '\'' -> quote = char
                    ':' -> return index
                }
            }
            index++
        }
        return -1
    }

    private fun parseKey(raw: String): String? {
        val key = raw.trim()
        if (key.isEmpty()) return null
        if (key.startsWith('"') || key.startsWith('\'')) return parseScalar(key)
        return key
    }

    /** Return null for non-scalar YAML constructs; empty/null scalars are returned as `""`. */
    private fun parseScalar(raw: String): String? {
        val value = stripComment(raw).trim()
        if (value.isEmpty() || value == "~" || value.equals("null", ignoreCase = true)) return ""
        if (value.startsWith('"')) return parseDoubleQuoted(value)
        if (value.startsWith('\'')) return parseSingleQuoted(value)
        if (value.startsWith('{') || value.startsWith('[') || value == "|" || value == ">") return null
        return value
    }

    private fun parseDoubleQuoted(value: String): String? {
        if (value.length < 2 || value.last() != '"') return null
        val body = value.substring(1, value.length - 1)
        val output = StringBuilder(body.length)
        var index = 0
        while (index < body.length) {
            val char = body[index]
            if (char == '\\' && index + 1 < body.length) {
                val escaped = body[index + 1]
                output.append(
                    when (escaped) {
                        '"' -> '"'
                        '\\' -> '\\'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> escaped
                    }
                )
                index += 2
            } else {
                output.append(char)
                index++
            }
        }
        return output.toString()
    }

    private fun parseSingleQuoted(value: String): String? {
        if (value.length < 2 || value.last() != '\'') return null
        val body = value.substring(1, value.length - 1)
        return body.replace("''", "'")
    }

    private fun stripComment(value: String): String {
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when (quote) {
                '"' -> {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quote = null
                }
                '\'' -> {
                    if (char == '\'') {
                        if (index + 1 < value.length && value[index + 1] == '\'') index++
                        else quote = null
                    }
                }
                else -> when {
                    char == '"' || char == '\'' -> quote = char
                    char == '#' && (index == 0 || value[index - 1].isWhitespace()) -> return value.substring(0, index)
                }
            }
            index++
        }
        return value
    }

    private fun leadingIndent(value: String): Int = value.takeWhile { it == ' ' }.length
}
