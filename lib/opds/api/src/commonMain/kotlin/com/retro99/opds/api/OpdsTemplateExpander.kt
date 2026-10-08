package com.retro99.opds.api

/**
 * Bounded RFC 6570 template expansion, the declared supported level of the
 * plan (§4): `{var}`, `{+var}`, `{?list}`, `{&list}`, literals per §2.1;
 * unsupported operators/modifiers fail explicitly with
 * [OpdsTemplateException] rather than mangling a search URL.
 */
interface OpdsTemplateExpander {
    fun expand(template: String, variables: Map<String, String>): String

    /** True when the string contains at least one expression. */
    fun isTemplate(text: String): Boolean

    class OpdsTemplateException(template: String, at: Int, why: String) :
        IllegalStateException("RFC 6570 template '$template' at offset $at: $why")
}
