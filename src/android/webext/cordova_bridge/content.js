(function () {
  // Must match NATIVE_APP in GeckoViewEngine.java
  const port = browser.runtime.connectNative("cordova");

  port.onMessage.addListener((msg) => {
    if (!msg || msg.type !== "EVAL") return;

    try {
      // Cordova uses this path to flush native->JS message queue.
      (0, eval)(msg.code);
      port.postMessage({ type: "EVAL_RESULT", id: msg.id, ok: true });
    } catch (e) {
      port.postMessage({ type: "EVAL_RESULT", id: msg.id, ok: false, error: String(e) });
    }
  });

  // Debug ping
  port.postMessage({ type: "READY" });
})();
