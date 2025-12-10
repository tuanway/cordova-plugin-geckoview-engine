package com.cordova.geckoview;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.webkit.ValueCallback;

import org.apache.cordova.CordovaBridge;
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
import org.mozilla.geckoview.WebExtension;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * GeckoView-based Cordova WebView engine with a WebExtension-backed bridge.
 *
 * NOTE: This is experimental and may require adjustments depending on your
 * Cordova / GeckoView versions.
 */
public class GeckoViewEngine implements CordovaWebViewEngine {

    private static final String TAG = "GeckoViewEngine";
    private static final String NATIVE_APP_ID = "cordovaNative";

    // Cordova state
    protected CordovaWebView parentWebView;
    protected CordovaInterface cordova;
    protected CordovaPreferences preferences;
    protected Client cordovaClient;

    protected PluginManager pluginManager;
    protected NativeToJsMessageQueue jsMessageQueue;
    protected CordovaBridge cordovaBridge;

    // Gecko state
    protected FrameLayout containerView;
    protected GeckoView geckoView;
    protected GeckoSession geckoSession;
    protected static GeckoRuntime sRuntime;

    // Track current URL for Cordova's getUrl()
    protected String currentUrl;

    // No-op cookie manager (Cordova requires an instance)
    protected final ICordovaCookieManager cookieManager = new NoopCookieManager();

    private WebExtension cordovaExtension;

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

        // Bridge objects similar to SystemWebViewEngine
        if (pluginManager != null) {
            this.pluginManager = pluginManager;
        } else {
            this.pluginManager = new PluginManager(parentWebView, cordova, null);
        }

        if (nativeToJsMessageQueue != null) {
            this.jsMessageQueue = nativeToJsMessageQueue;
        } else {
            this.jsMessageQueue = new NativeToJsMessageQueue();
        }

        this.cordovaBridge = new CordovaBridge(this.pluginManager, this.jsMessageQueue);

        // Track location changes; signature matches modern GeckoView
        geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession session,
                                         String url,
                                         List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
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
        // Approximate cache clearing by forcing a cache-bypass reload
        if (geckoSession != null && currentUrl != null) {
            geckoSession.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE);
        }
    }

    @Override
    public void clearHistory() {
        recreateSession();
    }

    // JS-based back navigation
    @Override
    public boolean canGoBack() {
        // We don't see native history state; let Cordova attempt JS-level back.
        return true;
    }

    @Override
    public boolean goBack() {
        String js = "if (window.history && window.history.length > 1){" +
                    "window.history.back();" +
                    "}";
        evaluateJavascript(js, null);
        return true;
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
            geckoSession.loadUri("javascript:" + js);
        }
        if (callback != null) {
            // GeckoView doesn't return JS results through this API
            callback.onReceiveValue(null);
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

        installCordovaExtension();
    }

    private void recreateSession() {
        if (geckoSession != null) {
            geckoSession.close();
        }

        geckoSession = new GeckoSession();
        geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {});
        geckoSession.open(sRuntime);
        geckoView.setSession(geckoSession);

        // Re-wire extension for the new session
        installCordovaExtension();

        // Re-wire Cordova delegates if already initialized
        if (cordovaClient != null) {
            init(parentWebView, cordova, cordovaClient, null, null, null);
        }
    }

    private void installCordovaExtension() {
        if (sRuntime == null || geckoSession == null) {
            return;
        }

        sRuntime.getWebExtensionController()
                .ensureBuiltIn("resource://android/assets/cordova-gecko/", "cordova@example.com")
                .accept(
                        extension -> {
                            Log.i(TAG, "Cordova WebExtension installed: " + extension);
                            cordovaExtension = extension;

                            WebExtension.MessageDelegate delegate = new WebExtension.MessageDelegate() {
                                @Override
                                public GeckoResult<Object> onMessage(String nativeApp,
                                                                     Object message,
                                                                     WebExtension.MessageSender sender) {
                                    if (!NATIVE_APP_ID.equals(nativeApp)) {
                                        return null;
                                    }
                                    return handleCordovaMessage(message);
                                }
                            };

                            geckoSession.getWebExtensionController()
                                    .setMessageDelegate(extension, delegate, NATIVE_APP_ID);
                        },
                        e -> Log.e(TAG, "Error installing Cordova WebExtension", e)
                );
    }

    private GeckoResult<Object> handleCordovaMessage(Object message) {
        if (!(message instanceof Map)) {
            Log.w(TAG, "Unknown message from extension: " + message);
            return GeckoResult.fromValue("");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) message;
        String type = (String) map.get("type");

        if ("exec".equals(type)) {
            String service = (String) map.get("service");
            String action = (String) map.get("action");
            String callbackId = (String) map.get("callbackId");
            String argsJson = (String) map.get("arguments");

            Log.d(TAG, "Cordova exec from Gecko: " + service + "." + action + " cb=" + callbackId);

            jsMessageQueue.setPaused(true);
            try {
                CordovaResourceApi.jsThread = Thread.currentThread();
                pluginManager.exec(service, action, callbackId, argsJson);

                String ret = "";
                if (!NativeToJsMessageQueue.DISABLE_EXEC_CHAINING) {
                    ret = jsMessageQueue.popAndEncode(false);
                }
                return GeckoResult.fromValue(ret);
            } catch (Throwable e) {
                Log.e(TAG, "Error in Cordova exec", e);
                return GeckoResult.fromValue("");
            } finally {
                jsMessageQueue.setPaused(false);
            }
        } else if ("setBridgeMode".equals(type)) {
            Number value = (Number) map.get("value");
            if (value != null) {
                jsMessageQueue.setBridgeMode(value.intValue());
            }
            return GeckoResult.fromValue(null);
        } else if ("retrieveJsMessages".equals(type)) {
            Boolean fromOnline = (Boolean) map.get("fromOnlineEvent");
            boolean flag = fromOnline != null && fromOnline;
            String encoded = jsMessageQueue.popAndEncode(flag);
            return GeckoResult.fromValue(encoded);
        }

        Log.w(TAG, "Unhandled Cordova message type: " + type);
        return GeckoResult.fromValue("");
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
