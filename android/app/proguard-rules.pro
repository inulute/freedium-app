# Keep WebView
-keepclassmembers class * extends android.webkit.WebView {
   public *;
}

-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
}

# Keep OkHttp (for DNS over HTTPS)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okhttp3.** { *; }

# Optimize
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Keep methods called from page JavaScript (WebViewActivity's download bridge)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
