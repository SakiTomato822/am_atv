(function () {
  "use strict";
  var nativeApp = "amatv_auth";
  var diagnosticsSent = 0;

  function send(message) {
    browser.runtime.sendNativeMessage(nativeApp, message).catch(function () {});
  }
  function safeText(value) {
    var text;
    try { text = String(value); } catch (_) { return "[unreadable]"; }
    return text
      .replace(/(https?:\/\/[^\s?#]+)[?#][^\s]*/gi, "$1")
      .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?\b/g, "[token]")
      .replace(/\bBearer\s+[^\s]+/gi, "Bearer [redacted]")
      .replace(/([A-Za-z0-9_.+-]+)@([A-Za-z0-9.-]+\.[A-Za-z]{2,})/g, "[email]")
      .replace(/((?:password|passwd|token|authorization|account|email|code|secret|credential)[A-Za-z_-]*\s*[:=]\s*)[^\s,;]+/gi, "$1[redacted]")
      .slice(0, 700);
  }
  function diagnostic(level, message, source, line) {
    if (diagnosticsSent++ >= 200) return;
    send({ kind: "diagnostic", level: level, message: safeText(message),
      source: safeText(source || location.href), line: Number(line) || 0 });
  }

  // The native side additionally checks the exact random origin/path, top-level
  // root session and per-attempt nonce. Apple frames never carry token messages.
  if (window === window.top && location.protocol === "http:" && location.hostname === "127.0.0.1") {
    send({ kind: "bootstrap-ready", secureContext: window.isSecureContext === true });
    window.addEventListener("message", function (event) {
      if (event.source !== window || event.origin !== location.origin) return;
      var data = event.data;
      if (!data || data.type !== "amatv-auth-result" || typeof data.nonce !== "string") return;
      if (typeof data.token !== "string" || data.token.length > 16384) return;
      send({ kind: "authorized", nonce: data.nonce, token: data.token });
    });
  }

  window.addEventListener("error", function (event) {
    var target = event.target;
    if (target && target !== window) {
      diagnostic("ERROR", "Resource load failed", target.src || target.href || location.href);
    } else {
      diagnostic("ERROR", event.message || "JavaScript error", event.filename, event.lineno);
    }
  }, true);
  window.addEventListener("unhandledrejection", function (event) {
    diagnostic("ERROR", event.reason && event.reason.message || event.reason || "Unhandled promise rejection");
  });

  // Firefox's isolated content-script world can safely export a console relay
  // without giving page scripts access to browser.runtime/native messaging.
  try {
    var page = window.wrappedJSObject;
    ["error", "warn", "info", "log"].forEach(function (level) {
      exportFunction(function () {
        // Never pass this privileged arguments object into a page-world apply().
        // Firefox rejects that cross-compartment access, which would make even
        // console.info() throw and interrupt MusicKit's initialization.
        try {
          var values = [];
          for (var i = 0; i < arguments.length; i++) values.push(safeText(arguments[i]));
          diagnostic(level.toUpperCase(), values.join(" "));
        } catch (_) { }
      }, page.console, { defineAs: level });
    });
  } catch (_) {
    // Error/resource events remain available if this provider disallows exports.
  }
  diagnostic("INFO", "Authentication document started");
})();
