# R8 rules for the release build (3.2.13, Pete: Play Console "DEX code optimisation"). The libraries (Room, WorkManager,
# Compose, Navigation, Google Play services, ML Kit) bring their own rules; these cover what they don't.

# Crash reports: keep line numbers (the bundle's mapping file turns the obfuscated names back into real ones in Play Console).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# osmdroid (the map) ships no R8 rules. It loads its tile sources and some classes by name, so it's kept as it is.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
