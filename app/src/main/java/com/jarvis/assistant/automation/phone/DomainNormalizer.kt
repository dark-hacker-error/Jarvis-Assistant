package com.jarvis.assistant.automation.phone

import java.util.Locale
import kotlin.math.min

object DomainNormalizer {

    private val KNOWN_DOMAINS = mapOf(
        // Tech & Dev
        "vercel" to "vercel.com",
        "wersel" to "vercel.com",
        "varsel" to "vercel.com",
        "versal" to "vercel.com",
        "vercell" to "vercel.com",
        "21st" to "21st.dev",
        "twenty first" to "21st.dev",
        "twenty-first" to "21st.dev",
        "21st dev" to "21st.dev",
        "twenty first dev" to "21st.dev",
        "github" to "github.com",
        "git hub" to "github.com",
        "git-hub" to "github.com",
        "gethub" to "github.com",
        "geethub" to "github.com",
        "gitlab" to "gitlab.com",
        "git lab" to "gitlab.com",
        "stackoverflow" to "stackoverflow.com",
        "stack overflow" to "stackoverflow.com",
        "chatgpt" to "chatgpt.com",
        "chat gpt" to "chatgpt.com",
        "chad gpt" to "chatgpt.com",
        "openai" to "openai.com",
        "open ai" to "openai.com",
        "anthropic" to "anthropic.com",
        "claude" to "claude.ai",
        "gemini" to "gemini.google.com",
        "google flow" to "labs.google/flow",
        "flow" to "labs.google/flow",
        "flow google" to "labs.google/flow",
        "googleflow" to "labs.google/flow",
        "flow website" to "labs.google/flow",
        "runway" to "runwayml.com",
        "runwayml" to "runwayml.com",
        "pika" to "pika.art",
        "midjourney" to "midjourney.com",
        "canva" to "canva.com",
        "leonardo" to "leonardo.ai",
        "elevenlabs" to "elevenlabs.io",
        "suno" to "suno.com",
        "luma" to "lumalabs.ai",
        "kling" to "klingai.com",
        "huggingface" to "huggingface.co",
        "hugging face" to "huggingface.co",
        "firebase" to "firebase.google.com",
        "aws" to "aws.amazon.com",

        // Search & Google
        "google" to "google.com",
        "gmail" to "mail.google.com",
        "g mail" to "mail.google.com",
        "google mail" to "mail.google.com",
        "google drive" to "drive.google.com",
        "gdrive" to "drive.google.com",
        "drive" to "drive.google.com",
        "google maps" to "maps.google.com",
        "maps" to "maps.google.com",

        // Social Media & Video
        "youtube" to "youtube.com",
        "you tube" to "youtube.com",
        "u tube" to "youtube.com",
        "utube" to "youtube.com",
        "yt" to "youtube.com",
        "twitter" to "x.com",
        "twiter" to "x.com",
        "x" to "x.com",
        "facebook" to "facebook.com",
        "face book" to "facebook.com",
        "fb" to "facebook.com",
        "instagram" to "instagram.com",
        "insta gram" to "instagram.com",
        "insta" to "instagram.com",
        "linkedin" to "linkedin.com",
        "link din" to "linkedin.com",
        "linked in" to "linkedin.com",
        "linkdin" to "linkedin.com",
        "reddit" to "reddit.com",
        "red it" to "reddit.com",
        "pinterest" to "pinterest.com",
        "pin terest" to "pinterest.com",
        "snapchat" to "snapchat.com",
        "snap" to "snapchat.com",
        "telegram" to "web.telegram.org",
        "discord" to "discord.com",
        "whatsapp" to "web.whatsapp.com",

        // Media & Streaming
        "netflix" to "netflix.com",
        "net flix" to "netflix.com",
        "spotify" to "spotify.com",
        "spo ti fy" to "spotify.com",
        "prime video" to "primevideo.com",
        "amazon prime" to "primevideo.com",
        "hotstar" to "hotstar.com",
        "jiocinema" to "jiocinema.com",
        "jio cinema" to "jiocinema.com",

        // Shopping & Food & Finance
        "amazon" to "amazon.in",
        "ama zon" to "amazon.in",
        "flipkart" to "flipkart.com",
        "flip kart" to "flipkart.com",
        "myntra" to "myntra.com",
        "meesho" to "meesho.com",
        "swiggy" to "swiggy.com",
        "zomato" to "zomato.com",
        "blinkit" to "blinkit.com",
        "zepto" to "zepto.com",
        "cricbuzz" to "cricbuzz.com",

        // Reference & Learning
        "wikipedia" to "wikipedia.org",
        "wiki pedia" to "wikipedia.org",
        "coursera" to "coursera.org",
        "udemy" to "udemy.com"
    )

