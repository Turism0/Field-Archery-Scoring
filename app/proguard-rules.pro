# R8 rules for the release build (3.2.13, Pete: Play Console "DEX code optimisation"). The libraries (Room, WorkManager,
# Compose, Navigation, Google Play services, ML Kit) bring their own rules; these cover what they don't.

# Crash reports: keep line numbers (the bundle's mapping file turns the obfuscated names back into real ones in Play Console).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# osmdroid (the map) ships no R8 rules. It loads its tile sources and some classes by name, so it's kept as it is.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# 3.2.26 (Pete: "Couldn't open the scanner (NullPointerException ... getClass() on a null object reference)"): ML Kit (the
# QR scanner) finds its parts through ComponentRegistrar classes named in the manifest and makes them by name, with their
# empty constructors. R8 removed those constructors (usage.txt: CommonComponentRegistrar / VisionCommonRegistrar <init>()),
# so the scanner client was never set up. Keep them.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
-keep class com.google.mlkit.common.internal.MlKitComponentDiscoveryService { *; }
