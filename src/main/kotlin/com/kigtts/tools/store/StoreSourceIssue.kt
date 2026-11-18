package com.kigtts.tools.store

internal interface StoreSourceProblem { val key: String }

internal class StoreSourceIssue(override val key: String, fallback: String) : IllegalArgumentException(fallback), StoreSourceProblem
internal class StoreSourceStateIssue(override val key: String, fallback: String) : IllegalStateException(fallback), StoreSourceProblem

internal fun SourceRecord.displayError(copy: StoreCopy): String =
    if (error.isEmpty()) "" else copy.text(errorKey ?: "source.issue.refresh")
