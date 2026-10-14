package io.github.h_sib.declared.core.model

/** How much weight the attack-path rules put on a weakness (THREAT_MODEL.md section 6). Both profiles run the same engine. */
enum class Profile { GENERAL, HIGH_RISK }

/** What the person says about their own life, where "not sure" is a real answer: it keeps the paths that need it open. */
enum class Told { YES, NO, UNSURE }

/**
 * What the person told the app about things no sensor on the phone can read (STAGE2_DESIGN.md section 5). Stored with the
 * baseline, encrypted, and never sent anywhere. The defaults say nothing: every question unanswered, the general profile.
 */
data class Declarations(
    val smsCodes: Told = Told.UNSURE,
    val publicWifi: Told = Told.UNSURE,
    val profile: Profile = Profile.GENERAL,
)
