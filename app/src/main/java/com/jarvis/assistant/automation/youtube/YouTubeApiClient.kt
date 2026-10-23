package com.jarvis.assistant.automation.youtube

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the YouTube Data API v3 search endpoint.
 * Used so JARVIS can jump straight to the right video instead of
 * relying on typing into YouTube's own search box.
 */
object YouTubeApiClient {

    data class VideoResult(val videoId: String, val title: String, val channelTitle: String)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val networkExecutor = java.util.concurrent.Executors.newCachedThreadPool()

    /**
     * Returns the top search result for [query].
     * Tries the official YouTube Data API first if [apiKey] is provided.
     * If the API key is absent, invalid, or exhausted by daily quota (HTTP 429),
     * automatically falls back to direct search parsing which requires 0 quota.
     * Safe to call from ANY thread (automatically dispatches to background executor if on main thread).
     */
    fun searchTopVideo(apiKey: String, query: String): VideoResult? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try {
                networkExecutor.submit<VideoResult?> { searchTopVideoInternal(apiKey, query) }.get(8, TimeUnit.SECONDS)
            } catch (e: Exception) {
                android.util.Log.e("YouTubeApiClient", "Background worker searchTopVideo failed: ${e.message}")
                null
            }
        }
        return searchTopVideoInternal(apiKey, query)
    }

    private fun searchTopVideoInternal(apiKey: String, query: String): VideoResult? {
        val clean = YouTubeController.cleanQuery(query).ifBlank { query.trim() }
        if (clean.isBlank()) return null

        if (apiKey.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(clean, "UTF-8")
            val url = "https://www.googleapis.com/youtube/v3/search" +
                    "?part=snippet&type=video&maxResults=1&q=$encodedQuery&regionCode=IN&key=$apiKey"

            val request = Request.Builder().url(url).get().build()

            try {
                val response = client.newCall(request).execute()
                try {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = JSONObject(body)
                            val items = json.optJSONArray("items")
                            if (items != null && items.length() > 0) {
                                val item = items.getJSONObject(0)
                                val videoId = item.optJSONObject("id")?.optString("videoId") ?: ""
                                val snippet = item.optJSONObject("snippet")
                                val title = snippet?.optString("title") ?: clean
                                val channel = snippet?.optString("channelTitle") ?: ""

                                if (videoId.isNotBlank()) {
                                    android.util.Log.d("YouTubeApiClient", "Found video via Data API: $videoId ($title)")
                                    return VideoResult(videoId, title, channel)
                                }
                            }
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "(no body)"
                        android.util.Log.w("YouTubeApiClient", "API search failed (HTTP ${response.code}), falling back to direct search. Error: $errorBody")
                    }
                } finally {
                    response.close()
                }
            } catch (e: Exception) {
                android.util.Log.w("YouTubeApiClient", "API request exception: ${e.message}, falling back to direct search")
            }
        }

        // Direct search fallback: 0 quota units, works even when API key is rate-limited (HTTP 429)
        return searchTopVideoDirect(clean)
    }

    /**
     * Directly parses the YouTube search results web page to extract the top videoId and title.
     * Consumes ZERO YouTube Data API quota and works reliably without API keys.
     * Safe to call from ANY thread.
     */
    fun searchTopVideoDirect(query: String): VideoResult? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try {
                networkExecutor.submit<VideoResult?> { searchTopVideoDirectInternal(query) }.get(8, TimeUnit.SECONDS)
            } catch (e: Exception) {
                android.util.Log.e("YouTubeApiClient", "Background worker searchTopVideoDirect failed: ${e.message}")
                null
            }
        }
        return searchTopVideoDirectInternal(query)
    }

    private fun searchTopVideoDirectInternal(query: String): VideoResult? {
        val clean = YouTubeController.cleanQuery(query)
        if (clean.isBlank()) return null

        return try {
            val encodedQuery = URLEncoder.encode(clean, "UTF-8")
            val url = "https://www.youtube.com/results?search_query=$encodedQuery&gl=IN&hl=en"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
                .header("Cookie", "CONSENT=YES+1; PREF=tz=UTC")
                .get()
                .build()

            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    android.util.Log.e("YouTubeApiClient", "Direct web search failed: HTTP ${response.code}")
                    return null
                }

                val html = response.body?.string() ?: return null

                // 1. Primary: Extract videoId from videoRenderer object
                val rendererRegex = Regex("\"videoRenderer\"\\s*:\\s*\\{[^}]*\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\"")
                var videoMatch = rendererRegex.find(html)
                var videoId = videoMatch?.groupValues?.get(1)

                // 2. Fallbacks if videoRenderer format shifted
                if (videoId.isNullOrBlank()) {
                    val genericRegex = Regex("\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\"")
                    videoId = genericRegex.find(html)?.groupValues?.get(1)
                }
                if (videoId.isNullOrBlank()) {
                    val mobileWatchRegex = Regex("""watch\?v=([a-zA-Z0-9_-]{11})""")
                    videoId = mobileWatchRegex.find(html)?.groupValues?.get(1)
                }

                if (videoId.isNullOrBlank()) {
                    android.util.Log.w("YouTubeApiClient", "Could not extract videoId from YouTube search page")
                    return null
                }

                // 3. Extract title
                val titleRendererRegex = Regex("\"videoRenderer\"\\s*:\\s*\\{.*?\"title\"\\s*:\\s*\\{\\s*\"runs\"\\s*:\\s*\\[\\s*\\{\\s*\"text\"\\s*:\\s*\"([^\"]+)\"")
                val titleRegexFallback = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"\\}")
                val rawTitle = titleRendererRegex.find(html)?.groupValues?.get(1)
                    ?: titleRegexFallback.find(html)?.groupValues?.get(1)
                    ?: clean

                val title = rawTitle.replace("\\u0026", "&")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")

                android.util.Log.i("YouTubeApiClient", "Direct search matched top video: $videoId ($title)")
                VideoResult(videoId, title, "")
            } finally {
                response.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTubeApiClient", "Direct search failed: ${e.message}", e)
            null
        }
    }
}
