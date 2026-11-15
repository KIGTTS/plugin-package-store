package com.kigtts.tools.store

/** Temporarily hide cloud sharing across official, external and cached sources. */
internal fun isPausedPackage(id: String): Boolean = id == "cloud.sharing"
