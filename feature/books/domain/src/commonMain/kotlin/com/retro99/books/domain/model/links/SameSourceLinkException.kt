package com.retro99.books.domain.model.links

import com.retro99.base.result.AppError

/** A link can hold only one copy per source; this link would hold two from [source]. */
class SameSourceLinkException(val source: CopySource) :
    IllegalArgumentException("A link can't hold two copies from ${source.prefix}")

fun sameSourceLinkError(source: CopySource): AppError =
    AppError.UnknownError(SameSourceLinkException(source))

/** The source a failed link would have repeated, or null for any other error. */
fun AppError.repeatedLinkSource(): CopySource? =
    ((this as? AppError.UnknownError)?.throwable as? SameSourceLinkException)?.source
