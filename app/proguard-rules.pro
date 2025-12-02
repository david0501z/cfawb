# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontobfuscate

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
}

# Kotlin Coroutine
# Allow R8 to optimize away the FastServiceLoader.
# Together with ServiceLoader optimization in R8
# this results in direct instantiation when loading Dispatchers.Main
-assumenosideeffects class kotlinx.coroutines.internal.MainDispatcherLoader {
    boolean FAST_SERVICE_LOADER_ENABLED return false;
}

-assumenosideeffects class kotlinx.coroutines.internal.FastServiceLoaderKt {
    boolean ANDROID_DETECTED return true;
}

-keep class kotlinx.coroutines.android.AndroidDispatcherFactory {*;}

# Disable support for "Missing Main Dispatcher", since we always have Android main dispatcher
-assumenosideeffects class kotlinx.coroutines.internal.MainDispatchersKt {
    boolean SUPPORT_MISSING return false;
}

# Statically turn off all debugging facilities and assertions
-assumenosideeffects class kotlinx.coroutines.DebugKt {
    boolean getASSERTIONS_ENABLED() return false;
    boolean getDEBUG() return false;
    boolean getRECOVER_STACK_TRACES() return false;
}

# Keep WebView proxy related classes and methods
-keep class androidx.webkit.ProxyController { *; }
-keep class androidx.webkit.ProxyConfig { *; }
-keep class androidx.webkit.WebViewFeature { *; }

# Keep our BrowserActivity and proxy setup
-keep class com.github.kr328.clash.BrowserActivity { *; }
-keep class com.github.kr328.clash.BrowserActivity$* { *; }

# Keep all WebView related classes and methods
-keep class android.webkit.WebView { *; }
-keep class android.webkit.WebViewClient { *; }
-keep class android.webkit.WebChromeClient { *; }
-keep class android.webkit.WebSettings { *; }
-keep class android.webkit.ValueCallback { *; }
-keep class android.webkit.WebResourceRequest { *; }
-keep class android.webkit.WebResourceError { *; }
-keep class android.webkit.WebResourceResponse { *; }
-keep class android.webkit.ConsoleMessage { *; }
-keep class android.webkit.GeolocationPermissions { *; }
-keep class android.webkit.JsResult { *; }
-keep class android.webkit.JsPromptResult { *; }
-keep class android.webkit.JsConfirmResult { *; }
-keep class android.webkit.JsAlert { *; }
-keep class android.webkit.WebStorage { *; }
-keep class android.webkit.WebViewDatabase { *; }
-keep class android.webkit.CookieManager { *; }
-keep class android.webkit.CookieSyncManager { *; }
-keep class android.webkit.HttpAuthHandler { *; }
-keep class android.webkit.SslErrorHandler { *; }
-keep class android.webkit.ClientCertRequest { *; }
-keep class android.webkit.WebBackForwardList { *; }
-keep class android.webkit.WebHistoryItem { *; }
-keep class android.webkit.DownloadListener { *; }
-keep class android.webkit.WebIconDatabase { *; }
-keep class android.webkit.WebSettingsClassic { *; }
-keep class android.webkit.WebViewFragment { *; }
-keep class android.webkit.WebViewProvider { *; }
-keep class android.webkit.WebViewDelegate { *; }

# Keep JavaScript interface for blob downloads
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public void *(android.webkit.WebView, java.lang.String);
    public boolean *(android.webkit.WebView, android.webkit.WebResourceRequest);
    public void *(android.webkit.WebView, android.webkit.WebResourceRequest, android.webkit.WebResourceError);
    public void *(android.webkit.WebView, android.webkit.WebResourceRequest, android.webkit.WebResourceResponse);
}

-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, int);
    public void *(android.webkit.WebView, java.lang.String);
    public boolean *(android.webkit.WebView, java.lang.String, android.webkit.ValueCallback, android.webkit.WebChromeClient.FileChooserParams);
    public void *(android.webkit.WebView, android.webkit.ConsoleMessage);
    public void *(android.webkit.WebView, java.lang.String, java.lang.String, android.webkit.JsResult);
    public void *(android.webkit.WebView, java.lang.String, java.lang.String, android.webkit.JsPromptResult);
    public void *(android.webkit.WebView, android.webkit.SslErrorHandler, android.net.http.SslError);
    public void *(android.webkit.WebView, android.webkit.ClientCertRequest);
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep all reflection calls and native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep all enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep Serializable implementations
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep network related classes
-keep class okhttp3.** { *; }
-keep class retrofit2.** { *; }

# Additional rules for WebView proxy functionality
-keep class android.net.Proxy { *; }
-keep class android.net.ProxyInfo { *; }
-keep class android.net.ConnectivityManager { *; }
-keep class android.net.Network { *; }
-keep class android.net.NetworkCapabilities { *; }
-keep class android.net.NetworkRequest { *; }

# Keep all classes that might be accessed via reflection from WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface *;
}

# Keep all methods that might be called from JavaScript
-keepclassmembers class * {
    public *;
}

# Keep all fields that might be accessed from JavaScript
-keepclassmembers class * {
    private *;
    protected *;
    public *;
}