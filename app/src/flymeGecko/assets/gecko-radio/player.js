"use strict";
// Headless MusicKit JS station player. Messages carry state only: never token
// values, request URLs, vendor error text or response bodies.
let radioMusic = null;
let radioGeneration = 0;
let radioPrepared = false;
let radioLastState = "";
// Without a Widevine CDM MusicKit JS silently falls back to 30-second previews,
// which start mid-song. Report both so the app can say why.
let radioDrm = true;

async function radioWidevine() {
  if (!navigator.requestMediaKeySystemAccess) return false;
  try {
    const access = await navigator.requestMediaKeySystemAccess("com.widevine.alpha", [{
      initDataTypes: ["cenc"],
      audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }],
    }]);
    await access.createMediaKeys();
    return true;
  } catch (_) { return false; }
}

function radioPreview(item) {
  if (radioMusic.previewOnly === true) return true;
  if (!item || item.live || item.type !== "song") return false;
  const actual = Number(radioMusic.currentPlaybackDuration) || 0;
  return item.durationMs > 60000 && actual > 0 && actual <= 35;
}

// GeckoView on Android plays HLS natively through its bundled ExoPlayer, which
// has no EME. MusicKit then picks <audio src=m3u8> for live radio and stalls.
// Hide native HLS so MusicKit uses its MSE + Widevine path, as on desktop.
const radioNativeHls = (() => {
  const original = HTMLMediaElement.prototype.canPlayType;
  const value = original.call(document.createElement("audio"), "application/vnd.apple.mpegurl");
  HTMLMediaElement.prototype.canPlayType = function (type) {
    return /mpegurl/i.test(String(type)) ? "" : original.call(this, type);
  };
  return value === "probably" || value === "maybe";
})();

function radioSend(kind, detail) {
  window.postMessage({ type: "amatv-radio-event", nonce: radioConfig.nonce, kind, ...detail }, location.origin);
}

function radioScriptFailed() { radioSend("fatal", { reason: "NETWORK_ERROR" }); }

function radioReason(error) {
  const status = Number(error && (error.status || error.statusCode || error.response && error.response.status));
  if (status === 401 || status === 403) return "AUTHORIZATION_ERROR";
  if (status === 404) return "NOT_FOUND";
  if (status >= 500 && status <= 599) return "SERVER_ERROR";
  const reason = String(error && (error.errorCode || error.reason || error.name) || "Error");
  return /^[A-Za-z_]{2,40}$/.test(reason) ? reason : "Error";
}

