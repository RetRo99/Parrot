package com.retro99.epub.api

import kotlinx.serialization.Serializable

/**
 * Represents a single SMIL <par> entry with raw references and clock values.
 * The references are kept as raw strings so platforms can resolve them.
 */
@Serializable
data class SmilClip(
    val textSrc: String,
    val audioSrc: String,
    val clipBegin: Double,
    val clipEnd: Double,
)
