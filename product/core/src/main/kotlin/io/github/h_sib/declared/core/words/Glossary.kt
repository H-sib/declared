package io.github.h_sib.declared.core.words

/** A word the app uses that most people do not, with what it means in a few plain words. */
data class Term(val name: String, val meaning: String, val patterns: List<String>)

/** One place in a text where a [Term] was found. */
data class TermMatch(val start: Int, val end: Int, val term: Term)

/**
 * The plain-words decoder. Every detailed text in the app is searched for these words; the ones found are marked and explained next to it.
 * Meanings are one short sentence each and say what the thing does, not how it is built.
 */
object Glossary {
    val terms: List<Term> = listOf(
        Term("Accessibility service", "An app that can read your screen and tap for you.", listOf("accessibility services", "accessibility service", "accessibility")),
        Term("Notification listener", "An app that can read every notification you get.", listOf("notification listeners", "notification listener", "notification access")),
        Term("Device administrator", "An app that can set rules for the whole phone, like locking it.", listOf("device administrators", "device administrator", "device admin")),
        Term("Overlay", "Something drawn on top of other apps. It can hide real buttons.", listOf("overlays", "overlay", "drawing over other apps", "draw over other apps")),
        Term("Usage access", "Lets an app see which apps you open and when.", listOf("usage access")),
        Term("Install unknown apps", "Permission to install apps from outside an app store.", listOf("install unknown apps", "installing apps")),
        Term("Sideloaded", "Installed from somewhere other than an app store.", listOf("sideloaded", "sideload")),
        Term("Certificate authority", "A name your phone trusts to decide which sites and apps are real.", listOf("certificate authorities", "certificate authority", "certificate authority(ies)")),
        Term("Certificate", "A digital ID that shows who made an app or a site.", listOf("certificates", "certificate")),
        Term("Proxy", "A middleman server that your web traffic goes through.", listOf("proxy")),
        Term("Private DNS", "Hides which sites you look up from the network you are on.", listOf("private dns")),
        Term("USB debugging", "A builder mode that lets a computer control the phone over a cable.", listOf("usb debugging")),
        Term("Wireless debugging", "The same builder mode, but over Wi-Fi.", listOf("wireless debugging")),
        Term("Developer options", "Hidden settings made for people who build apps.", listOf("developer options")),
        Term("Security patch", "The date of the phone's latest security fixes.", listOf("security patch level", "security patch", "patch level")),
        Term("Tripwire", "A tiny alarm key. Android destroys it when your lock or fingerprints change.", listOf("tripwires", "tripwire")),
        Term("Keystore", "Android's locked safe for secret keys.", listOf("keystore")),
        Term("Attestation", "A signed receipt proving a key lives in real hardware.", listOf("attestation")),
        Term("Protected hardware", "A separate secure chip. Even a hacked system cannot pull keys out of it.", listOf("protected hardware", "strongbox", "secure hardware")),
        Term("Hash", "A fingerprint of data. Change one letter and the whole thing changes.", listOf("hashes", "hash")),
        Term("Signature", "A digital stamp that proves who wrote something and that it was not edited.", listOf("signatures", "signature", "signed with", "signed by", "signing key")),
        Term("Baseline", "The first snapshot that later checks are compared to.", listOf("baseline")),
        Term("Play Protect", "Google's built-in scanner for harmful apps.", listOf("play protect")),
        Term("Work profile", "A separate space for work apps that an employer controls.", listOf("work profile")),
        Term("Input method", "Your keyboard app. It sees what you type.", listOf("input methods", "input method")),
        Term("Encrypted", "Scrambled so only the two ends can read it.", listOf("encrypted", "encryption")),
        Term("Root", "Full control over the phone's system, which normal phones block.", listOf("rooted", "root access")),
        Term("Emulator", "A pretend phone that runs on a computer.", listOf("emulator")),
        Term("Spyware", "An app that secretly watches the person who owns the phone.", listOf("stalkerware", "spyware", "spying apps", "spying app")),
        Term("Provenance", "Where a reading came from.", listOf("provenance")),
    )

    private val regex: Regex = Regex(
        "(?<![\\p{L}\\p{N}])(" + terms.flatMap { it.patterns }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    private val byPattern: Map<String, Term> = terms.flatMap { t -> t.patterns.map { it.lowercase() to t } }.toMap()

    /** Every term found in [text], left to right, never overlapping. */
    fun matches(text: String): List<TermMatch> =
        regex.findAll(text).mapNotNull { m -> byPattern[m.value.lowercase()]?.let { TermMatch(m.range.first, m.range.last + 1, it) } }.toList()

    /** The distinct terms found across [texts], in the order they first appear. */
    fun termsIn(texts: List<String>): List<Term> = texts.flatMap { matches(it) }.map { it.term }.distinct()
}

/** Splits a paragraph into its sentences, so that each can be shown on a line of its own. */
object Bits {
    private val abbreviations = listOf("e.g.", "i.e.", "etc.", "vs.", "approx.")
    private val boundary = Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"(])")

    fun split(text: String): List<String> {
        val raw = boundary.split(text.trim())
        val out = ArrayList<String>()
        for (piece in raw) {
            if (piece.isBlank()) continue
            val previous = out.lastOrNull()
            if (previous != null && abbreviations.any { previous.endsWith(it, ignoreCase = true) }) out[out.lastIndex] = "$previous $piece" else out.add(piece)
        }
        return out
    }
}
