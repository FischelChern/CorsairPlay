package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateCatalogTest {
    private val releaseJson = """
        [
          {
            "tag_name": "v0.2.15",
            "draft": true,
            "assets": [
              {"name": "DiPlay-0.2.15.apk", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.15/DiPlay-0.2.15.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.15/SHA256SUMS.txt"}
            ]
          },
          {
            "tag_name": "v0.2.14",
            "draft": false,
            "prerelease": true,
            "assets": [
              {"name": "DiPlay-0.2.14.apk", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/DiPlay-0.2.14.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/SHA256SUMS.txt"}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun skipsDraftsAndReturnsTheFirstPublishedRelease() {
        val release = UpdateCatalog.parse(releaseJson)
        assertEquals("v0.2.14", release?.tagName)
        assertEquals("DiPlay-0.2.14.apk", release?.apkName)
        assertEquals(
            "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/DiPlay-0.2.14.apk",
            release?.apkUrl,
        )
        assertEquals(
            "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/SHA256SUMS.txt",
            release?.checksumsUrl,
        )
    }

    @Test
    fun returnsNullWithoutAnApkAsset() {
        val json = """
            [
              {"tag_name": "v0.2.14", "draft": false, "assets": [
                {"name": "source.zip", "browser_download_url": "https://example.com/source.zip"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullWithoutChecksums() {
        val json = """
            [
              {"tag_name": "v0.2.14", "draft": false, "assets": [
                {"name": "DiPlay-0.2.14.apk", "browser_download_url": "https://example.com/DiPlay-0.2.14.apk"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullForAnEmptyReleaseList() {
        assertNull(UpdateCatalog.parse("[]"))
    }

    @Test
    fun parsesTheServerManifest() {
        val json = """
            {
              "tag_name": "0.2.25",
              "apk_name": "CorsairPlay-0.2.25.apk",
              "apk_url": "https://www.teamcc.net/api/updates/CorsairPlay-0.2.25.apk",
              "checksums_url": "https://www.teamcc.net/api/updates/SHA256SUMS.txt"
            }
        """.trimIndent()
        val release = UpdateCatalog.parseManifest(json)
        assertEquals("0.2.25", release?.tagName)
        assertEquals("CorsairPlay-0.2.25.apk", release?.apkName)
        assertEquals("https://www.teamcc.net/api/updates/CorsairPlay-0.2.25.apk", release?.apkUrl)
        assertEquals("https://www.teamcc.net/api/updates/SHA256SUMS.txt", release?.checksumsUrl)
    }

    @Test
    fun returnsNullForAnIncompleteServerManifest() {
        assertNull(UpdateCatalog.parseManifest("""{"tag_name":"0.0.0"}"""))
    }
}
