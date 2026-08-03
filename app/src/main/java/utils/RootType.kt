package utils

enum class RootType(val displayName: String) {
    MAGISK("Magisk"),
    KSU("KernelSU"),
    KSU_NEXT("KernelSU Next"),
    APATCH("APatch"),
    UNKNOWN("Unknown")
}
