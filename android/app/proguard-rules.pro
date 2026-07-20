# WebView JavaScript 桥接的方法不能被混淆掉
-keepclassmembers class com.voiceassistant.app.** {
    @android.webkit.JavascriptInterface <methods>;
}
