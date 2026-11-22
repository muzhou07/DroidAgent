package com.muzhou.droidagent.ui.markdown

import com.muzhou.droidagent.ui.theme.DsColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private val KEYWORDS: Map<String, Set<String>> = mapOf(
    "kotlin" to setOf(
        "package", "import", "class", "object", "interface", "fun", "val", "var", "if", "else",
        "when", "for", "while", "do", "return", "break", "continue", "try", "catch", "finally",
        "throw", "suspend", "data", "sealed", "enum", "companion", "private", "public", "internal",
        "protected", "override", "open", "abstract", "inline", "lateinit", "by", "in", "is", "as",
        "null", "true", "false", "this", "super", "typealias", "reified", "crossinline", "noinline",
        "vararg", "init", "constructor", "get", "set", "field", "where",
    ),
    "java" to setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public",
        "private", "protected", "static", "final", "void", "int", "long", "double", "float",
        "boolean", "char", "byte", "short", "new", "return", "if", "else", "for", "while", "do",
        "try", "catch", "finally", "throw", "throws", "this", "super", "null", "true", "false",
        "abstract", "synchronized", "volatile", "transient", "instanceof", "record", "sealed",
    ),
    "python" to setOf(
        "def", "class", "import", "from", "as", "if", "elif", "else", "for", "while", "return",
        "yield", "try", "except", "finally", "raise", "with", "lambda", "pass", "break", "continue",
        "and", "or", "not", "in", "is", "None", "True", "False", "async", "await", "global",
        "nonlocal", "assert", "del", "match", "case", "self",
    ),
    "javascript" to setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "return", "class",
        "extends", "new", "this", "super", "import", "export", "default", "async", "await",
        "try", "catch", "finally", "throw", "typeof", "instanceof", "null", "undefined", "true",
        "false", "switch", "case", "break", "continue", "in", "of", "yield", "static", "get", "set",
    ),
    "typescript" to setOf(
        "function", "const", "let", "var", "if", "else", "for", "while", "return", "class",
        "extends", "implements", "interface", "type", "enum", "namespace", "new", "this", "super",
        "import", "export", "default", "async", "await", "try", "catch", "finally", "throw",
        "typeof", "keyof", "instanceof", "null", "undefined", "true", "false", "switch", "case",
        "break", "continue", "in", "of", "yield", "readonly", "public", "private", "protected",
        "abstract", "as", "satisfies", "infer", "declare",
    ),
    "json" to setOf("true", "false", "null"),
    "shell" to setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "return", "exit", "export", "local", "readonly", "source", "echo", "printf",
        "cd", "set", "unset", "trap", "wait", "test",
    ),
    "go" to setOf(
        "package", "import", "func", "var", "const", "type", "struct", "interface", "map",
        "chan", "go", "defer", "select", "case", "switch", "if", "else", "for", "range", "return",
        "break", "continue", "fallthrough", "nil", "true", "false", "make", "new", "append",
    ),
    "rust" to setOf(
        "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "pub", "use",
        "mod", "crate", "self", "super", "match", "if", "else", "for", "while", "loop", "return",
        "break", "continue", "move", "ref", "where", "async", "await", "dyn", "unsafe", "as",
        "in", "true", "false", "None", "Some", "Ok", "Err",
    ),
    "c" to setOf(
        "int", "long", "short", "char", "float", "double", "void", "struct", "union", "enum",
        "typedef", "static", "const", "extern", "return", "if", "else", "for", "while", "do",
        "switch", "case", "break", "continue", "goto", "sizeof", "include", "define", "NULL",
    ),
    "cpp" to setOf(
        "int", "long", "short", "char", "float", "double", "void", "bool", "class", "struct",
        "namespace", "using", "template", "typename", "public", "private", "protected", "virtual",
        "override", "const", "constexpr", "static", "return", "if", "else", "for", "while", "do",
        "switch", "case", "break", "continue", "new", "delete", "nullptr", "true", "false", "auto",
        "include", "define", "try", "catch", "throw",
    ),
    "sql" to setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
        "TABLE", "ALTER", "DROP", "INDEX", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP",
        "BY", "ORDER", "LIMIT", "OFFSET", "HAVING", "AS", "AND", "OR", "NOT", "NULL", "PRIMARY",
        "KEY", "FOREIGN", "REFERENCES", "DISTINCT", "COUNT", "SUM", "AVG", "MIN", "MAX",
    ),
    "xml" to emptySet(),
    "html" to emptySet(),
    "css" to setOf(
        "important", "media", "keyframes", "import", "font-face", "supports", "root", "hover",
        "focus", "active", "before", "after", "nth-child", "display", "position", "flex",
    ),
    "yaml" to setOf("true", "false", "null", "yes", "no"),
    "markdown" to emptySet(),
)

