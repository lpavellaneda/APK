# Add project specific ProGuard rules here.
-keep class com.jobai.hunter.bridge.** { *; }
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
