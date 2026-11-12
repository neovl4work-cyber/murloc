# Desktop-only UI classes (Swing file picker / system file manager) are referenced from
# platform-guarded code paths that never run on Android.
-dontwarn java.awt.**
-dontwarn javax.swing.**
