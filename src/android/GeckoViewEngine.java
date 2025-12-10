package com.example.cordova.geckoview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.webkit.ValueCallback;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.CordovaResourceApi;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CordovaWebViewEngine;
import org.apache.cordova.ICordovaCookieManager;
import org.apache.cordova.NativeToJsMessageQueue;
import org.apache.cordova.PluginManager;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.GeckoResult;

/**
 * GeckoView-based Cordova WebView engine.
 *
 * Tested against:
 *   - GeckoView 143.x
 *   - Cordova-Android 10+ CordovaWebViewEngine interface
 *
 * NOTE:
 *  - Cookie manager is a no-op. If you need native-side cookie access,
 *    you’ll want to bridge via a WebExtension and JS (document.cookie).
 *  - JS evaluation uses "javascript:" URLs; GeckoView doesn't return
 *    results via evaluateJavascript like Android WebView.
 */
public class GeckoViewEngine implements CordovaWebViewEngine {

    public static final String TAG = "GeckoViewEngine";

    // Cordova-side state
    protected CordovaWebView parentWebView;
    protected CordovaInterface cordova;
    protected CordovaPreferences preferences;
    protected Client cordovaClient;

    // Gecko / View state
    protected FrameLayout containerView;
    protected GeckoView geckoView;
    protected GeckoSession geckoSession;
    protected static GeckoRuntime sRuntime;

    // Current URL for getUrl()
    protected String currentUrl;

    // No-op cookie manager to satisfy Cordova interface
    protected final ICordovaCookieManager cookieManager = new NoopCookieManager();

    // --- Constructors used by Cordova via reflection ---

    public GeckoViewEngine(Context context, CordovaPreferences preferences) {
        this.preferences = preferences;
        createGeckoView(context);
    }

    public GeckoViewEngine(Context context, AttributeSet attrs, CordovaPreferences preferences) {
        this(context, preferences);
    }

    // ---- CordovaWebViewEngine implementation ----

    @Override
    public void init(CordovaWebView parentWebView,
                     CordovaInterface cordova,
                     Client client,
                     CordovaResourceApi resourceApi,
                     PluginManager pluginManager,
                     NativeToJsMessageQueue nativeToJsMessageQueue) {

        this.parentWebView = parentWebView;
        this.cordova = cordova;
        this.cordovaClient = client;

        // Navigation delegate: let Cordova inspect URLs and optionally block them.
        geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<GeckoSession.NavigationDelegate.AllowOrDeny> onLoadRequest(
                    GeckoSession session,
                    GeckoSession.NavigationDelegate.LoadRequest request) {

                String url = request.uri;
                currentUrl = url;

                if (cordovaClient != null) {
                    boolean shouldBlock = cordovaClient.onNavigationAttempt(url);
                    if (shouldBlock) {
                        // Return a DENY result explicitly
                        return GeckoResult.fromValue(
                                GeckoSession.NavigationDelegate.AllowOrDeny.DENY
                        );
                    }
                }

                // null => default (ALLOW)
                return null;
            }

            @Override
            public void onLocationChange(GeckoSession session, String url) {
                currentUrl = url;
                if (cordovaClient != null) {
                    cordovaClient.onPageFinishedLoading(url);
                }
            }
        });
    }

    @Override
    public CordovaWebView getCordovaWebView() {
        return parentWebView;
    }

    @Override
    public ICordovaCookieManager getCookieManager() {
        return cookieManager;
    }

    @Override
    public View getView() {
        return containerView;
    }

    @Override
    public void loadUrl(String url, boolean clearNavigationStack) {
        if (clearNavigationStack) {
            clearHistory();
        }
        currentUrl = url;
        if (geckoSession != null) {
            geckoSession.loadUri(url);
        }
    }

    @Override
    public void stopLoading() {
        if (geckoSession != null) {
            geckoSession.stop();
        }
    }

    @Override
    public String getUrl() {
        return currentUrl;
    }

    @Override
    public void clearCache() {
        clearCache(true);
    }

    @Override
    public void clearCache(boolean b) {
        if (geckoSession != null) {
            // Simple cache-bypass reload
            geckoSession.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE);
        }
    }

    @Override
    public void clearHistory() {
        recreateSession();
    }

    @Override
    public boolean canGoBack() {
        return geckoSession != null && geckoSession.canGoBack();
    }

    @Override
    public boolean goBack() {
        if (geckoSession != null && geckoSession.canGoBack()) {
            geckoSession.goBack();
            return true;
        }
        return false;
    }

    @Override
    public void setPaused(boolean value) {
        if (geckoSession == null) return;
        // When paused, mark session as inactive
        geckoSession.setActive(!value);
    }

    @Override
    public void destroy() {
        if (geckoSession != null) {
            geckoSession.close();
            geckoSession = null;
        }
        if (geckoView != null && containerView != null) {
            containerView.removeView(geckoView);
            geckoView = null;
        }
    }

    @Override
    public void evaluateJavascript(String js, ValueCallback<String> callback) {
        if (geckoSession != null) {
            String uri = "javascript:" + js;
            geckoSession.loadUri(uri);
        }
        if (callback != null) {
            // GeckoView doesn't provide a direct JS result here
            callback.onReceiveValue(null);
        }
    }

    // ---- Internal helpers ----

    private void createGeckoView(Context context) {
        containerView = new FrameLayout(context);
        geckoView = new GeckoView(context);

        if (sRuntime == null) {
            // GeckoRuntime should be a process-wide singleton
            sRuntime = GeckoRuntime.create(context.getApplicationContext());
        }

        geckoSession = new GeckoSession();
        geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {});
        geckoSession.open(sRuntime);
        geckoView.setSession(geckoSession);

        containerView.addView(
                geckoView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );
    }

    private void recreateSession() {
        if (geckoSession != null) {
            geckoSession.close();
        }
        geckoSession = new GeckoSession();
        geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {});
        geckoSession.open(sRuntime);
        geckoView.setSession(geckoSession);

        // Reattach Cordova navigation delegate if already initialized
        if (cordovaClient != null) {
            init(parentWebView, cordova, cordovaClient, null, null, null);
        }
    }

    // ---- No-op cookie manager implementation ----

    private static class NoopCookieManager implements ICordovaCookieManager {
        @Override
        public void setCookiesEnabled(boolean accept) { }
        @Override
        public void setCookie(String url, String value) { }
        @Override
        public String getCookie(String url) { return null; }
        @Override
        public void clearCookies() { }
        @Override
        public void flush() { }
    }
}
