package com.retro99.epub.implementation.di

import nl.adaptivity.xmlutil.serialization.XML
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@ComponentScan("com.retro99.epub.implementation")
class EpubModule {

    @Single
    fun provideXml(): XML = XML.v1 {
        // Be lenient with unknown attributes and elements (like version, xmlns:epub, etc.)
        policy {
            ignoreUnknownChildren()
        }
    }
}