fun normalizeLanguage(raw: String): String {
    val lang = raw.trim().lowercase().substringBefore(' ').substringBefore(',')
    return when (lang) {
        "kt", "kts", "kotlin" -> "kotlin"
        "java" -> "java"
        "py", "python", "python3" -> "python"
        "js", "javascript", "jsx", "node" -> "javascript"
        "ts", "typescript", "tsx" -> "typescript"
        "json", "jsonc" -> "json"
        "sh", "bash", "shell", "zsh", "console" -> "shell"
        "go", "golang" -> "go"
        "rs", "rust" -> "rust"
        "c", "h" -> "c"
        "cc", "cpp", "c++", "hpp", "cxx" -> "cpp"
        "sql" -> "sql"
        "xml" -> "xml"
        "html", "htm" -> "html"
        "css", "scss", "less" -> "css"
        "yaml", "yml" -> "yaml"
        "md", "markdown" -> "markdown"
        "mermaid" -> "markdown"
        else -> lang
    }
}

private fun syntaxColors(colors: DsColors): SyntaxPalette = if (colors.dark) {
    SyntaxPalette(
        keyword = Color(0xFFC792EA),
        string = Color(0xFF8BD49C),
        comment = Color(0xFF7F848E),
        number = Color(0xFFF7AD31),
        tag = Color(0xFF7EB6FF),
    )
} else {
    SyntaxPalette(
        keyword = Color(0xFF8B5CF6),
        string = Color(0xFF0E9F6E),
        comment = Color(0xFF8B909A),
        number = Color(0xFFC2410C),
        tag = Color(0xFF2563EB),
    )
}

private data class SyntaxPalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val tag: Color,
)

fun highlightCode(code: String, language: String, colors: DsColors): AnnotatedString {
    val lang = normalizeLanguage(language)
    val keywords = KEYWORDS[lang].orEmpty()
    val palette = syntaxColors(colors)
    return buildAnnotatedString {
        val lines = code.split('\n')
        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) append('\n')
            appendLineHighlight(line, lang, keywords, palette, colors)
        }
    }
}

private fun AnnotatedString.Builder.appendLineHighlight(
    line: String,
    lang: String,
    keywords: Set<String>,
    palette: SyntaxPalette,
    colors: DsColors,
) {
    val hashComment = lang in setOf("python", "shell", "yaml", "ruby", "perl", "toml")
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            line.startsWith("//", i) || (hashComment && ch == '#') -> {
                withStyle(SpanStyle(color = palette.comment)) { append(line.substring(i)) }
                return
            }
            line.startsWith("/*", i) -> {
                val end = line.indexOf("*/", i + 2)
                val stop = if (end >= 0) end + 2 else line.length
                withStyle(SpanStyle(color = palette.comment)) { append(line.substring(i, stop)) }
                i = stop
                continue
            }
            line.startsWith("<!--", i) -> {
                val end = line.indexOf("-->", i + 4)
                val stop = if (end >= 0) end + 3 else line.length
                withStyle(SpanStyle(color = palette.comment)) { append(line.substring(i, stop)) }
                i = stop
                continue
            }
            ch == '"' || ch == '\'' || ch == '`' -> {
                val quote = ch
                var j = i + 1
                while (j < line.length) {
                    if (line[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (line[j] == quote) {
                        j++
                        break
                    }
                    j++
                }
                withStyle(SpanStyle(color = palette.string)) { append(line.substring(i, minOf(j, line.length))) }
                i = j
                continue
            }
            ch == '<' && (lang == "xml" || lang == "html") -> {
                val end = line.indexOf('>', i + 1)
                val stop = if (end >= 0) end + 1 else line.length
                withStyle(SpanStyle(color = palette.tag)) { append(line.substring(i, stop)) }
                i = stop
                continue
            }
            ch.isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit()) -> {
                var j = i
                while (j < line.length && (line[j].isDigit() || line[j] == '.' || line[j] == '_')) j++
                withStyle(SpanStyle(color = palette.number)) { append(line.substring(i, j)) }
                i = j
                continue
            }
            ch.isLetter() || ch == '_' -> {
                var j = i
                while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                val matched = if (lang == "sql") {
                    keywords.any { it.equals(word, ignoreCase = true) }
                } else {
                    word in keywords
                }
                if (matched) {
                    withStyle(SpanStyle(color = palette.keyword, fontWeight = FontWeight.Medium)) { append(word) }
                } else if (line.getOrNull(j) == '(' && word !in setOf("if", "for", "while", "switch", "catch", "when")) {
                    withStyle(SpanStyle(color = colors.brand)) { append(word) }
                } else {
                    append(word)
                }
                i = j
                continue
            }
            else -> {
                append(ch)
                i++
            }
        }
    }
}

fun inlineCodeStyle(colors: DsColors): SpanStyle = SpanStyle(
    fontFamily = FontFamily.Monospace,
    background = colors.inlineCode,
    color = colors.textPrimary,
)
