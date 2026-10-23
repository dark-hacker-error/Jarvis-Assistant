package com.jarvis.assistant.automation.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.contacts.ContactResolutionResult
import com.jarvis.assistant.automation.contacts.ContactResolver
import com.jarvis.assistant.util.ActivityLauncherHelper
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class PhoneControlManager(
    private val context: Context,
    private val contactResolver: ContactResolver? = null
) {

    companion object {
        private const val TAG = "PhoneControlManager"

        // Massively expanded app map with smart aliases
        private val POPULAR_APP_PACKAGES = mapOf(
            // Social Media
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "whats app" to "com.whatsapp",
            "facebook" to "com.facebook.katana",
            "fb" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "fb messenger" to "com.facebook.orca",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "snapchat" to "com.snapchat.android",
            "snap" to "com.snapchat.android",
            "telegram" to "org.telegram.messenger",
            "tg" to "org.telegram.messenger",
            "linkedin" to "com.linkedin.android",
            "pinterest" to "com.pinterest",
            "reddit" to "com.reddit.frontpage",
            "threads" to "com.instagram.barcelona",

            // AI Apps
            "chatgpt" to "com.openai.chatgpt",
            "chat gpt" to "com.openai.chatgpt",
            "openai" to "com.openai.chatgpt",
            "gemini" to "com.google.android.apps.bard",
            "google gemini" to "com.google.android.apps.bard",
            "bard" to "com.google.android.apps.bard",
            "canva" to "com.canva.editor",
            "copilot" to "com.microsoft.copilot",
            "perplexity" to "ai.perplexity.app.android",

            // Google Apps
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "mail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "google maps" to "com.google.android.apps.maps",
            "maps" to "com.google.android.apps.maps",
            "map" to "com.google.android.apps.maps",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "google pe" to "com.google.android.apps.nbu.paisa.user",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "g pay" to "com.google.android.apps.nbu.paisa.user",
            "gpe" to "com.google.android.apps.nbu.paisa.user",
            "gp" to "com.google.android.apps.nbu.paisa.user",
            "google play" to "com.google.android.apps.nbu.paisa.user",
            "pay" to "com.google.android.apps.nbu.paisa.user",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "youtube music" to "com.google.android.apps.youtube.music",
            "yt music" to "com.google.android.apps.youtube.music",
            "google photos" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos",
            "google drive" to "com.google.android.apps.docs",
            "drive" to "com.google.android.apps.docs",
            "google docs" to "com.google.android.apps.docs.editors.docs",
            "docs" to "com.google.android.apps.docs.editors.docs",
            "google sheets" to "com.google.android.apps.docs.editors.sheets",
            "sheets" to "com.google.android.apps.docs.editors.sheets",
            "google slides" to "com.google.android.apps.docs.editors.slides",
            "slides" to "com.google.android.apps.docs.editors.slides",
            "google translate" to "com.google.android.apps.translate",
            "translate" to "com.google.android.apps.translate",
            "google meet" to "com.google.android.apps.tachyon",
            "meet" to "com.google.android.apps.tachyon",
            "google lens" to "com.google.ar.lens",
            "lens" to "com.google.ar.lens",
            "google calendar" to "com.google.android.calendar",
            "calendar" to "com.google.android.calendar",
            "google keep" to "com.google.android.keep",
            "keep" to "com.google.android.keep",
            "notes" to "com.google.android.keep",
            "google assistant" to "com.google.android.apps.googleassistant",
            "assistant" to "com.google.android.apps.googleassistant",
            "playstore" to "com.android.vending",
            "play store" to "com.android.vending",
            "google play store" to "com.android.vending",
            "app store" to "com.android.vending",

            // Entertainment
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "amazon prime" to "com.amazon.avod.thirdpartyclient",
            "prime video" to "com.amazon.avod.thirdpartyclient",
            "prime" to "com.amazon.avod.thirdpartyclient",
            "hotstar" to "in.startv.hotstar",
            "disney hotstar" to "in.startv.hotstar",
            "jio cinema" to "com.jio.media.ondemand",
            "jiocinema" to "com.jio.media.ondemand",
            "zee5" to "com.graymatrix.did",
            "sony liv" to "com.sonyliv",

            // Finance & Payments
            "phonepe" to "com.phonepe.app",
            "phone pe" to "com.phonepe.app",
            "paytm" to "net.one97.paytm",
            "amazon" to "in.amazon.mShop.android.shopping",
            "amazon shopping" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "cred" to "com.dreamplug.androidapp",
            "myntra" to "com.myntra.android",

            // Productivity
            "calculator" to "com.google.android.calculator",
            "calc" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "alarm" to "com.google.android.deskclock",
            "timer" to "com.google.android.deskclock",
            "files" to "com.google.android.apps.nbu.files",
            "file manager" to "com.google.android.apps.nbu.files",
            "contacts" to "com.google.android.contacts",
            "dialer" to "com.google.android.dialer",
            "phone" to "com.google.android.dialer",

            // Communication
            "zoom" to "us.zoom.videomeetings",
            "teams" to "com.microsoft.teams",
            "microsoft teams" to "com.microsoft.teams",
            "skype" to "com.skype.raider",
            "signal" to "org.thoughtcrime.securesms",

            // Games
            "pubg" to "com.pubg.imobile",
            "bgmi" to "com.pubg.imobile",
            "free fire" to "com.dts.freefireth",
            "call of duty" to "com.activision.callofduty.shooter",
            "cod" to "com.activision.callofduty.shooter",

            // Utility
            "uber" to "com.ubercab",
            "ola" to "com.olacabs.customer",
            "swiggy" to "in.swiggy.android",
            "zomato" to "com.application.zomato",
            "weather" to "com.google.android.apps.weather"
        )
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @Volatile
    private var isFlashlightOn = false

    val isTorchOn: Boolean
        get() = isFlashlightOn

    init {
        try {
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    val torchId = findTorchCameraId()
                    if (torchId == null || cameraId == torchId) {
                        isFlashlightOn = enabled
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "Error registering torch callback: ${e.message}")
        }
    }

    // -------------------------------------------------------------
    // Brightness Controls (Fixed — reliable auto-brightness disable)
    // -------------------------------------------------------------

    // -------------------------------------------------------------
    // Brightness Controls (Android 15 + Legacy support)
    // -------------------------------------------------------------

    fun setBrightnessPercent(percent: Int): String {
        val clamped = percent.coerceIn(0, 100)
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "WRITE_SETTINGS permission is required for system brightness")
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ActivityLauncherHelper.startActivitySafely(context, intent)
            } catch (_: Exception) {}
            return "Please grant System Settings permission in Settings to allow brightness control."
        }

        return try {
            // Step 1: Force disable auto-brightness FIRST
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            // Step 2: Set screen_brightness_float (Required for Android 13/14/15, e.g. Vivo/OnePlus/Xiaomi)
            val floatValue = clamped / 100.0f
            try {
                Settings.System.putFloat(
                    context.contentResolver,
                    "screen_brightness_float",
                    floatValue
                )
            } catch (e: Exception) {
                Log.d(TAG, "screen_brightness_float not supported: ${e.message}")
            }

            // Step 3: Set legacy brightness value (0-255 range)
            val brightnessValue = ((clamped / 100.0) * 255).roundToInt().coerceIn(1, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )

            Log.i(TAG, "Brightness set to $clamped% (float=$floatValue, raw=$brightnessValue, mode=MANUAL)")
            "Screen brightness set to $clamped percent."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness: ${e.message}", e)
            "Failed to change brightness."
        }
    }

    fun adjustBrightness(increase: Boolean): String {
        if (!Settings.System.canWrite(context)) {
            return "Please grant System Settings permission to allow brightness adjustment."
        }

        // Force manual mode before adjusting
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
        } catch (_: Exception) {}

        // Read current brightness from float first, fallback to integer
        val currentPercent = try {
            val floatVal = Settings.System.getFloat(context.contentResolver, "screen_brightness_float")
            (floatVal * 100).roundToInt()
        } catch (_: Exception) {
            val intVal = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) {
                128
            }
            ((intVal / 255.0) * 100).roundToInt()
        }

        val delta = if (increase) 20 else -20
        return setBrightnessPercent(currentPercent + delta)
    }

    // -------------------------------------------------------------
    // Volume Controls
    // -------------------------------------------------------------

    fun setVolumePercent(percent: Int): String {
        val clamped = percent.coerceIn(0, 100)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = ((clamped / 100.0) * maxVolume).roundToInt().coerceIn(0, maxVolume)

        return try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetVolume,
                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
            )
            try {
                if (clamped == 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                }
            } catch (_: Exception) {}

            if (clamped == 0) {
                "Media volume muted."
            } else {
                "Volume set to $clamped percent."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume: ${e.message}", e)
            "Could not change volume."
        }
    }

    fun adjustVolume(increase: Boolean): String {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Ensure a noticeable jump of at least 15% per step
        val step = max(1, (maxVolume * 0.15).roundToInt())
        val targetVolume = (if (increase) currentVolume + step else currentVolume - step).coerceIn(0, maxVolume)

        return try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetVolume,
                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
            )
            val percent = ((targetVolume / maxVolume.toFloat()) * 100).roundToInt()
            if (increase) "Volume increased to $percent percent." else "Volume decreased to $percent percent."
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting volume: ${e.message}", e)
            "Failed to adjust volume."
        }
    }

    fun muteVolume(mute: Boolean): String {
        return if (mute) {
            setVolumePercent(0)
        } else {
            setVolumePercent(50)
        }
    }

    // -------------------------------------------------------------
    // Phone Calling (Cellular / SIM Dialer)
    // -------------------------------------------------------------

    fun makePhoneCall(contactOrNumber: String): String {
        val clean = contactOrNumber.trim()
        if (clean.isBlank()) return "Who would you like to call?"

        val rawDigits = clean.replace(Regex("[^0-9+]"), "")
        val isRawNumber = rawDigits.length >= 7 && (clean.startsWith("+") || clean.all { it.isDigit() || it.isWhitespace() || it == '-' })

        val phoneNumber: String
        val displayName: String

        if (isRawNumber) {
            phoneNumber = rawDigits
            displayName = rawDigits
        } else {
            val resolver = contactResolver ?: ContactResolver(context)
            val res = resolver.resolveContact(clean)
            when (res) {
                is ContactResolutionResult.SingleMatch -> {
                    phoneNumber = res.contact.phoneNumber
                    displayName = res.contact.name
                }
                is ContactResolutionResult.MultipleMatches -> {
                    val names = res.candidates.take(3).joinToString(" or ") { it.name }
                    return "Which $clean? I found $names."
                }
                else -> {
                    return "I could not find a phone number for $clean."
                }
            }
        }

        if (phoneNumber.isBlank()) {
            return "No phone number found for $clean."
        }

        return try {
            val hasCallPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            ActivityLauncherHelper.startActivitySafely(context, intent)
            "Calling $displayName."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place call: ${e.message}", e)
            "Could not initiate phone call."
        }
    }

    // -------------------------------------------------------------
    // Flashlight Controls
    // -------------------------------------------------------------

    fun setFlashlight(enabled: Boolean): String {
        return try {
            val cameraId = findTorchCameraId() ?: return "No flashlight hardware detected on this device."
            cameraManager.setTorchMode(cameraId, enabled)
            isFlashlightOn = enabled
            if (enabled) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: Exception) {
            Log.e(TAG, "Error setting torch mode: ${e.message}", e)
            "Unable to access the flashlight."
        }
    }

    fun toggleFlashlight(): String {
        return setFlashlight(!isFlashlightOn)
    }

    private fun findTorchCameraId(): String? {
        return try {
            val ids = cameraManager.cameraIdList
            // 1. Back-facing camera with flash
            for (id in ids) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                if (flashAvailable && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            // 2. Any camera with flash available
            for (id in ids) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    return id
                }
            }
            Log.w(TAG, "No camera with FLASH_INFO_AVAILABLE == true found in: ${ids.toList()}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "findTorchCameraId failed: ${e.message}", e)
            null
        }
    }

    // -------------------------------------------------------------
    // Web Search & URL Opening (No API needed — opens Chrome/Browser)
    // -------------------------------------------------------------

    fun openUrl(rawUrl: String): String {
        val normalized = DomainNormalizer.normalizeToUrl(rawUrl)
        val uri = Uri.parse(normalized)
        val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, context.packageName)
            putExtra("com.android.browser.application_id", context.packageName)
        }

        return if (ActivityLauncherHelper.startActivitySafely(context, chromeIntent)) {
            "Opening $normalized in Chrome."
        } else {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, context.packageName)
            }
            if (ActivityLauncherHelper.startActivitySafely(context, fallbackIntent)) {
                "Opening $normalized."
            } else {
                "Could not open $rawUrl."
            }
        }
    }

    suspend fun openUrlsInChromeTabs(urls: List<String>): Boolean {
        if (urls.isEmpty()) return false
        var anyLaunched = false
        val baseTime = System.currentTimeMillis()

        for ((index, rawUrl) in urls.withIndex()) {
            val urlString = if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
                rawUrl
            } else {
                "https://$rawUrl"
            }
            val uri = Uri.parse(urlString)
            val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, context.packageName)
                putExtra("com.android.browser.application_id", context.packageName)
            }

            val launched = ActivityLauncherHelper.startActivitySafely(context, chromeIntent)
            if (launched) {
                anyLaunched = true
            } else {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, context.packageName)
                }
                if (ActivityLauncherHelper.startActivitySafely(context, fallbackIntent)) {
                    anyLaunched = true
                }
            }

            // Delay 400ms between multiple tabs so Chrome creates separate tab tasks
            if (urls.size > 1 && index < urls.size - 1) {
                delay(400L)
            }
        }
        return anyLaunched
    }

    suspend fun openMultipleUrls(rawUrls: List<String>): String {
        if (rawUrls.isEmpty()) return "No websites specified to open."
        val ok = openUrlsInChromeTabs(rawUrls)
        return if (ok) {
            if (rawUrls.size == 1) {
                "Opening ${DomainNormalizer.normalizeToUrl(rawUrls.first())} in browser."
            } else {
                "Opening ${rawUrls.size} websites in separate tabs."
            }
        } else {
            "Could not open specified websites."
        }
    }

    fun searchWeb(query: String): String {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return "Please specify what you want to search for."
        }

        // Direct URL / Domain check
        val domainRegex = Regex(".*\\b([a-zA-Z0-9-]+\\.(?:com|org|net|in|io|co|ai|edu|gov|app|dev|me)(?:/[^\\s]*)?)\\b.*", RegexOption.IGNORE_CASE)
        val domainMatch = domainRegex.find(cleanQuery)
        if (domainMatch != null || cleanQuery.startsWith("http://") || cleanQuery.startsWith("https://") || cleanQuery.startsWith("www.")) {
            val urlTarget = domainMatch?.groupValues?.get(1) ?: cleanQuery
            return openUrl(urlTarget)
        }

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "https://www.google.com/search?q=$encoded"
        return openUrl(searchUrl)
    }

    fun extractUrlsFromText(input: String): List<String> {
        val urls = mutableListOf<String>()
        val explicitUrlRegex = Regex("""https?://[^\s,"'<>]+""", RegexOption.IGNORE_CASE)
        for (m in explicitUrlRegex.findAll(input)) {
            val u = m.value.trimEnd('.', ',', ';', '!', '?', ')')
            if (u.isNotEmpty() && !urls.contains(u)) urls.add(u)
        }

        val domainRegex = Regex("""\b(?:www\.)?([a-zA-Z0-9-]+\.)+(com|in|org|net|io|co|dev|app|ai|gov|edu|me|tech|info|online|xyz|site|store|cc|tv|uk|us|ca|de|fr|jp|cn|biz)(/[^\s,"'<>]*)?\b""", RegexOption.IGNORE_CASE)
        for (m in domainRegex.findAll(input)) {
            val clean = m.value.trimEnd('.', ',', ';', '!', '?', ')')
            val full = if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) clean else "https://$clean"
            if (!urls.any { it.equals(full, ignoreCase = true) || it.contains(clean, ignoreCase = true) }) {
                urls.add(full)
            }
        }
        return urls
    }

    fun parseTargetUrl(query: String): String? {
        var clean = query.trim()
        val lower = clean.lowercase(Locale.ROOT)

        val prefixes = listOf("open website", "open site", "visit website", "visit site", "open url", "navigate to", "visit", "open")
        for (prefix in prefixes) {
            if (lower.startsWith(prefix)) {
                val candidate = clean.substring(prefix.length).trim()
                if (candidate.isNotEmpty()) {
                    clean = candidate
                    break
                }
            }
        }

        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return clean
        }

        val cleanLower = clean.lowercase(Locale.ROOT)
        // Smart URL resolution for web-only creative tools & explicit website requests
        when {
            cleanLower.contains("google flow") || cleanLower.contains("flow website") || cleanLower == "flow" || cleanLower.contains("flow ai") -> return "https://flow.google.com"
            cleanLower.contains("chatgpt.com") || cleanLower.contains("chatgpt website") || cleanLower.contains("chatgpt site") -> return "https://chatgpt.com"
            cleanLower.contains("canva.com") || cleanLower.contains("canva website") || cleanLower.contains("canva site") -> return "https://www.canva.com"
            cleanLower.contains("gemini.google") || cleanLower.contains("gemini website") || cleanLower.contains("gemini site") -> return "https://gemini.google.com"
            cleanLower.contains("runway") -> return "https://runwayml.com"
            cleanLower.contains("pika") -> return "https://pika.art"
            cleanLower.contains("luma") -> return "https://lumalabs.ai"
            cleanLower.contains("midjourney") -> return "https://www.midjourney.com"
            cleanLower.contains("kling") -> return "https://klingai.com"
        }
        val domainRegex = Regex("""^([a-zA-Z0-9-]+\.)+(com|in|org|net|io|co|dev|app|ai|gov|edu|me|tech|info|online|xyz|site|store|cc|tv|uk|us|ca|de|fr|jp|cn|biz)(/.*)?$""")
        if (domainRegex.matches(cleanLower)) {
            return "https://$clean"
        }

        if (cleanLower.startsWith("www.") || (cleanLower.contains(".") && !cleanLower.contains(" ") && cleanLower.indexOf(".") < cleanLower.length - 2)) {
            return "https://$clean"
        }

        return null
    }

    // -------------------------------------------------------------
    // App Launching (Smart resolution with 100+ aliases)
    // -------------------------------------------------------------

    fun openApp(appNameOrPackage: String): String {
        val clean = appNameOrPackage.trim().lowercase(Locale.ROOT)
        Log.i(TAG, "openApp called with: '$clean'")

        // Check if query is actually a URL or domain: e.g. "open github.com"
        val domainRegex = Regex(".*\\b([a-zA-Z0-9-]+\\.(?:com|org|net|in|io|co|ai|edu|gov|app|dev|me)(?:/[^\\s]*)?)\\b.*", RegexOption.IGNORE_CASE)
        val domainMatch = domainRegex.find(clean)
        if (domainMatch != null || clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("www.")) {
            val urlTarget = domainMatch?.groupValues?.get(1) ?: clean
            return openUrl(urlTarget)
        }

        // Special case: Camera
        if (clean == "camera" || clean == "cam") {
            val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                ActivityLauncherHelper.startActivitySafely(context, cameraIntent)
                "Opening Camera."
            } catch (e: Exception) {
                // Try direct package
                val pkg = resolveInstalledPackageByName("camera") ?: "com.android.camera"
                context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    ActivityLauncherHelper.startActivitySafely(context, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return "Opening Camera."
                }
                "Failed to open Camera."
            }
        }

        // Special case: Settings
        if (clean == "settings" || clean == "setting") {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                ActivityLauncherHelper.startActivitySafely(context, settingsIntent)
                "Opening Settings."
            } catch (e: Exception) {
                "Failed to open Settings."
            }
        }

        // Special case: Gallery/Photos
        if (clean == "gallery" || clean == "photo gallery" || clean == "photos") {
            val pkg = resolveInstalledPackageByName("gallery")
                ?: resolveInstalledPackageByName("photos")
                ?: "com.google.android.apps.photos"
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                ActivityLauncherHelper.startActivitySafely(context, launchIntent)
                return "Opening Gallery."
            }
            val galleryIntent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                ActivityLauncherHelper.startActivitySafely(context, galleryIntent)
                "Opening Gallery."
            } catch (e: Exception) {
                "Failed to open Gallery."
            }
        }

        // Smart Alias check: if user says "google play", smartly check if GPay is intended
        var targetPackage: String? = null
        if (clean == "google play") {
            val gpayPkg = "com.google.android.apps.nbu.paisa.user"
            val hasGPay = try {
                context.packageManager.getPackageInfo(gpayPkg, 0) != null
            } catch (_: Exception) { false }
            targetPackage = if (hasGPay) gpayPkg else "com.android.vending"
        } else {
            // Step 1: Try exact match from smart aliases map
            targetPackage = POPULAR_APP_PACKAGES[clean]
                ?: POPULAR_APP_PACKAGES[clean.replace(" ", "")]
                ?: POPULAR_APP_PACKAGES[clean.replace("-", "")]
        }

        // Step 2: Search installed launcher activities and applications
        if (targetPackage == null) {
            targetPackage = resolveInstalledPackageByName(clean)
        }

        if (targetPackage != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                return try {
                    val launched = ActivityLauncherHelper.startActivitySafely(context, launchIntent)
                    Log.i(TAG, "Successfully launched via ActivityLauncherHelper: $targetPackage ($launched)")
                    "Opening ${clean.replaceFirstChar { it.uppercase() }}."
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch $targetPackage: ${e.message}")
                    "Failed to launch app."
                }
            } else {
                Log.w(TAG, "Package $targetPackage found but no launch intent available")
            }
        }

        // Fallback: If no app is installed on phone, check if it is a known web-only platform (e.g. Google Flow)
        val webFallbackUrl = parseTargetUrl(clean)
        if (webFallbackUrl != null) {
            Log.i(TAG, "No native app found for '$clean', falling back to web platform: $webFallbackUrl")
            return openUrl(webFallbackUrl)
        }

        return "I could not find $appNameOrPackage installed on this device."
    }

    private fun resolveInstalledPackageByName(query: String): String? {
        val pm = context.packageManager
        val cleanQuery = query.lowercase(Locale.ROOT).trim()

        // 1. Query launcher activities first (guarantees launchable apps)
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            emptyList()
        }

        val appEntries = mutableListOf<Pair<String, String>>() // Pair(label, packageName)
        for (ri in resolveList) {
            val label = ri.loadLabel(pm).toString().lowercase(Locale.ROOT).trim()
            val pkg = ri.activityInfo.packageName
            appEntries.add(Pair(label, pkg))
        }

        // Pass 1: Exact match on label
        for ((label, pkg) in appEntries) {
            if (label == cleanQuery) return pkg
        }

        // Pass 2: Normalized match (spaces/punctuation removed, e.g. "google pay" == "googlepay")
        val normalizedQuery = cleanQuery.replace(Regex("[\\s._-]+"), "")
        for ((label, pkg) in appEntries) {
            val normLabel = label.replace(Regex("[\\s._-]+"), "")
            if (normLabel == normalizedQuery) return pkg
        }

        // Pass 3: Label contains query or query contains label
        for ((label, pkg) in appEntries) {
            if (label.contains(cleanQuery) || cleanQuery.contains(label)) return pkg
        }

        // Pass 4: Word prefix match (e.g. "insta" matches "Instagram")
        for ((label, pkg) in appEntries) {
            val words = label.split(" ", ".", "_", "-")
            if (words.any { it.startsWith(cleanQuery) || cleanQuery.startsWith(it) }) return pkg
        }

        // Pass 5: Levenshtein fuzzy similarity
        var bestPkg: String? = null
        var bestScore = 0.65f
        for ((label, pkg) in appEntries) {
            val score = calculateStringSimilarity(cleanQuery, label)
            if (score > bestScore) {
                bestScore = score
                bestPkg = pkg
            }
        }
        if (bestPkg != null) return bestPkg

        // Fallback: check all installed applications metadata
        try {
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installed) {
                val label = pm.getApplicationLabel(app).toString().lowercase(Locale.ROOT).trim()
                if (label.contains(cleanQuery) || cleanQuery.contains(label)) {
                    if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                        return app.packageName
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun calculateStringSimilarity(s1: String, s2: String): Float {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        if (longer.isEmpty()) return 1.0f
        val dist = levenshtein(longer, shorter)
        return (longer.length - dist).toFloat() / longer.length.toFloat()
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        var cost = IntArray(lhs.length + 1) { it }
        var newCost = IntArray(lhs.length + 1) { 0 }
        for (i in 1..rhs.length) {
            newCost[0] = i
            for (j in 1..lhs.length) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = kotlin.math.min(kotlin.math.min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhs.length]
    }
}
