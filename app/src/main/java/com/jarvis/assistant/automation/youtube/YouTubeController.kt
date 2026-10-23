package com.jarvis.assistant.automation.youtube

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.util.ActivityLauncherHelper

/**
 * Everything JARVIS needs to actually control YouTube:
 *  - search + jump straight to a video (deep link, skips typing in-app)
 *  - play/pause/skip via standard Android media key events (works because
 *    YouTube registers a MediaSession while a video is playing, the same
 *    mechanism headset buttons use)
 *  - volume control
 */
object YouTubeController {

    data class PlayResult(val success: Boolean, val title: String?, val message: String?)

    /**
     * Cleans noise and command words from the spoken query (e.g. "play 30 songs on YouTube" -> "30 songs").
     */
    fun cleanQuery(rawQuery: String): String {
        var clean = rawQuery.trim()
        val patterns = listOf(
            Regex("""\b(on|in|from|or|and)\s+(youtube|yt)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(youtube|yt)\s+(pe|par|mein|video|song|songs|audio)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(youtube|yt)\b""", RegexOption.IGNORE_CASE),
            Regex("""^\s*(play|open|search|listen\s+to)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(chalao|chala\s+do|karo|kar\s+do|lagao|laga\s+do|sunao|suna\s+do|bajao|baja\s+do|dikhao|dikha\s+do)\b""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            clean = pattern.replace(clean, " ")
        }
        // Remove trailing noise words like "song", "video", "gana" if preceded by title
        clean = clean.replace(Regex("""(?i)\s+(song|songs|video|videos|gana|gane|track)$"""), "")
        val result = clean.replace(Regex("""\s+"""), " ").trim()
        return if (result.isNotBlank()) result else rawQuery.trim()
    }

    /**
     * Searches YouTube for [query] and opens the top result directly in the
     * player (via the `vnd.youtube:` deep link, which YouTube's app handles
     * natively). Falls back to zero-quota direct search, then native
     * MEDIA_PLAY_FROM_SEARCH intent, so videos ALWAYS play directly.
     */
    fun searchAndPlay(context: Context, query: String, explicitApiKey: String? = null): PlayResult {
        var cleanQ = cleanQuery(query)
        if (cleanQ.isBlank() || cleanQ.equals("video", ignoreCase = true) || cleanQ.equals("song", ignoreCase = true) ||
            cleanQ.equals("a video", ignoreCase = true) || cleanQ.equals("a song", ignoreCase = true) ||
            cleanQ.equals("music", ignoreCase = true) || cleanQ.equals("songs", ignoreCase = true)) {
            val fav = try { JarvisApp.instance.memoryManager.resolveSongQuery("my favorite song") } catch (_: Exception) { "Arijit Singh top hits" }
            cleanQ = fav.ifBlank { "Trending music" }
        }

        val apiKey = explicitApiKey ?: try {
            JarvisApp.instance.youTubePreferences.youtubeApiKey
        } catch (_: Exception) { "" }
        android.util.Log.i("YouTubeController", "searchAndPlay STRICT PLAY requested for: \"$query\" -> clean: \"$cleanQ\" (apiKey present: ${apiKey.isNotBlank()})")

        // 1. Try search via YouTubeApiClient (Data API if key present, else zero-quota direct scraper)
        var result = YouTubeApiClient.searchTopVideo(apiKey, cleanQ)
        var videoId = result?.videoId?.takeIf { it.isNotBlank() }

        // 2. Secondary scraper fallback via YouTubeAutomationManager if needed
        if (videoId == null) {
            try {
                videoId = JarvisApp.instance.youTubeAutomationManager.scrapeFirstVideoId(cleanQ)
                if (!videoId.isNullOrBlank()) {
                    android.util.Log.i("YouTubeController", "YouTubeAutomationManager scraper found videoId: $videoId")
                }
            } catch (e: Exception) {
                android.util.Log.w("YouTubeController", "Secondary scraper exception: ${e.message}")
            }
        }

        // 3. DIRECT PLAYBACK: If videoId found, deep-link straight into video playback
        if (!videoId.isNullOrBlank()) {
            val title = result?.title?.takeIf { it.isNotBlank() } ?: cleanQ
            val played = playVideoId(context, videoId)
            if (played) {
                android.util.Log.i("YouTubeController", "Successfully launched direct video playback for $videoId ($title)")
                return PlayResult(true, title, "Playing \"$title\" on YouTube.")
            }
        }

        // 4. Fallback if scraping was blocked: Open YouTube search results AND ARM ACCESSIBILITY AUTO-PLAY
        // to immediately tap and play the first video result. NEVER open search box with keyboard.
        return try {
            android.util.Log.i("YouTubeController", "Arming accessibility auto-play fallback for \"$cleanQ\"")
            com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.triggerYouTubeAutoPlay()

            val encoded = Uri.encode(cleanQ)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            PlayResult(true, cleanQ, "Playing \"$cleanQ\" on YouTube.")
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Playback fallback failed", e)
            PlayResult(false, null, "Couldn't open YouTube.")
        }
    }

    /**
     * Searches YouTube for [query] and presents the search results screen ONLY.
     * Does NOT auto-click or play the top result.
     */
    fun searchYouTube(context: Context, query: String): PlayResult {
        val cleanQ = cleanQuery(query)
        return try {
            val encoded = Uri.encode(cleanQ)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (ok) {
                PlayResult(true, null, "Showing YouTube search results for \"$cleanQ\".")
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded&gl=IN&hl=en")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ActivityLauncherHelper.startActivitySafely(context, webIntent)
                PlayResult(true, null, "Showing YouTube search results for \"$cleanQ\".")
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "YouTube search intent failed", e)
            PlayResult(false, null, "Couldn't open YouTube.")
        }
    }

    /** Opens a specific video ID directly in the YouTube app's player. */
    fun playVideoId(context: Context, videoId: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (launched) {
                true
            } else {
                val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val launchedApp = ActivityLauncherHelper.startActivitySafely(context, appIntent)
                if (launchedApp) {
                    true
                } else {
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/watch?v=$videoId")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ActivityLauncherHelper.startActivitySafely(context, webIntent)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Deep link play failed, trying web fallback", e)
            try {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/watch?v=$videoId")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ActivityLauncherHelper.startActivitySafely(context, webIntent)
            } catch (e2: Exception) {
                android.util.Log.e("YouTubeController", "Web fallback also failed", e2)
                false
            }
        }
    }

    /**
     * Sends a standard media key event (play/pause/next/previous/stop).
     * Controls whatever app currently holds the active media session —
     * in practice this is YouTube whenever a video is actively playing.
     */
    fun sendMediaKey(context: Context, action: String): Boolean {
        val keyCode = when (action.lowercase()) {
            "play", "pause", "play_pause", "toggle", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip", "skip_next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "back", "skip_previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return false
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Media key dispatch failed", e)
            false
        }
    }

    /** direction: "up" or "down". Adjusts the media/music volume stream. */
    fun adjustVolume(context: Context, direction: String): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val adjust = when (direction.lowercase()) {
            "up", "increase", "louder" -> AudioManager.ADJUST_RAISE
            "down", "decrease", "lower", "quieter" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            else -> return false
        }
        return try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            android.util.Log.e("YouTubeController", "Volume adjust failed", e)
            false
        }
    }
}
