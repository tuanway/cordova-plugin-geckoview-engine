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

/**
 * Minimal, stable GeckoView-based Cordova WebView engine.
 *
 * Fully compatible with:
 *   - GeckoView 143.x
 *   - Cordova-Android 10+ WebViewEngine interface
 *
 * This implementation intentionally avoids APIs not exposed by GeckoView 143,
 * such as history navigation, load interception with AllowOrDeny, etc.
 */
public class GeckoViewEngine implements CordovaWebViewEngine {

    // Cordova state
    protected CordovaWebView parentWebView;
    protected CordovaInterface cordova;
    protected CordovaPreferences preferences;
    protected Client cordovaClient;

    // Gecko state
    protected FrameLayout containerView;
    protected GeckoView geckoView;
    protected GeckoSession geckoSession;
    protected static GeckoRuntime sRuntime;

    // Track current URL for Cordova's getUrl()
    protected String currentUrl;

    // No-op cookie manager (Cordova requires an instance)
    protected final ICordovaCookieManager cookieManager = new NoopCookieManager();

    // Constructors (Cordova instantiates through reflection)
    public GeckoViewEngine(Context context, CordovaPreferences preferences) {
        this.preferences = preferences;
        createGeckoView(context);
    }

    public GeckoViewEngine(Context context, AttributeSet attrs, CordovaPreferences preferences) {
        this(context, preferences);
    }

    // -------------------------------------------------------------------------
    // CordovaWebViewEngine implementation
    // -------------------------------------------------------------------------

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

        // Only track location changes — safe across GeckoView versions
        geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
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
        // GeckoSession doesn't expose deep cache APIs, so we approximate
        if (geckoSession != null && currentUrl != null) {
            geckoSession.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE);
        }
    }

    @Override
    public void clearHistory() {
        recreateSession();
    }

    // NO NATIVE HISTORY AVAILABLE → Stub implementations
    @Override
    public boolean canGoBack() {
        return false; // Cordova will treat this as "cannot go back"
    }

    @Override
    public boolean goBack() {
        return false; // No native back navigation available
    }

    @Override
    public void setPaused(boolean value) {
        if (geckoSession != null) {
            geckoSession.setActive(!value);
        }
    }

    @Override
    public void destroy() {
        if (geckoSession != null) {
            geckoSession.close();
        }
        if (geckoView != null && containerView != null) {
            containerView.removeView(geckoView);
        }
    }

    @Override
    public void evaluateJavascript(String js, ValueCallback<String> callback) {
        if (geckoSession != null) {
            geckoSession.loadUri("javascript:" + js);
        }
        if (callback != null) {
            callback.onReceiveValue(null); // GeckoView doesn't return JS results
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void createGeckoView(Context context) {
        containerView = new FrameLayout(context);
        geckoView = new GeckoView(context);

        if (sRuntime == null) {
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
                        FrameLayout.LayoutParams.MATCH_PARENT)
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

        // Re-wire Cordova delegates if already initialized
        if (cordovaClient != null) {
            init(parentWebView, cordova, cordovaClient, null, null, null);
        }
    }

    // -------------------------------------------------------------------------
    // No-op cookie manager
    // -------------------------------------------------------------------------

    private static class NoopCookieManager implements ICordovaCookieManager {
        @Override public void setCookiesEnabled(boolean accept) { }
        @Override public void setCookie(String url, String value) { }
        @Override public String getCookie(String url) { return null; }
        @Override public void clearCookies() { }
        @Override public void flush() { }
    }
}
