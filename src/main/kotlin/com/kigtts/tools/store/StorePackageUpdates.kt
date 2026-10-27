package com.kigtts.tools.store

/** Keep update filters, badges and the selected release's buttons on the same rule. */
internal fun PackageRelease.isUpdateFrom(installed: InstalledPackage): Boolean {
    val versionOrder = SemVersion.parse(version).compareTo(SemVersion.parse(installed.version))
    return versionOrder > 0 ||
        (apiVersion > installed.apiVersion && versionOrder >= 0) ||
        (format == PackageFormat.NATIVE && versionCode > installed.versionCode)
}
