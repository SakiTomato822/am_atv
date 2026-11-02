(function () {
  "use strict";
  // Relays between the local player page and the app. The native side checks
  // the session, exact loopback origin and per-boot nonce of every message.
  if (window !== window.top || location.protocol !== "http:" || location.hostname !== "127.0.0.1") return;
  var port = browser.runtime.connectNative("amatv_radio");
  port.onMessage.addListener(function (command) {
    window.postMessage({ type: "amatv-radio-command", command: command }, location.origin);
  });
  window.addEventListener("message", function (event) {
    if (event.source !== window || event.origin !== location.origin) return;
    var data = event.data;
    if (!data || data.type !== "amatv-radio-event" || typeof data.nonce !== "string") return;
    port.postMessage(data);
  });
})();
