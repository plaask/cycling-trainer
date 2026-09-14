# R8 / ProGuard rules for Cycling Trainer (release build).
#
# The release build runs R8 (isMinifyEnabled + isShrinkResources). Almost every
# library here — Compose UI/runtime, AndroidX core/lifecycle/activity,
# kotlinx-coroutines — ships its own consumer rules inside its AAR, so the main
# job of this file is to guard the handful of places where this app reaches code
# that R8 cannot see being used.
#
# Anything added here should say WHY, so a later reader can tell whether it is
# still needed. When in doubt, prefer a targeted rule over a whole-package keep:
# keeping everything quietly undoes the shrinking while looking like it works.

# ---------------------------------------------------------------------------
# kotlinx-coroutines: the Android main dispatcher is discovered at runtime via
# ServiceLoader (META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory),
# not by a direct reference. Shinier versions ship consumer rules for this, but
# a stripped factory would only surface at runtime as "Module with the Main
# dispatcher had failed to initialize", so keep the implementations.
# ---------------------------------------------------------------------------
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# ---------------------------------------------------------------------------
# Compose: the Compose compiler emits classes looked up reflectively. The
# compose-runtime AAR ships the rules for its own machinery, but `@Preview`
# wrappers and composable lambdas are reached through generated code that R8
# can otherwise decide is unused.
# ---------------------------------------------------------------------------
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}

# ---------------------------------------------------------------------------
# Keep the stack-trace metadata. Without this, obfuscated crashes inside this
# app report meaningless frame names, and the app has no crash reporting to
# de-obfuscate with.
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# This app parses .zwo XML with XmlPullParser. The default Android rules already
# cover the platform parser, but the pull-parser factory is loaded by class name
# on some Android versions.
# ---------------------------------------------------------------------------
-keep class org.xmlpull.** { *; }
-dontwarn org.xmlpull.**
