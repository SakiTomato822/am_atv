(function () {
  "use strict";
  if (window !== window.top) return;
  window.addEventListener("message", function (event) {
    if (event.source !== window || event.origin !== location.origin) return;
    var d = event.data;
    if (!d || d.type !== "amatv-radio-probe" || typeof d.nonce !== "string") return;
    browser.runtime.sendNativeMessage("amatv_radio_probe", d).catch(function () {});
  });
})();
