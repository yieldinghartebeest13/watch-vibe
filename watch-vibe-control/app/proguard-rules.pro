# ProGuard/R8 rules for watch-vibe-control
# Keep Wearable Data Layer callbacks (reflectively invoked by Play Services)
-keep class com.yieldinghartebeest13.watchvibe.** { *; }
