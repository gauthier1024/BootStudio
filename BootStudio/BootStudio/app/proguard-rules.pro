# ProGuard rules for the BootStudio application
# Add any specific rules for your application below

# Keep the MainActivity class
-keep class com.bootstudio.MainActivity { *; }

# Keep all classes in the utils package
-keep class utils.** { *; }

# Keep all classes in the com.bootstudio.ui.screens package
-keep class com.bootstudio.ui.screens.** { *; }

# Keep all classes in the com.bootstudio.ui.theme package
-keep class com.bootstudio.ui.theme.** { *; }

# Keep all public methods and fields
-keepclassmembers public class * {
    public *;
}

# Add any additional rules as needed for libraries or specific classes