// MusicKit's StoreKit reads music.<teamId>.media-user-token while configuring.
// Seed the app's existing account token there instead of a second web sign-in.
function radioTokenKey(developerToken) {
  try {
    const payload = JSON.parse(atob(developerToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return payload.iss ? `music.${String(payload.iss).toLowerCase()}.media-user-token` : null;
  } catch (_) { return null; }
}

function radioStateName() {
  const states = (window.MusicKit && MusicKit.PlaybackStates) || {};
  for (const name in states) if (states[name] === radioMusic.playbackState) return name;
  return "none";
}

function radioItem(item) {
  if (!item) return null;
  const a = item.attributes || {};
  const play = a.playParams || item.playParams || {};
  const type = /^song/.test(String(item.type || "")) ? "song" : String(item.type || "");
  const art = a.artwork || item.artwork;
  const artworkUrl = art && typeof art.url === "string"
    ? art.url.replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg") : "";
  return {
    type,
    id: String(item.id || ""),
    catalogId: String(play.catalogId || (type === "song" ? play.id || item.id || "" : "")),
    title: String(item.title || a.name || ""),
    artist: String(item.artistName || a.artistName || ""),
    album: String(item.albumName || a.albumName || ""),
    durationMs: Number(item.playbackDuration || a.durationInMillis || 0) ||
      Math.round((Number(radioMusic.currentPlaybackDuration) || 0) * 1000),
    live: !!(item.isLiveRadioStation || a.isLive),
    hasLyrics: !!a.hasLyrics,
    artworkUrl,
  };
}

function radioReport(force) {
  if (!radioMusic || !radioGeneration) return;
  const item = radioItem(radioMusic.nowPlayingItem);
  const state = {
    generation: radioGeneration, prepared: radioPrepared, state: radioStateName(),
    playing: radioMusic.isPlaying === true, live: !!(item && item.live), item,
    positionMs: Math.round((Number(radioMusic.currentPlaybackTime) || 0) * 1000),
    drm: radioDrm, preview: radioPreview(item),
  };
  const key = JSON.stringify({ ...state, positionMs: Math.floor(state.positionMs / 1000) });
  if (!force && key === radioLastState) return;
  radioLastState = key;
  radioSend("state", state);
}

async function radioPrepare(command) {
  const generation = radioGeneration = Number(command.generation) || 0;
  radioPrepared = false;
  try {
    radioSend("trace", { stage: "prepare_received" });
    try { await radioMusic.stop(); } catch (_) { }
    await radioMusic.setQueue({ station: String(command.stationId), startPlaying: false });
    if (generation !== radioGeneration) return;
    radioSend("trace", { stage: "queue_ready" });
    radioPrepared = true;
    radioReport(true);
    if (command.play) await radioMusic.play();
    radioSend("trace", { stage: "play_resolved" });
  } catch (error) {
    if (generation === radioGeneration) radioSend("error", { generation, reason: radioReason(error) });
  }
  if (generation === radioGeneration) radioReport(true);
}

async function radioCommand(command) {
  if (!radioMusic || !command) return;
  try {
    switch (command.cmd) {
      case "prepare": return await radioPrepare(command);
      case "play": return await radioMusic.play();
      case "pause": return radioMusic.pause();
      case "next": return await radioMusic.skipToNextItem();
      case "stop":
        radioGeneration = 0;
        radioPrepared = false;
        return await radioMusic.stop();
    }
  } catch (error) {
    if (radioGeneration) radioSend("error", { generation: radioGeneration, reason: radioReason(error) });
  }
}

window.addEventListener("message", event => {
  if (event.source !== window || event.origin !== location.origin) return;
  const data = event.data;
  if (data && data.type === "amatv-radio-command") radioCommand(data.command);
});

document.addEventListener("musickitloaded", async () => {
  const tokenKey = radioTokenKey(radioConfig.developerToken);
  try {
    if (tokenKey && radioConfig.userToken) localStorage.setItem(tokenKey, radioConfig.userToken);
    await MusicKit.configure({
      developerToken: radioConfig.developerToken,
      app: { name: radioConfig.appName, build: radioConfig.appBuild },
    });
    radioMusic = MusicKit.getInstance();
    const events = MusicKit.Events || {};
    const on = (name, handler) => { if (events[name]) radioMusic.addEventListener(events[name], handler); };
    on("playbackStateDidChange", () => radioReport(true));
    on("nowPlayingItemDidChange", () => radioReport(true));
    on("mediaPlaybackError", error => {
      if (radioGeneration) radioSend("error", { generation: radioGeneration, reason: radioReason(error) });
    });
    setInterval(() => radioReport(false), 1000);
    radioDrm = await radioWidevine();
    radioSend("ready", {
      authorized: radioMusic.isAuthorized === true, nativeHls: radioNativeHls,
      drm: radioDrm, previewOnly: radioMusic.previewOnly === true,
    });
  } catch (error) {
    radioSend("fatal", { reason: radioReason(error) });
  } finally {
    // StoreKit keeps the token in memory; the profile must not retain a plaintext copy.
    if (tokenKey) try { localStorage.removeItem(tokenKey); } catch (_) { }
    radioConfig.userToken = null;
    radioConfig.developerToken = null;
  }
});
