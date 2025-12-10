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

public class GeckoViewEngine implements CordovaWebViewEngine {

    public static final String TAG = "GeckoViewEngine";

    protected CordovaWebView parentWebView;
    protected CordovaInterface cordova;
    protected CordovaPreferences preferences;
    protected Client cordovaClient;

    protected FrameLayout containerView;
    protected GeckoView geckoView;
    protected GeckoSession geckoSession;
    protected static GeckoRuntime sRuntime;

    protected String currentUrl;

    protected final ICordovaCookieManager cookieManager = new NoopCookieManager();

    public GeckoViewEngine(Context context, CordovaPreferences preferences) {
        this.preferences = preferences;
        createGeckoView(context);
    }

    public GeckoViewEngine(Context context, AttributeSet attrs, CordovaPreferences preferences) {
        this(context, preferences);
    }

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

        geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,
                                                          LoadRequest request) {
                String url = request.uri;
                currentUrl = url;

                if (cordovaClient != null) {
                    boolean shouldBlock = cordovaClient.onNavigationAttempt(url);
                    if (shouldBlock) {
                        return GeckoResult.deny();
                    }
                }
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
        geckoSession.loadUri(url);
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
        geckoSession.setActive(!value);
    }

    @Override
    public void destroy() {
        if (geckoSession != null) {
            geckoSession.close();
            geckoSession = null;
        }
        if (geckoView != null) {
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
            callback.onReceiveValue(null);
        }
    }

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

        if (cordovaClient != null) {
            init(parentWebView, cordova, cordovaClient, null, null, null);
        }
    }

    private static class NoopCookieManager implements ICordovaCookieManager {
        @Override
        public void setCookiesEnabled(boolean accept) {
        }

        @Override
        public void setCookie(String url, String value) {
        }

        @Override
        public String getCookie(String url) {
            return null;
        }

        @Override
        public void clearCookies() {
        }

        @Override
        public void flush() {
        }
    }
}
