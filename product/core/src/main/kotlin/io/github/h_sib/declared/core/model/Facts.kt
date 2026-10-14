package io.github.h_sib.declared.core.model

/** Where a fact is shown: a setting of this phone, or the network it is connected to right now. */
enum class FactGroup(val heading: String) {
    DEVICE("This phone's settings"),
    NETWORK("The network you are on"),
    INTEGRITY("Hardware check of this phone's software (made on this phone, not by an independent server)"),
}

/** The conditions that are shown as plain rows whether or not they are a problem. Order is display order. */
enum class FactKind(val group: FactGroup, val label: String) {
    PATCH_LEVEL(FactGroup.DEVICE, "Last security update"),
    SCREEN_LOCK(FactGroup.DEVICE, "Screen lock"),
    USB_DEBUGGING(FactGroup.DEVICE, "USB debugging"),
    WIRELESS_DEBUGGING(FactGroup.DEVICE, "Wireless debugging"),
    DEVELOPER_OPTIONS(FactGroup.DEVICE, "Developer options"),
    ADVANCED_PROTECTION(FactGroup.DEVICE, "Advanced Protection"),
    CONNECTION(FactGroup.NETWORK, "Connection"),
    VPN(FactGroup.NETWORK, "VPN"),
    WIFI_SECURITY(FactGroup.NETWORK, "Wi-Fi security"),
    PRIVATE_DNS(FactGroup.NETWORK, "Private DNS"),
    HTTP_PROXY(FactGroup.NETWORK, "Web proxy"),
    USER_CAS(FactGroup.NETWORK, "Added certificate authorities"),
    KEY_ATTESTATION(FactGroup.INTEGRITY, "Hardware key check"),
    BOOT_STATE(FactGroup.INTEGRITY, "Boot state"),
    BOOTLOADER(FactGroup.INTEGRITY, "Bootloader"),
    ATTESTED_PATCH(FactGroup.INTEGRITY, "Security update the hardware attests"),
}

/**
 * One row of the "this phone" and "network" panels. [summary] is the sentence shown. An unreadable fact keeps its row
 * with the reason ([availability]), never an empty or green one (DESIGN.md principle 2).
 */
data class DeviceFact(
    val kind: FactKind,
    val summary: String,
    val availability: Availability,
    val observation: Observation?,
)
