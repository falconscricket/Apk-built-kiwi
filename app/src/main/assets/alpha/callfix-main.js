/* ALPHA CALL FIX (MAIN world, document_start).
   Symptom: in a WhatsApp Web call the mic button can't be tapped, the device dropdown closes by itself
   and the call ends up muted. Cause: Android switches communication mode / Bluetooth SCO when the mic opens,
   Gecko fires `devicechange` (and the local mic track fires `mute`) several times, and WhatsApp reacts by
   re-rendering its call bar, closing the dropdown and muting. We simply don't deliver those two events. */
(function () {
  try {
    if (window.__ALPHA_CALLFIX__) return;
    window.__ALPHA_CALLFIX__ = true;
    var L = function (m) { try { console.error("ALPHA_DBG " + m); } catch (e) {} };
    var md = navigator.mediaDevices;
    if (!md) return;

    // 1) never tell the page that audio devices changed
    var proto = Object.getPrototypeOf(md);
    var origAdd = proto.addEventListener;
    proto.addEventListener = function (type, listener, opts) {
      if (type === "devicechange") { L("blocked devicechange listener"); return; }
      return origAdd.call(this, type, listener, opts);
    };
    try {
      Object.defineProperty(md, "ondevicechange", {
        get: function () { return null; },
        set: function () { L("blocked ondevicechange"); },
        configurable: true
      });
    } catch (e) {}

    // 2) local mic tracks: swallow platform 'mute' events (capture restarting during route changes)
    // 3) local mic: ignore `enabled=false` that was NOT caused by a user tap/key in the last 2s (= auto-mute)
    var lastGesture = 0;
    ["pointerdown", "touchstart", "mousedown", "click", "keydown"].forEach(function (ev) {
      window.addEventListener(ev, function () { lastGesture = Date.now(); }, true);
    });
    var ed = Object.getOwnPropertyDescriptor(MediaStreamTrack.prototype, "enabled");
    if (ed && ed.set) {
      Object.defineProperty(MediaStreamTrack.prototype, "enabled", {
        get: ed.get,
        set: function (v) {
          if (v === false && this.kind === "audio" && this.__alphaLocal && Date.now() - lastGesture > 2000) {
            L("blocked AUTO-MUTE (no user gesture)");
            return;
          }
          ed.set.call(this, v);
        },
        configurable: true
      });
    }

    function patchTrack(t) {
      try {
        t.__alphaLocal = true;
        var add = t.addEventListener.bind(t);
        var id = Math.random().toString(36).slice(2, 6);
        ["mute", "unmute", "ended"].forEach(function (ev) {
          add(ev, function () { L("track " + id + " event " + ev + " state=" + t.readyState + " enabled=" + t.enabled); });
        });
        var n = 0, iv = setInterval(function () {
          n++;
          if (t.readyState === "ended" || n > 40) { clearInterval(iv); return; }
          L("track " + id + " poll " + n + " state=" + t.readyState + " muted=" + t.muted + " enabled=" + t.enabled);
        }, 1500);
        t.addEventListener = function (type, listener, opts) {
          if (type === "mute") { L("blocked track mute listener"); return; }
          return add(type, listener, opts);
        };
        Object.defineProperty(t, "onmute", {
          get: function () { return null; },
          set: function () { L("blocked track onmute"); },
          configurable: true
        });
      } catch (e) {}
    }
    var gumCount = 0;
    var gum = md.getUserMedia.bind(md);
    md.getUserMedia = function (c) {
      gumCount = (window.__alphaGum = (window.__alphaGum || 0) + 1);
      L("gUM #" + gumCount + " " + JSON.stringify(c));
      return gum(c).then(function (s) {
        try { s.getAudioTracks().forEach(patchTrack); } catch (e) {}
        return s;
      });
    };
    L("callfix ready");
  } catch (e) {}
})();
