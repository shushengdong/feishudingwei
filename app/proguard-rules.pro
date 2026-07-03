# Keep Xposed module entry point
-keep class com.research.location.hook.HookEntry { *; }

# Keep all hook classes (reflection usage + Xposed API)
-keep class com.research.location.hook.hooks.* { *; }
-keep class com.research.location.hook.data.* { *; }
-keep class com.research.location.hook.Config { *; }
-keep class com.research.location.hook.ConfigLoader { *; }
-keep class com.research.location.hook.CoordinatesEngine { *; }
-keep class com.research.location.hook.util.* { *; }

# Keep Gson serializable config classes
-keep class com.research.location.hook.MockConfig { *; }
-keep class com.research.location.hook.LocationConfig { *; }
-keep class com.research.location.hook.BehaviorConfig { *; }
-keep class com.research.location.hook.WifiConfig { *; }
-keep class com.research.location.hook.CellConfig { *; }
-keep class com.research.location.hook.GnssConfig { *; }
-keep class com.research.location.hook.SensorConfig { *; }
-keep class com.research.location.hook.NetworkConfig { *; }
-keep class com.research.location.hook.SystemConfig { *; }
-keep class com.research.location.hook.SelfHideConfig { *; }
-keep class com.research.location.hook.PackageOverride { *; }
-keep class com.research.location.hook.ConfigLoader$ResolvedConfig { *; }

# Keep model classes
-keep class com.research.location.model.* { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }

# Xposed
-dontwarn de.robv.android.xposed.**
