package com.example.ai

enum class EntryFlag {
    SPAM,
    TROLL,
    DUPLICATE,
    PROFANE
}

/**
 * Cheap checks that run off the UI thread after the page is already shown.
 * They never call the network.
 */
object EntryHeuristics {
    private val profanity = listOf(
        "amk", "aq", "orospu", "siktir", "yarrak", "piç", "pic "
    )

    fun flags(text: String, alreadySeen: MutableSet<String>): Set<EntryFlag> {
        val normalized = normalize(text)
        if (normalized.isBlank()) return emptySet()
        val found = linkedSetOf<EntryFlag>()
        if (!alreadySeen.add(normalized)) found.add(EntryFlag.DUPLICATE)
        if (looksLikeSpam(text, normalized)) found.add(EntryFlag.SPAM)
        if (looksLikeTroll(text)) found.add(EntryFlag.TROLL)
        if (profanity.any { token -> normalized.contains(token) }) found.add(EntryFlag.PROFANE)
        return found
    }

    fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[^a-z0-9çğıöşü\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun looksLikeSpam(raw: String, normalized: String): Boolean {
        val links = Regex("https?://", RegexOption.IGNORE_CASE).findAll(raw).count()
        if (links >= 3) return true
        if (links >= 1 && normalized.length < 25) return true
        if (Regex("(.)\\1{7,}").containsMatchIn(normalized)) return true
        return false
    }

    fun onDeviceSaysHide(raw: String): Boolean {
        val compact = raw.lowercase().replace(" ", "")
        if (compact.contains("\"hide\":false")) return false
        return compact.contains("\"hide\":true")
    }

    private fun looksLikeTroll(raw: String): Boolean {
        val letters = raw.count { it.isLetter() }
        if (letters >= 20) {
            val upper = raw.count { it.isUpperCase() }
            if (upper.toFloat() / letters > 0.8f) return true
        }
        if (raw.count { it == '!' || it == '?' } >= 6) return true
        return false
    }
}
