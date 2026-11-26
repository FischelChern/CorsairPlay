package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection

internal object UpdateReleaseLookup {
    fun latest(
        installedVersion: String,
        userAgent: String,
        manifestUrl: String = UpdateClient.MANIFEST_URL,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateRelease? {
        val json = UpdateClient.fetchText(
            manifestUrl,
            "application/json",
            userAgent,
            openConnection,
        )
        val release = UpdateCatalog.parseManifest(json) ?: return null
        return release.takeIf { UpdateVersion.isNewer(it.tagName, installedVersion) }
    }
}
