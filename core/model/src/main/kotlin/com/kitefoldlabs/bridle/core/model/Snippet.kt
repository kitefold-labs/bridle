package com.kitefoldlabs.bridle.core.model

import kotlinx.serialization.Serializable

@Serializable
public enum class SnippetRunMode {
    /** Typed into the focused terminal (bracketed paste). */
    PASTE,

    /** Run as a separate `exec` on the connection; output shown in a sheet. */
    EXEC,
}

/**
 * A saved command. Variables are written `{{name}}` or `{{name:default}}` in [body] and are
 * filled in by the user before running. `{{{{` produces a literal `{{`.
 */
@Serializable
public data class Snippet(
    val id: EntityId = 0,
    val name: String,
    val body: String,
    val runMode: SnippetRunMode = SnippetRunMode.PASTE,
    val packageName: String? = null,
) {
    /** Variables in order of first appearance, with their default values if any. */
    public val variables: List<SnippetVariable>
        get() =
            SnippetTemplate
                .parse(body)
                .filterIsInstance<SnippetTemplate.Part.Variable>()
                .distinctBy { it.name }
                .map { SnippetVariable(it.name, it.default) }

    /**
     * Renders [body] with [values]. Missing values fall back to defaults.
     * @throws IllegalArgumentException if a variable has neither a value nor a default.
     */
    public fun render(values: Map<String, String>): String =
        SnippetTemplate.parse(body).joinToString("") { part ->
            when (part) {
                is SnippetTemplate.Part.Text -> part.text
                is SnippetTemplate.Part.Variable ->
                    values[part.name] ?: part.default
                        ?: throw IllegalArgumentException("Missing value for {{${part.name}}}")
            }
        }
}

@Serializable
public data class SnippetVariable(
    val name: String,
    val default: String?,
)

internal object SnippetTemplate {
    sealed interface Part {
        data class Text(
            val text: String,
        ) : Part

        data class Variable(
            val name: String,
            val default: String?,
        ) : Part
    }

    private val variableName = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    private const val ESCAPED_OPEN = "{{{{"
    private const val OPEN = "{{"
    private const val CLOSE = "}}"

    fun parse(body: String): List<Part> {
        val parts = mutableListOf<Part>()
        val text = StringBuilder()
        var i = 0
        while (i < body.length) {
            val variable = if (body.startsWith(ESCAPED_OPEN, i)) null else variableAt(body, i)
            i =
                when {
                    body.startsWith(ESCAPED_OPEN, i) -> {
                        text.append(OPEN)
                        i + ESCAPED_OPEN.length
                    }
                    variable != null -> {
                        if (text.isNotEmpty()) parts += Part.Text(text.toString()).also { text.clear() }
                        parts += variable.first
                        variable.second
                    }
                    else -> {
                        text.append(body[i])
                        i + 1
                    }
                }
        }
        if (text.isNotEmpty()) parts += Part.Text(text.toString())
        return parts
    }

    /** The variable starting at [start] and the index just past it, or null if there is none. */
    private fun variableAt(
        body: String,
        start: Int,
    ): Pair<Part.Variable, Int>? {
        if (!body.startsWith(OPEN, start)) return null
        val end = body.indexOf(CLOSE, start + OPEN.length)
        if (end < 0) return null
        val variable = parseVariable(body.substring(start + OPEN.length, end)) ?: return null
        return variable to end + CLOSE.length
    }

    private fun parseVariable(inner: String): Part.Variable? {
        val name = inner.substringBefore(':')
        val default = if (inner.contains(':')) inner.substringAfter(':') else null
        return if (variableName.matches(name)) Part.Variable(name, default) else null
    }
}