    fun isKnownWebAlias(target: String): Boolean {
        val clean = target.trim().lowercase(Locale.ROOT)
            .replace(Regex("^(https?://|www\\.)"), "")
            .replace(Regex("^(the\\s+)?(website|site|web)\\s+"), "")
            .replace(Regex("\\s+(website|site|web|in browser|in chrome)$"), "")
            .replace(Regex("\\.(com|org|net|in|io|co|ai|edu|gov|app|dev|me)$"), "")
            .trim()
        return KNOWN_DOMAINS.containsKey(clean) || KNOWN_DOMAINS.containsKey(target.trim().lowercase(Locale.ROOT))
    }

    /**
     * Normalizes a raw string (spoken domain or website alias) into a full valid URL.
     * e.g. "wersel.com" -> "https://vercel.com"
     * e.g. "wersel" -> "https://vercel.com"
     * e.g. "github.com" -> "https://github.com"
     */
    fun normalizeToUrl(raw: String): String {
        var clean = raw.trim().lowercase(Locale.ROOT)
            .replace(Regex("\\s+dot\\s+"), ".")
            .replace(Regex("\\bdot\\s+(com|org|net|in|io|co|ai|edu|gov|app|dev|me)\\b"), ".$1")
            .replace("twenty first", "21st")
            .replace("twenty-first", "21st")
            .replace(Regex("^(https?://|www\\.)"), "")
            .replace(Regex("^(the\\s+)?(website|site|web)\\s+"), "")
            .replace(Regex("\\s+(website|site|web|in browser|in chrome)$"), "")
            .replace(Regex("[.,/\\s]+$"), "")

        // Check if ends with a domain extension
        val withoutExt = clean.replace(Regex("\\.(com|org|net|in|io|co|ai|edu|gov|app|dev|me)$"), "").trim()

        // 1. Exact alias match
        KNOWN_DOMAINS[clean]?.let { return "https://$it" }
        KNOWN_DOMAINS[withoutExt]?.let { return "https://$it" }

        // 2. Fuzzy match against known domain keys
        var bestDomain: String? = null
        var bestDistance = 2 // allow max 2 typos/phonetic diffs for words >= 5 chars
        for ((key, domain) in KNOWN_DOMAINS) {
            if (key.length >= 4 && (withoutExt.length >= 4 || clean.length >= 4)) {
                val d1 = levenshtein(withoutExt, key)
                val d2 = levenshtein(clean, key)
                val minD = min(d1, d2)
                if (minD < bestDistance) {
                    bestDistance = minD
                    bestDomain = domain
                }
            }
        }
        if (bestDomain != null) {
            return "https://$bestDomain"
        }

        // 3. If raw already has a valid TLD extension, return https://<clean>
        if (clean.matches(Regex("^[a-zA-Z0-9-]+\\.(com|org|net|in|io|co|ai|edu|gov|app|dev|me)(/.*)?$"))) {
            return "https://$clean"
        }

        // 4. Default fallback: append .com
        return "https://$clean.com"
    }

    /**
     * Extracts one or more target website URLs from a user speech string.
     * Handles:
     * - "open vercel.com and github.com" -> ["https://vercel.com", "https://github.com"]
     * - "open vercel, github and twitter" -> ["https://vercel.com", "https://github.com", "https://x.com"]
     * - "open youtube.com and chatgpt" -> ["https://youtube.com", "https://chatgpt.com"]
     */
    fun extractMultipleUrls(input: String): List<String> {
        val clean = input
            .replace(Regex("(?i)^(hey jarvis|hi jarvis|hello jarvis|ok jarvis|jarvis|service|please)?\\s*(open|launch|go to|browse|in chrome open|on google chrome open)\\s+"), "")
            .replace(Regex("(?i)\\s+(in chrome|in browser|on chrome|on browser|in multiple tabs|tabs|now|please)$"), "")
            .trim()

        // 1. Check if input has space-separated domains (e.g. "github.com vercel.com 21st.dev")
        val spaceTokens = clean.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        val domainTldRegex = Regex("^[a-zA-Z0-9-]+\\.(com|org|net|in|io|co|ai|edu|gov|app|dev|me)(/.*)?$", RegexOption.IGNORE_CASE)
        val domainMatchesCount = spaceTokens.count { domainTldRegex.matches(it) || isKnownWebAlias(it) }

        val rawTokens: List<String> = if (domainMatchesCount > 1) {
            spaceTokens.filter { it.length > 2 && it != "and" && it != "or" && it != "plus" }
        } else {
            // Split by "and", "or", commas, "plus"
            clean.split(Regex("(?i)\\s+(?:and|&|or|plus)\\s+|,\\s*"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

        val urls = mutableListOf<String>()
        for (token in rawTokens) {
            val normalized = normalizeToUrl(token)
            if (!urls.contains(normalized)) {
                urls.add(normalized)
            }
        }

        return urls
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
