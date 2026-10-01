package com.retro99.reader.ui.media

import com.retro99.epub.implementation.smil.SmilParser
import com.retro99.reader.ui.media.smil.SmilQuickScanner
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Provider for SMIL parsing utilities accessible from Swift.
 *
 * This object provides access to Koin-managed SMIL parser instances
 * for use in iOS Swift code. Since SmilParser requires constructor
 * dependencies (XML parser and clock parser), it cannot be instantiated
 * directly from Swift. This provider exposes the Koin-managed singletons.
 *
 * Usage from Swift:
 * ```swift
 * let smilParser = SmilParserProvider.shared.smilParser
 * let quickScanner = SmilParserProvider.shared.quickScanner
 * ```
 */
object SmilParserProvider : KoinComponent {

    /**
     * The Koin-managed SmilParser instance.
     * Uses lazy injection to ensure Koin is initialized before access.
     */
    val smilParser: SmilParser by inject()

    /**
     * The Koin-managed SmilQuickScanner instance.
     * Uses lazy injection to ensure Koin is initialized before access.
     */
    val quickScanner: SmilQuickScanner by inject()
}

