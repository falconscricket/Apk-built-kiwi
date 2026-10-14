/* ALPHA CALL FIX (MAIN world, document_start).
   Symptom: in a WhatsApp Web call the mic button can't be tapped, the device dropdown closes by itself
   and the call ends up muted. Cause: Android switches communication mode / Bluetooth SCO when the mic opens,
   Gecko fires `devicechange` (and the local mic track fires `mute`) several times, and WhatsApp reacts by
   re-rendering its call bar, closing the dropdown and muting. We coalesce devicechange (deliver only on a real change) and swallow track mute events. */
(function () {
  try {
    if (window.__ALPHA_CALLFIX__) return;
    window.__ALPHA_CALLFIX__ = true;
    // Log channel page -> app. console.* does not reach logcat in this GeckoView, so we send each line through
    // document.title ("ALPHA_DBG|..."); MainActivity's ContentDelegate.onTitleChange writes it to the app log.
    var q = [], busy = false;
    function flush() {
      if (busy || !q.length) return;
      if (!document.head && !document.documentElement) { setTimeout(flush, 200); return; }
      if (document.readyState === "loading") { setTimeout(flush, 200); return; }
      busy = true;
      var m = q.shift(), prev = document.title;
      if (prev.indexOf("ALPHA_DBG|") === 0) prev = "WhatsApp";
      try { document.title = "ALPHA_DBG|" + m; } catch (e) {}
      setTimeout(function () {
        try { if (document.title.indexOf("ALPHA_DBG|") === 0) document.title = prev; } catch (e) {}
        busy = false; flush();
      }, 90);
    }
    var L = function (m) {
      try { console.error("ALPHA_DBG " + m); } catch (e) {}
      try { if (q.length < 300) { q.push(String(m).slice(0, 220)); flush(); } } catch (e) {}
    };
    window.__alphaLog = L;
    L("callfix script started on " + location.host + " t=" + Date.now());
    var md = navigator.mediaDevices;
    if (!md) return;

    // 1) devicechange: WhatsApp needs it to refresh the mic list, but Android fires it in storms while the
    //    route flips (SCO on/off). So: coalesce, and only deliver when the device list really changed.
    var proto = Object.getPrototypeOf(md);
    var origAdd = proto.addEventListener;
    var origEnum = md.enumerateDevices.bind(md);
    var dcListeners = [], dcProp = null, lastSig = "", dcTimer = null;
    function sig(list) {
      return list.map(function (d) { return d.kind + ":" + d.deviceId + ":" + d.label; }).sort().join("|");
    }
    function describe(list) {
      return list.map(function (d) {
        return d.kind + "[" + (d.label || "NO-LABEL") + "]" + (d.deviceId || "").slice(0, 8);
      }).join(", ");
    }
    // Gecko on Android exposes NO audiooutput devices -> WhatsApp shows "No speakers available"
    // and greys out the mic/speaker picker. Add a virtual speaker + give the mic a readable label.
    function fakeDev(kind, id, label, gid) {
      var o = { deviceId: id, kind: kind, label: label, groupId: gid || "alpha-phone" };
      o.toJSON = function () { return { deviceId: id, kind: kind, label: label, groupId: o.groupId }; };
      try { Object.setPrototypeOf(o, (window.MediaDeviceInfo || Object).prototype); } catch (e) {}
      return o;
    }
    function fixList(raw) {
      var out = [], hasOut = false, hasLabel = false;
      raw.forEach(function (d) {
        if (d.kind === "audiooutput") hasOut = true;
        if (d.label) hasLabel = true;
        if (d.kind === "audioinput" && d.label && /default audio input/i.test(d.label))
          out.push(fakeDev("audioinput", d.deviceId, "Phone microphone", d.groupId));
        else out.push(d);
      });
      if (!hasOut) out.push(fakeDev("audiooutput", "default", hasLabel ? "Phone speaker" : ""));
      return out;
    }
    if (window.HTMLMediaElement && !HTMLMediaElement.prototype.__alphaSink) {
      HTMLMediaElement.prototype.__alphaSink = true;
      var origSink = HTMLMediaElement.prototype.setSinkId;
      HTMLMediaElement.prototype.setSinkId = function (id) {
        var el = this;
        if (!id || id === "default") { try { Object.defineProperty(el, "sinkId", { value: "default", configurable: true }); } catch (e) {} return Promise.resolve(); }
        return origSink ? origSink.call(el, id).catch(function () { return undefined; }) : Promise.resolve();
      };
    }
    proto.addEventListener = function (type, listener, opts) {
      if (type === "devicechange" && this === md) { dcListeners.push(listener); L("captured devicechange listener"); return; }
      return origAdd.call(this, type, listener, opts);
    };
    try {
      Object.defineProperty(md, "ondevicechange", {
        get: function () { return dcProp; },
        set: function (f) { dcProp = f; L("captured ondevicechange"); },
        configurable: true
      });
    } catch (e) {}
    md.enumerateDevices = function () {
      return origEnum().then(function (raw) {
        var list = fixList(raw);
        lastSig = sig(list);
        L("enumerateDevices -> " + (list.length ? describe(list) : "EMPTY"));
        return list;
      });
    };
    origAdd.call(md, "devicechange", function () {
      L("raw devicechange");
      clearTimeout(dcTimer);
      dcTimer = setTimeout(function () {
        origEnum().then(function (raw) {
          var list = fixList(raw);
          var s = sig(list);
          if (s === lastSig) { L("devicechange dropped (same devices)"); return; }
          lastSig = s;
          L("devicechange delivered -> " + describe(list));
          var ev = new Event("devicechange");
          dcListeners.forEach(function (fn) {
            try { typeof fn === "function" ? fn.call(md, ev) : fn.handleEvent(ev); } catch (e) {}
          });
          try { if (typeof dcProp === "function") dcProp.call(md, ev); } catch (e) {}
        });
      }, 1500);
    });
    // log what the page can see right after load (labels may be empty until mic permission is granted)
    setTimeout(function () { try { md.enumerateDevices(); } catch (e) {} }, 4000);

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
          if (t.readyState === "ended" || n > 8) { clearInterval(iv); return; }
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

    // 4) Keep-alive mic. The log shows WhatsApp calling getUserMedia ~every second: each time Android opens the
    //    mic, flips communication mode / Bluetooth SCO, then closes it again (-> devicechange storms, mute, closing
    //    dropdown). We keep ONE real capture open (30s idle timeout) and hand the page cheap clones of it, so the
    //    OS route stays stable. Clones are independent: page calling track.stop() never kills the source.
    var callStream = null;
    var keep = null, keepKey = "", keepTimer = null;
    function releaseKeep() {
      clearTimeout(keepTimer);
      if (keep) { try { keep.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {} L("keep-alive mic released"); }
      keep = null;
    }
    function live(st) {
      return st && st.getAudioTracks().length > 0 && st.getAudioTracks().every(function (t) { return t.readyState === "live"; });
    }
    md.getUserMedia = function (c) {
      gumCount = (window.__alphaGum = (window.__alphaGum || 0) + 1);
      L("gUM #" + gumCount + " " + JSON.stringify(c));
      var audioOnly = c && c.audio && !c.video;
      if (false && audioOnly) {
        var key = JSON.stringify(c.audio);
        clearTimeout(keepTimer);
        keepTimer = setTimeout(releaseKeep, 30000);
        if (live(keep)) {
          L("gUM #" + gumCount + " served from keep-alive mic (no new capture)");
          var c2 = keep.clone();
          c2.getAudioTracks().forEach(patchTrack);
          return Promise.resolve(c2);
        }
      }
      // WhatsApp keeps calling gUM({audio:true}) every few seconds during a call (mic-level check).
      // On Android each new capture steals the mic from the CALL track -> other side hears nothing.
      // Once the real call mic exists, answer those checks with a copy of it instead of a new capture.
      if (audioOnly && callStream && live(callStream) && c.audio === true) {
        L("gUM #" + gumCount + " mic-check served from call mic (no new capture)");
        var cc = callStream.clone(); cc.getAudioTracks().forEach(patchTrack);
        return Promise.resolve(cc);
      }
      return gum(c).then(function (s) {
        if (audioOnly && typeof c.audio === "object") { callStream = s; L("call mic registered"); }
        try { s.getAudioTracks().forEach(patchTrack); } catch (e) {}
        if (false && audioOnly) {
          try { releaseKeep(); keep = s.clone(); keepKey = JSON.stringify(c.audio); keepTimer = setTimeout(releaseKeep, 30000); } catch (e) {}
        }
        return s;
      });
    };
    L("callfix ready");
  } catch (e) {}
})();
