package com.retro99.opds.api.model

/**
 * A parsed media type (plan §4: "Do not compare MIME types as exact unparsed
 * strings"). Comparisons are case-insensitive; parameters are matched by
 * parsed keys.
 */
data class OpdsMediaType(
    val mainType: String,
    val subType: String,
    val parameters: Map<String, String> = emptyMap(),
) {
    val mediaRange: String get() = "$mainType/$subType"

    fun parameter(name: String): String? = parameters[name.lowercase()]

    /** `kind=navigation`/`acquisition` and similar sub-clues parse as parameters. */
    val kindParameter: String? get() = parameter("kind")

    fun isXmlFamily(): Boolean = mainType == "application" && (subType.endsWith("+xml") || subType == "xml" || subType == "atom+xml")

    fun isJsonFamily(): Boolean = mainType == "application" && (subType.endsWith("+json") || subType == "json")
}
