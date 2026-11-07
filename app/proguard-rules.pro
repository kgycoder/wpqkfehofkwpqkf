# Debug builds are not minified. Keep the bridge methods if release minify is enabled later.
-keepclassmembers class com.arena.agent.arena.ArenaJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
