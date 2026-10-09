package com.retro99.catalogue.ui.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CataloguePreset(
    val id: String,
    val name: String,
    val address: String,
    val description: String,
    val shortDescription: String,
    val accountLabel: String,
    val needsAccount: Boolean,
    val termsUrl: String?,
    val listEntriesAreBooks: Boolean = false,
) {
    val host: String get() = catalogueDisplayHost(address)
}

internal fun catalogueDisplayHost(address: String): String = address
    .removeScheme()
    .substringBefore('/')
    .substringBefore('?')
    .substringBefore('#')
    .substringAfterLast('@')
    .lowercase()
    .removePrefix("www.")

private fun String.removeScheme(): String = when {
    startsWith("https://", ignoreCase = true) -> drop("https://".length)
    startsWith("http://", ignoreCase = true) -> drop("http://".length)
    else -> this
}

/** Reads and validates the shipped preset data file. Only HTTPS catalogue and policy links ship. */
fun parseCataloguePresets(json: String): List<CataloguePreset> {
    val entries: JsonArray = Json.parseToJsonElement(json).jsonArray
    val presets = entries.map { element ->
        val entry: JsonObject = element.jsonObject
        fun required(key: String) = requireNotNull(entry[key]?.jsonPrimitive?.contentOrNull) { "Missing preset field: $key" }
        val address = required("address")
        val termsUrl = entry["termsUrl"]?.jsonPrimitive?.contentOrNull
        require(address.startsWith("https://", ignoreCase = true)) { "Preset addresses must use HTTPS" }
        require(termsUrl == null || termsUrl.startsWith("https://", ignoreCase = true)) { "Preset links must use HTTPS" }
        CataloguePreset(
            id = required("id"),
            name = required("name"),
            address = address,
            description = required("description"),
            shortDescription = required("shortDescription"),
            accountLabel = required("accountLabel"),
            needsAccount = requireNotNull(entry["needsAccount"]?.jsonPrimitive?.boolean) { "Missing preset field: needsAccount" },
            termsUrl = termsUrl,
            listEntriesAreBooks = entry["listEntriesAreBooks"]?.jsonPrimitive?.boolean ?: false,
        )
    }
    require(presets.map { it.id }.distinct().size == presets.size) { "Preset ids must be unique" }
    require(presets.map { it.address }.distinct().size == presets.size) { "Preset addresses must be unique" }
    return presets
}

fun availableCataloguePresets(presets: List<CataloguePreset>, registeredAddresses: Set<String>): List<CataloguePreset> =
    presets.filterNot { it.address in registeredAddresses }
