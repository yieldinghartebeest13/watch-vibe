# ProGuard/R8 rules for vibe-control
# Keep Wearable Data Layer callbacks (reflectively invoked by Play Services)
-keep class com.example.vibecontrol.** { *; }
