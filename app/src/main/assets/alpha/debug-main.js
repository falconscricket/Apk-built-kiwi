/* ALPHA DEBUG (log build only): runs in the page's MAIN world so page CSP cannot block it.
   Logs mic mute/unmute to logcat as GeckoConsole. Does not change audio. */
(function () {
  try {
    if (window.__ALPHA_DBG__) return;
    window.__ALPHA_DBG__ = true;
    var L = function (m) { if (window.__alphaLog) window.__alphaLog(m); else console.error("ALPHA_DBG " + m); };
    var d = Object.getOwnPropertyDescriptor(MediaStreamTrack.prototype, "enabled");
    if (d && d.set) {
      Object.defineProperty(MediaStreamTrack.prototype, "enabled", {
        get: d.get,
        set: function (v) {
          L("track.enabled=" + v + " kind=" + this.kind + " state=" + this.readyState + " t=" + Date.now());
          d.set.call(this, v);
        },
        configurable: true
      });
    }
    var md = navigator.mediaDevices;
    if (md && md.getUserMedia) {
      var g = md.getUserMedia.bind(md);
      md.getUserMedia = function (c) {
        L("gUM " + JSON.stringify(c));
        return g(c).then(function (s) {
          s.getAudioTracks().forEach(function (t) {
            L("track created kind=" + t.kind + " enabled=" + t.enabled);
            t.addEventListener("ended", function () { L("track ended kind=" + t.kind); });
          });
          return s;
        }, function (e) { L("gUM error " + e.name + " " + e.message); throw e; });
      };
    }
    if (window.RTCRtpSender && RTCRtpSender.prototype.replaceTrack) {
      var rt = RTCRtpSender.prototype.replaceTrack;
      RTCRtpSender.prototype.replaceTrack = function (t) {
        L("replaceTrack " + (t ? ("kind=" + t.kind + " enabled=" + t.enabled) : "null"));
        return rt.call(this, t);
      };
    }
    L("debug hook ready (main world)");
  } catch (e) {}
})();
