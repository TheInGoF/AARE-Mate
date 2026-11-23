# Mate release build with R8 (code optimization; resources are not shrunk).

# Shizuku starts these by class name in its own process and calls their constructors by reflection.
-keep class net.prototux.aare.audio.ShellAudioService { <init>(...); *; }
-keep class net.prototux.aare.launch.LaunchHostService { <init>(...); *; }

# Started as root by name with app_process (su), main() is called by nobody in the app: keep it (System I-105b).
-keep class net.prototux.aare.launch.LaunchMain { public static void main(java.lang.String[]); }
-keep class net.prototux.aare.audio.SuAudioMain { public static void main(java.lang.String[]); }

# Hidden framework members are reached by reflection; our own classes are not, but keep line numbers
# so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Only optimize: names stay readable in logs and the diagnostic log (no obfuscation).
-dontobfuscate

# protobuf-lite reads the generated messages' fields by name (Android Auto protocol).
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# sherpa-onnx (speech recognition): its native code reads the config classes' fields by name over JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# sherpa-onnx calls the speech callback by name and signature over JNI (LocalTts.Feed)
# The same for every callback of the voice (LocalTts$Stop: Mate's voice for other apps and the test sentences - without
# this rule its invoke(float[]) was removed and the native side took the app down, 18.11.2026)
-keep class net.prototux.aare.speech.LocalTts$Stop { public java.lang.Integer invoke(float[]); }
-keepclassmembers class net.prototux.aare.speech.** { public java.lang.Integer invoke(float[]); }
# OwnBluetooth asks the system for this device's Bluetooth address through Shizuku's own way to run a command; it is
# reached by name, so it must stay
-keepclassmembers class rikka.shizuku.Shizuku { *** newProcess(...); }

# System L-063: Mate's own wireless-debugging connection - the adb library, its SPAKE2 pairing (native, found by name
# through JNI), Conscrypt (looked up by name for TLS 1.3) and Bouncy Castle (providers and algorithms looked up by name) kept as they are
-keep class io.github.muntashirakon.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn io.github.muntashirakon.**
