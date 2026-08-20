# Hilt / Dagger generated code is reflectively referenced by the runtime.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# Room's generated implementations are looked up by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *

# One reflective platform lookup exists: data/detect/SystemProperties.kt resolves
# android.os.SystemProperties.get(String) by name, because several genuinely useful
# platform facts (Treble, A/B updates, VNDK version) have no public API at all. R8
# does not process the platform classpath, so no keep rule is needed for the target
# itself; what must survive is the caller, which R8 could otherwise inline into a
# form that loses the string literal. Scoped to that one file rather than the blanket
# `-keepclassmembers class android.**` this replaced, which kept nothing R8 was ever
# going to touch and hid how narrow the reflection actually is.
-keep class com.devicelab.data.detect.SystemProperties { *; }

# Compose keeps its own rules through consumer files; nothing extra is needed here.
