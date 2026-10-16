# R8 keep rules for the standalone car-test build.
#
# Most of the app is reachable from the manifest and the Kotlin entry points, so R8 can rename
# and strip it safely. The rules below cover the pieces R8 cannot see: shell entry points launched
# by name through `app_process`, the classes they are named after, JNI methods, and the few
# reflection targets the head-unit probes rely on.

# --- Shell / app_process entry points ---------------------------------------
# These are started as `CLASSPATH=<apk> app_process /system/bin <class>` from the head unit's own
# adb shell, so nothing in the APK references their names at runtime. Keep the class names and the
# main methods exactly.
-keep class com.shilapi.xcertplay.transport.IphoneUsbGrantTool { public static void main(java.lang.String[]); }
-keep class com.shilapi.xcertplay.mfi.LocalMfiProbe { public static void main(java.lang.String[]); }
-keep class com.shilapi.xcertplay.network.CarHotspotStartMain { public static void main(java.lang.String[]); }
-keep class com.shilapi.xcertplay.network.WifiScanPauseMain { public static void main(java.lang.String[]); }
-keep class com.shilapi.xcertplay.network.HotspotJoinRepairMain { public static void main(java.lang.String[]); }

# --- JNI ---------------------------------------------------------------------
# Native symbols are bound by their Java names (Java_com_shilapi_xcertplay_...), so the declaring
# classes and their native methods must keep their names.
-keep class com.shilapi.xcertplay.transport.LinuxI2cNative { *; }
-keep class com.shilapi.xcertplay.network.LegacyHotspotNative { *; }
-keep class com.shilapi.xcertplay.media.SpeexEchoCanceller { *; }
-keepclasseswithmembernames class * { native <methods>; }

# --- Manifest components -----------------------------------------------------
# Declared in AndroidManifest.xml, but keep them robust against renaming by any tool.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
-keep public class * extends android.appwidget.AppWidgetProvider

# --- Widget / RemoteViews ----------------------------------------------------
# RemoteViews resolves layout ids and methods reflectively on the host process.
-keep class com.shilapi.xcertplay.NavigationWidget { *; }
-keepclassmembers class * extends android.appwidget.AppWidgetProvider { public void onUpdate(...); }

# --- Reflection targets ------------------------------------------------------
# Head-unit probes look these up by name on the vendor SDK.
-keep class com.byd.** { *; }
-keep class android.hardware.bydauto.** { *; }

# Kotlin metadata is unused at runtime in this build.
-dontwarn kotlin.Metadata
