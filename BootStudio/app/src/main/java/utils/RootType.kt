package utils

enum class RootType(val displayName: String) {
    MAGISK("Magisk"),
    KSU("KernelSU"),
    APATCH("APatch"),
    UNKNOWN("Unknown")
}
