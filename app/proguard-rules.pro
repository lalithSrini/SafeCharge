# Keep Jakarta Mail / Angus Mail classes intact (uses reflection internally)
-keep class org.eclipse.angus.mail.** { *; }
-keep class jakarta.mail.**            { *; }
-keep class com.sun.mail.**            { *; }
-dontwarn org.eclipse.angus.mail.**
-dontwarn jakarta.mail.**
-dontwarn com.sun.mail.**

# Keep our own classes
-keep class com.example.safecharge.** { *; }