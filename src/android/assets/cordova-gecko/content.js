(function () {
  if (window._cordovaNative) {
    return;
  }

  const NATIVE_APP = "cordovaNative";

  async function exec(service, action, callbackId, argsJson) {
    const msg = {
      type: "exec",
      service,
      action,
      callbackId,
      arguments: argsJson // JSON string
    };

    try {
      const result = await browser.runtime.sendNativeMessage(NATIVE_APP, msg);
      if (typeof result === "string") {
        return result;
      }
    } catch (e) {
      console.error("Cordova Gecko exec error", e);
    }
    return "";
  }

  async function setNativeToJsBridgeMode(value) {
    const msg = {
      type: "setBridgeMode",
      value: value
    };
    try {
      await browser.runtime.sendNativeMessage(NATIVE_APP, msg);
    } catch (e) {
      console.error("Cordova Gecko setBridgeMode error", e);
    }
  }

  async function retrieveJsMessages(fromOnlineEvent) {
    const msg = {
      type: "retrieveJsMessages",
      fromOnlineEvent: !!fromOnlineEvent
    };
    try {
      const result = await browser.runtime.sendNativeMessage(NATIVE_APP, msg);
      if (typeof result === "string") {
        return result;
      }
    } catch (e) {
      console.error("Cordova Gecko retrieveJsMessages error", e);
    }
    return "";
  }

  window._cordovaNative = {
    exec,
    setNativeToJsBridgeMode,
    retrieveJsMessages
  };
})();