# Mate release build with R8 (code optimization; resources are not shrunk).

# Shizuku starts these by class name in its own process and calls their constructors by reflection.
-keep class net.prototux.aare.audio.ShellAudioService { <init>(...); *; }
-keep class net.prototux.aare.launch.LaunchHostService { <init>(...); *; }

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
