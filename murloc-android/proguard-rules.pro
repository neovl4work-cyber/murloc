# Desktop-only UI classes (Swing file picker / system file manager) are referenced from
# platform-guarded code paths that never run on Android.
-dontwarn java.awt.**
-dontwarn javax.swing.**

# kotlin-stdlib внутри apk (нужен процессу плагина) НЕ должна обрабатываться R8:
# оптимизация/слияние классов stdlib ломает делегирование classloader-а хоста
# (IllegalAccessError: MapsKt__MapsKt при загрузке плагина в процессе Murglar).
# Держим stdlib как есть — как в debug-сборке, которая загружается нормально.
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontoptimize
