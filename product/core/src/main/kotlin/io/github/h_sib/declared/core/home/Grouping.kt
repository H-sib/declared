package io.github.h_sib.declared.core.home

import io.github.h_sib.declared.core.model.Attention

/**
 * What a finding is about, one level above the finding itself. Findings about the same ability held by different apps are one kind,
 * so the Check list can show them as one row ("Notification access, 4 apps") instead of four rows that read almost the same.
 */
enum class FindingKind(val title: String, val noun: String, val groupable: Boolean) {
    ACCESSIBILITY("Accessibility services", "services", true),
    NOTIFICATIONS("Notification access", "apps", true),
    ADMIN("Device administrators", "apps", true),
    KEYBOARD("Keyboards", "keyboards", true),
    OVERLAY("Drawing over other apps", "apps", true),
    USAGE("Usage access", "apps", true),
    INSTALL("Installing other apps", "apps", true),
    SMS("Text message access", "apps", true),
    NOT_RECOGNIZED("Apps you did not recognize", "apps", true),
    NEW_SIGNER("Apps with a new signing certificate", "apps", true),
    SETTING("Phone settings", "", false),
    NETWORK("Network", "", false),
    INTEGRITY("Integrity", "", false),
}

/** The members of one row of the list. Only a groupable kind with more than one member is a real group; anything else is a single finding. */
data class Group<T>(val kind: FindingKind, val attention: Attention, val members: List<T>) {
    val isGroup: Boolean get() = kind.groupable && members.size > 1
    val key: String get() = kind.name + ":" + attention.name
}

object Grouping {
    private val SETTINGS = setOf("screen-lock", "patch-level", "patch-mismatch", "usb-debugging", "developer-options", "wireless-debugging")
    private val NETWORK = setOf("http-proxy", "wifi-security", "user-cas")

    fun kindOf(findingId: String): FindingKind {
        val head = findingId.substringBefore(":")
        val tail = findingId.substringAfter(":", "").substringBefore(":")
        return when (head) {
            "accessibility" -> FindingKind.ACCESSIBILITY
            "notification-listener" -> FindingKind.NOTIFICATIONS
            "device-admin" -> FindingKind.ADMIN
            "input-method" -> FindingKind.KEYBOARD
            "overlay" -> FindingKind.OVERLAY
            "usage-access" -> FindingKind.USAGE
            "install-unknown-apps" -> FindingKind.INSTALL
            "sms-access" -> FindingKind.SMS
            "review" -> if (tail == "signer-changed") FindingKind.NEW_SIGNER else FindingKind.NOT_RECOGNIZED
            "condition" -> when (tail) {
                in SETTINGS -> FindingKind.SETTING
                in NETWORK -> FindingKind.NETWORK
                else -> FindingKind.INTEGRITY
            }
            else -> FindingKind.SETTING
        }
    }

    /**
     * The list in the same order, with findings of one groupable kind and one attention merged into one row at the place of the first of them.
     * Nothing is dropped: every item is in exactly one group, and a group of one is the item itself.
     */
    fun <T> of(items: List<T>, id: (T) -> String, attention: (T) -> Attention): List<Group<T>> {
        val rows = LinkedHashMap<String, MutableList<T>>()
        val kinds = HashMap<String, FindingKind>()
        for (item in items) {
            val kind = kindOf(id(item))
            val key = if (kind.groupable) kind.name + ":" + attention(item).name else "single:" + id(item)
            rows.getOrPut(key) { ArrayList() }.add(item)
            kinds[key] = kind
        }
        return rows.map { (key, members) -> Group(kinds.getValue(key), attention(members.first()), members) }
    }
}
