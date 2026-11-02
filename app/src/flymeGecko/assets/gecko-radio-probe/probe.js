"use strict";
let music = null;
let eventCount = 0;
let stationKind = null;
const out = document.getElementById('output');
function probeEvent(stage, detail = {}) {
  // No vendor error text, URLs, account labels, token values or response bodies.
  const clean = {stage, ...detail};
  const line = JSON.stringify(clean);
  if (++eventCount > 300) return;
  out.textContent = (out.textContent + line + '\n').split('\n').slice(-60).join('\n');
  window.postMessage({type:'amatv-radio-probe', nonce:probeConfig.nonce, ...clean}, location.origin);
}
function probeError(stage, error) {
  const name = String(error && error.name || 'Error');
  const status = Number(error && (error.status || error.statusCode || error.response && error.response.status));
  probeEvent(stage, {error: /^[A-Za-z][A-Za-z0-9_.]{0,60}$/.test(name) ? name : 'Error',
    ...(Number.isInteger(status) && status >= 100 && status <= 599 ? {status} : {})});
}
async function checkDrm() {
  probeEvent('environment', {secure:window.isSecureContext, eme:typeof navigator.requestMediaKeySystemAccess==='function'});
  if (!navigator.requestMediaKeySystemAccess) return probeEvent('drm_unavailable');
  try {
    const access = await navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
      initDataTypes:['cenc'], audioCapabilities:[{contentType:'audio/mp4; codecs="mp4a.40.2"'}],
      sessionTypes:['temporary'], persistentState:'optional', distinctiveIdentifier:'optional'
    }]);
    await access.createMediaKeys();
    probeEvent('drm_keys_created');
  } catch(e) { probeError('drm_failed', e); }
}
document.getElementById('drm').onclick = checkDrm;
window.addEventListener('unhandledrejection', e => probeError('unhandled', e.reason));
function authState() {
  return {authorized:music && music.isAuthorized === true,
    userTokenPresent:!!(music && typeof music.musicUserToken==='string' && music.musicUserToken.length>20)};
}
document.addEventListener('musickitloaded', async function() {
  probeEvent('script_loaded');
  try {
    const config = {developerToken:probeConfig.developerToken, app:{name:'AM ATV radio probe',build:'1'}};
    // This is a configuration compatibility probe, not mutation of private SDK fields.
    // If the SDK does not accept the existing token, require its normal authorize flow.
    if (probeConfig.userToken) config.musicUserToken = probeConfig.userToken;
    await MusicKit.configure(config);
    music = MusicKit.getInstance();
    probeConfig.userToken = null;
    probeConfig.developerToken = null;
    probeEvent('musickit_ready', authState());
    for (const id of ['auth','personal','live','stop']) document.getElementById(id).disabled=false;
    const events = MusicKit.Events || {};
    const on = (name, handler) => { if (events[name]) music.addEventListener(events[name], handler); };
    on('playbackStateDidChange', () => probeEvent('playback_state', {state:Number(music.playbackState)||0}));
    on('nowPlayingItemDidChange', () => probeEvent('item_changed', {itemPresent:!!music.nowPlayingItem}));
    on('mediaPlaybackError', e => probeError('media_error', e));
    let last = -1;
    setInterval(() => {
      const position = Number(music.currentPlaybackTime) || 0;
      if (music.isPlaying || position !== last) {
        probeEvent('clock', {position:Math.round(position*1000),playing:music.isPlaying===true,
          state:Number(music.playbackState)||0,itemPresent:!!music.nowPlayingItem});
        last = position;
      }
    }, 3000);
  } catch(e) { probeError('configure_failed', e); }
});
document.getElementById('auth').onclick = async function() {
  if (!music) return;
  try { await music.authorize(); probeEvent('authorization_done', authState()); }
  catch(e) { probeError('authorization_failed', e); }
};
async function playStation(kind) {
  if (!music) return;
  if (!music.isAuthorized) return probeEvent('web_authorization_needed', authState());
  try {
    if (stationKind) await music.stop();
    probeEvent('station_request', {kind});
    const params = kind === 'personal' ? {'filter[identity]':'personal'} : {'filter[featured]':'apple-music-live-radio'};
    const response = await music.api.music('/v1/catalog/{{storefrontId}}/stations', params);
    const items = response && response.data && response.data.data || [];
    const station = kind === 'live' ? items.find(s=>s.id==='ra.978194965') || items[0] : items[0];
    probeEvent('station_response', {kind,count:items.length});
    if (!station || typeof station.id !== 'string') return probeEvent('station_missing', {kind});
    stationKind = kind;
    await music.setQueue({station:station.id});
    probeEvent('queue_ready', {kind});
    await music.play();
    probeEvent('play_resolved', {kind});
  } catch(e) { probeError('station_failed', e); }
}
document.getElementById('personal').onclick = () => playStation('personal');
document.getElementById('live').onclick = () => playStation('live');
document.getElementById('stop').onclick = async () => {
  try { if(music) await music.stop(); stationKind=null; probeEvent('stopped'); }
  catch(e) { probeError('stop_failed',e); }
};
checkDrm();
