package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.fartech.ftapp2.commonsKt.HttpAccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

@Serializable
@LLMDescription("A structure containing iOS App metadata and marketing information")
data class AppleAppInfo(
    @property:LLMDescription("a list of the screenshot URLs for the iPhone")
    val screenshotUrls: List<String>? = null,
    @property:LLMDescription("a list of the screenshot URLs for the iPad")
    val ipadScreenshotUrls: List<String>? = null,
    @property:LLMDescription("a list of the screenshot URLs for the Apple TV")
    val appletvScreenshotUrls: List<String>? = null,
    @property:LLMDescription("artwork URL 60")
    val artworkUrl60: String? = null,
    @property:LLMDescription("artwork URL 512")
    val artworkUrl512: String? = null,
    @property:LLMDescription("artwork URL 100")
    val artworkUrl100: String? = null,
    @property:LLMDescription("artist view URL")
    val artistViewUrl: String? = null,
    @property:LLMDescription("features enabled for the app")
    val features: List<String>? = null,
    @property:LLMDescription("devices supported by the app")
    val supportedDevices: List<String>? = null,
    @property:LLMDescription("advisories for the app")
    val advisories: List<String>? = null,
    @property:LLMDescription("indicates whether Game Center is enabled for the app")
    val isGameCenterEnabled: Boolean? = null,
    @property:LLMDescription("the app's kind")
    val kind: String? = null,
    @property:LLMDescription("minimum iOS version supported by the app")
    val minimumOsVersion: String? = null,
    @property:LLMDescription("track censored name for the app")
    val trackCensoredName: String? = null,
    @property:LLMDescription("languages that app supports in ISO format")
    val languageCodesISO2A: List<String>? = null,
    @property:LLMDescription("the app binary's file size")
    val fileSizeBytes: String? = null,
    @property:LLMDescription("formated price for the app")
    val formattedPrice: String? = null,
    @property:LLMDescription("the average user rating for the current version of the app")
    val averageUserRatingForCurrentVersion: Double? = null,
    @property:LLMDescription("the user rating count for the current version of the app")
    val userRatingCountForCurrentVersion: Int? = null,
    @property:LLMDescription("the average user rating for the app as a whole")
    val averageUserRating: Double? = null,
    @property:LLMDescription("track view URL for the app")
    val trackViewUrl: String? = null,
    @property:LLMDescription("the content rating for the app")
    val trackContentRating: String? = null,
    @property:LLMDescription("the bundle id for the app")
    val bundleId: String? = null,
    @property:LLMDescription("track id of the app")
    val trackId: String? = null,
    @property:LLMDescription("track name of the app")
    val trackName: String? = null,
    @property:LLMDescription("the release date of the app")
    val releaseDate: String? = null,
    @property:LLMDescription("the primary genre name of the app")
    val primaryGenreName: String? = null,
    @property:LLMDescription("genre IDs associated with the app")
    val genreIds: List<String>? = null,
    @property:LLMDescription("if VPP Device is enabled for the app")
    val isVppDeviceBasedLicensingEnabled: Boolean? = null,
    @property:LLMDescription("the current version of the app")
    val currentVersionReleaseDate: String? = null,
    @property:LLMDescription("the name of the app seller (developer)")
    val sellerName: String? = null,
    @property:LLMDescription("the release notes for the current version of the app")
    val releaseNotes: String? = null,
    @property:LLMDescription("the primary genre ID of the app")
    val primaryGenreId: Long? = null,
    @property:LLMDescription("currency used in the app")
    val currency: String? = null,
    @property:LLMDescription("the version of the app")
    val version: String? = null,
    val wrapperType: String? = null,
    @property:LLMDescription("app's artist id on the App Store")
    val artistId: String? = null,
    @property:LLMDescription("app's artist name on the App Store")
    val artistName: String? = null,
    @property:LLMDescription("app's primary genres on the App Store")
    val genres: List<String>? = null,
    @property:LLMDescription("price for the app")
    val price: Double? = null,
    @property:LLMDescription("app's description on the App Store")
    val description: String? = null,
    @property:LLMDescription("the user rating count for the app as a whole")
    val userRatingCount: Long? = null,
    @property:LLMDescription("content advisory rating for the app")
    val contentAdvisoryRating: String? = null
)

@LLMDescription("Toolset for get Apple App Info")
class AppleAppInfoTools(
    val httpAccess: HttpAccess
) : ToolSet {

    /**
     * iTunes lookup endpoint. `appId` used to be pasted directly into the query string —
     * if the LLM was tricked into passing `"123&limit=1000"` that would silently add a
     * second query parameter, and `"123?foo=bar"` would break URL parsing. We URL-encode
     * both values and additionally validate their shape (numeric appId, 2-letter country)
     * so a malicious input produces a clean error rather than an unexpected API response.
     */
    private suspend fun fetchAppleAppInfo(
        httpAccess: HttpAccess,
        appId: String,
        country: String = "us"
    ): List<AppleAppInfo> {
        require(appId.isNotBlank() && appId.all { it.isDigit() } && appId.length <= 20) {
            "Apple appId must be a numeric string (got '${appId.take(40)}')"
        }
        val cc = country.lowercase()
        require(cc.length == 2 && cc.all { it in 'a'..'z' }) {
            "Apple country must be a 2-letter ISO code (got '${country.take(20)}')"
        }
        val encodedId = java.net.URLEncoder.encode(appId, Charsets.UTF_8)
        val encodedCountry = java.net.URLEncoder.encode(cc, Charsets.UTF_8)
        val json = httpAccess.get<JsonObject>(
            url = "https://itunes.apple.com/lookup?id=$encodedId&cc=$encodedCountry",
            headers = mapOf("Accept" to "application/json")
        )
        return json["results"]?.jsonArray?.mapNotNull { Json.decodeFromJsonElement(it) } ?: emptyList()
    }

    @Tool
    @LLMDescription("Get Apple App Info")
    suspend fun getAppInfo(
        @LLMDescription("App ID")
        appId: String,
        @LLMDescription("Country code (optional, default us)")
        country: String = "us"
    ): List<AppleAppInfo> = fetchAppleAppInfo(httpAccess, appId, country)
}
