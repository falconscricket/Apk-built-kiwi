/* ALPHA Mic Booster — v9 audio engine (runs in page main-world) */
(function () {
  if (window.__ALPHA_ENGINE__) return;
  window.__ALPHA_ENGINE__ = true;
  const TAG = "ALPHA";

  // ---------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------
  let state = {
    enabled: false,   // OFF by default: raw mic, no processing

    // Gain / Booster
    autoGainMax: false,          // 1-100x secondary multiplier ("Volume Booster")

    disableAGC: true,
    disableNS: false,

    // 5-band EQ, now -24..+24 dB per band

    // Noise gate
    gateEnabled: false,
    gateThreshold: -50,

    mode: "ALPHA",

    // Default ALPHA reset state
    intensity: 10,
    boosterX: 100,
    autoGainMax: true,
    disableAGC: true,
    disableNS: true,
    eq60: -24, eq250: -24, eq1k: 24, eq4k: -24, eq12k: -24,
    gateEnabled: true,
    gateThreshold: -20,
    heavyEnabled: true,

    // Heavy / distortion
    heavyDrive: 100,

    // Echo — driven by "repeats" 0-9. Each step raises both the feedback
    // (how many audible repeats) and the wet mix (how loud those repeats
    // are), capped at safe values so it never runs away into feedback howl.
    echoEnabled: false,
    echoDelay: 250,
    echoRepeats: 6,        // 0-9
    echoFeedback: 0,       // derived, kept for state-sync/back-compat
    echoMix: 0,            // derived, kept for state-sync/back-compat

    // Voice / pitch — widened to +/-24 semitones (2 octaves)
    pitchEnabled: false,
    pitchSemitones: 0,
    voicePreset: "Normal",

    // Soundboard
    sbFxEnabled: false,
    sbVolume: 1,
    soundboardEnabled: true,
    soundAutoPlay: false,
    songLoop: false,
    songFx: { echo: false, echoRepeats: 4, echoDelay: 300, reverb: false, chorus: false, tremolo: false,
              distortion: false, robot: false, telephone: false, bass: false, speed: 100 }
  };

  let ctx = null;
  let engines = [];      // one per hooked getUserMedia() call stream
  let testEngine = null; // engine for the local "Test My Mic" loopback

  let sbBuffer = null;        // decoded AudioBuffer for the loaded soundboard clip
  let sbSourceNode = null;    // currently-playing BufferSource (soundboard)
  let recorder = null;
  let recordedChunks = [];

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------
  function dbToGain(db) { return Math.pow(10, db / 20); }
  function intensityToGain(v) { return 1 + (v / 10) * 999; } // 0-10 -> 1x..1000x
  function semitonesToRatio(st) { return Math.pow(2, st / 12); }

  function echoParamsFromRepeats(n) {
    const t = Math.max(0, Math.min(9, n)) / 9;
    return {
      feedback: n === 0 ? 0 : Math.min(0.85, 0.15 + t * 0.7),
      mix: n === 0 ? 0 : Math.min(1, 0.1 + t * 0.9)
    };
  }

  function getCtx() {
    if (!ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      try { ctx = new AC({ sampleRate: 48000, latencyHint: "interactive" }); }
      catch (_) { ctx = new AC(); }
    }
    if (ctx.state === "suspended") ctx.resume().catch(() => {});
    return ctx;
  }

  function makeFilter(c, freq, type) {
    const f = c.createBiquadFilter();
    f.type = type;
    f.frequency.value = freq;
    if (type === "peaking") f.Q.value = 1;
    f.gain.value = 0;
    return f;
  }

  // Waveshaper-based "Heavy Sound" distortion with a bit of sub-bass lift
  function makeHeavy(c) {
    const shaper = c.createWaveShaper();
    shaper.oversample = "2x";
    shaper._setDrive = function (amount) {
      const curve = new Float32Array(1024);
      const k = amount * 180;
      for (let i = 0; i < 1024; i++) {
        const x = (i / 1023) * 2 - 1;
        curve[i] = k === 0 ? x : ((1 + k) * x) / (1 + k * Math.abs(x));
      }
      shaper.curve = curve;
    };
    shaper._setDrive(0);
    const subBass = makeFilter(c, 90, "lowshelf");
    subBass.gain.value = 0;
    shaper.connect(subBass);
    return { input: shaper, output: subBass, shaper, subBass };
  }

  // Granular pitch shifter (ScriptProcessor based, dependency-free)
  function makePitchShifter(c) {
    const node = c.createScriptProcessor(4096, 1, 1);
    const bufSize = 16384;
    const buf = new Float32Array(bufSize);
    let writeIdx = 0;
    const grain = 2048;
    let readA = 0, readB = grain / 2;
    node._ratio = 1;
    function hann(p) { return 0.5 - 0.5 * Math.cos(2 * Math.PI * p); }
    node.onaudioprocess = (ev) => {
      const input = ev.inputBuffer.getChannelData(0);
      const output = ev.outputBuffer.getChannelData(0);
      const ratio = node._ratio;
      for (let i = 0; i < input.length; i++) {
        buf[writeIdx] = input[i];
        writeIdx = (writeIdx + 1) % bufSize;
        const idxA = Math.floor(readA) % bufSize;
        const idxB = Math.floor(readB) % bufSize;
        const phaseA = (readA % grain) / grain;
        const phaseB = (readB % grain) / grain;
        const wA = hann(phaseA), wB = hann(phaseB);
        const wSum = wA + wB || 1;
        output[i] = (buf[idxA] * wA + buf[idxB] * wB) / wSum;
        readA += ratio; if (readA >= bufSize) readA -= bufSize;
        readB += ratio; if (readB >= bufSize) readB -= bufSize;
      }
    };
    return node;
  }

  // Noise gate (ScriptProcessor based)
  function makeGate(c) {
    const node = c.createScriptProcessor(2048, 1, 1);
    node._gateEnabled = false;
    node._thresholdDb = -50;
    node._envelope = 0;
    node.onaudioprocess = (ev) => {
      const input = ev.inputBuffer.getChannelData(0);
      const output = ev.outputBuffer.getChannelData(0);
      if (!node._gateEnabled) { output.set(input); return; }
      const thresh = dbToGain(node._thresholdDb);
      for (let i = 0; i < input.length; i++) {
        const s = input[i];
        node._envelope = Math.max(Math.abs(s), node._envelope * 0.995);
        output[i] = node._envelope > thresh ? s : s * 0.05;
      }
    };
    return node;
  }

  // Apply current `state` to one live engine graph
  function applyState(e) {
    if (!e) return;
    const on = state.enabled;
    const t = ctx.currentTime;

    const gainOn = on && !state.autoGainMax;
    e.gainNode.gain.setTargetAtTime(gainOn ? intensityToGain(state.intensity) : 1, t, 0.05);
    e.boosterGain.gain.setTargetAtTime(gainOn ? state.boosterX : 1, t, 0.05);
    if (e.autoComp) {
      e.autoComp.threshold.setTargetAtTime(state.autoGainMax && on ? -30 : -100, t, 0.05);
      e.autoComp.knee.setTargetAtTime(state.autoGainMax && on ? 24 : 0, t, 0.05);
      e.autoComp.ratio.setTargetAtTime(state.autoGainMax && on ? 20 : 1, t, 0.05);
      if (e.autoMakeup) e.autoMakeup.gain.setTargetAtTime(state.autoGainMax && on ? 4 : 1, t, 0.05);
      e.autoComp.attack.setTargetAtTime(0.003, t, 0.01);
      e.autoComp.release.setTargetAtTime(0.12, t, 0.01);
    }

    e.eq60.gain.setTargetAtTime(on ? state.eq60 : 0, t, 0.05);
    e.eq250.gain.setTargetAtTime(on ? state.eq250 : 0, t, 0.05);
    e.eq1k.gain.setTargetAtTime(on ? state.eq1k : 0, t, 0.05);
    e.eq4k.gain.setTargetAtTime(on ? state.eq4k : 0, t, 0.05);
    e.eq12k.gain.setTargetAtTime(on ? state.eq12k : 0, t, 0.05);

    const heavyOn = on && state.heavyEnabled;
    e.heavy.shaper._setDrive(heavyOn ? state.heavyDrive / 100 : 0);
    e.heavy.subBass.gain.setTargetAtTime(heavyOn ? 6 : 0, t, 0.05);

    const pitchOn = on && state.pitchEnabled;
    e.pitchNode._ratio = semitonesToRatio(state.pitchSemitones);
    e.pitchDry.gain.setTargetAtTime(pitchOn ? 0 : 1, t, 0.03);
    e.pitchWet.gain.setTargetAtTime(pitchOn ? 1 : 0, t, 0.03);

    const echoOn = on && state.echoEnabled;
    const ep = echoParamsFromRepeats(state.echoRepeats);
    state.echoFeedback = ep.feedback;
    state.echoMix = ep.mix;
    e.echoDelay.delayTime.setTargetAtTime(Math.max(0.01, state.echoDelay / 1000), t, 0.05);
    e.echoFeedback.gain.setTargetAtTime(echoOn ? ep.feedback : 0, t, 0.05);
    e.echoWet.gain.setTargetAtTime(echoOn ? ep.mix : 0, t, 0.05);

    e.gate._gateEnabled = on && state.gateEnabled;
    e.gate._thresholdDb = state.gateThreshold;

    // Bypass the ScriptProcessor nodes completely when unused: they run on the
    // page's main thread and cause choppy audio on phones when always active.
    const needPitch = on && state.pitchEnabled;
    if (needPitch && !e._pitchLinked) {
      e.pitchIn.connect(e.pitchNode); e.pitchNode.connect(e.pitchWet); e._pitchLinked = true;
    } else if (!needPitch && e._pitchLinked) {
      try { e.pitchIn.disconnect(e.pitchNode); e.pitchNode.disconnect(); } catch (_) {}
      e._pitchLinked = false;
    }
    const needGate = on && state.gateEnabled;
    if (needGate && !e._gateLinked) {
      try { e.echoOut.disconnect(e.analyser); } catch (_) {}
      e.echoOut.connect(e.gate); e.gate.connect(e.analyser); e._gateLinked = true;
    } else if (!needGate && e._gateLinked) {
      try { e.echoOut.disconnect(e.gate); e.gate.disconnect(); } catch (_) {}
      e.echoOut.connect(e.analyser); e._gateLinked = false;
    }

    if (e.sbGain) e.sbGain.gain.setTargetAtTime(state.sbVolume, t, 0.02);
  }

  // Build the full processing graph for one MediaStream, return the engine object
  function buildEngine(stream) {
    const c = getCtx();
    const source = c.createMediaStreamSource(stream);
    const gainNode = c.createGain();
    const boosterGain = c.createGain();
    const autoComp = c.createDynamicsCompressor();
    const autoMakeup = c.createGain();

    const eq60 = makeFilter(c, 60, "lowshelf");
    const eq250 = makeFilter(c, 250, "peaking");
    const eq1k = makeFilter(c, 1000, "peaking");
    const eq4k = makeFilter(c, 4000, "peaking");
    const eq12k = makeFilter(c, 12000, "highshelf");

    const heavy = makeHeavy(c);

    const pitchIn = c.createGain();
    const pitchDry = c.createGain();
    const pitchWet = c.createGain();
    const pitchOut = c.createGain();
    const pitchNode = makePitchShifter(c);
    pitchIn.connect(pitchDry);
    pitchDry.connect(pitchOut);
    // pitchNode is linked on demand in applyState() (ScriptProcessor = main thread)
    pitchWet.connect(pitchOut);

    const echoIn = c.createGain();
    const echoOut = c.createGain();
    const echoDelay = c.createDelay(2);
    const echoFeedback = c.createGain();
    const echoWet = c.createGain();
    echoIn.connect(echoOut);          // dry path
    echoIn.connect(echoDelay);
    echoDelay.connect(echoFeedback);
    echoFeedback.connect(echoDelay);  // feedback loop = repeats
    echoDelay.connect(echoWet);
    echoWet.connect(echoOut);

    const gate = makeGate(c);
    const analyser = c.createAnalyser();
    analyser.fftSize = 512;

    const dest = c.createMediaStreamDestination();

    // Soundboard mixing point — volume node, routed dynamically at play()
    // time to either the EQ input (FX applied) or straight to dest (dry).
    const sbGain = c.createGain();
    sbGain.gain.value = state.sbVolume;

    // Main mic chain: source -> gain -> booster -> EQ -> heavy -> pitch -> echo -> gate -> analyser -> dest
    source.connect(gainNode);
    gainNode.connect(boosterGain);
    boosterGain.connect(autoComp);
    autoComp.connect(autoMakeup);
    autoMakeup.connect(eq60);
    eq60.connect(eq250);
    eq250.connect(eq1k);
    eq1k.connect(eq4k);
    eq4k.connect(eq12k);
    eq12k.connect(heavy.input);
    heavy.output.connect(pitchIn);
    pitchOut.connect(echoIn);
    echoOut.connect(analyser);      // gate is linked on demand in applyState()
    analyser.connect(dest);

    const engine = {
      source, gainNode, boosterGain, autoComp, autoMakeup,
      eq60, eq250, eq1k, eq4k, eq12k,
      heavy,
      pitchIn, pitchDry, pitchWet, pitchOut, pitchNode,
      echoIn, echoOut, echoDelay, echoFeedback, echoWet,
      gate, analyser, dest,
      sbGain,
      _pitchLinked: false, _gateLinked: false,
      sourceStream: stream
    };
    applyState(engine);
    return engine;
  }

  // ---------------------------------------------------------------------
  // Level meter (for the panel VU bar)
  // ---------------------------------------------------------------------
  function meterLoop() {
    const e = engines[engines.length - 1] || testEngine;
    if (e && e.analyser) {
      const buf = new Uint8Array(e.analyser.fftSize);
      e.analyser.getByteTimeDomainData(buf);
      let sum = 0;
      for (let i = 0; i < buf.length; i++) {
        const v = (buf[i] - 128) / 128;
        sum += v * v;
      }
      const rms = Math.sqrt(sum / buf.length);
      const level = Math.min(100, Math.round(rms * 260));
      window.postMessage({ type: TAG + "_LEVEL", level }, "*");
    }
    requestAnimationFrame(meterLoop);
  }
  requestAnimationFrame(meterLoop);

  // ---------------------------------------------------------------------
  // Hook getUserMedia so every call (Discord/WhatsApp/Instagram Web etc.)
  // is routed through the engine
  // ---------------------------------------------------------------------
  const origGUM = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
  navigator.mediaDevices.getUserMedia = function (constraints) {
    // Booster OFF (or no audio requested): hand back the untouched native stream
    if (!state.enabled || !(constraints && constraints.audio)) return origGUM(constraints);
    const patched = Object.assign({}, constraints, {
      audio: Object.assign({}, typeof constraints.audio === "object" ? constraints.audio : {}, {
        autoGainControl: !state.disableAGC,
        noiseSuppression: !state.disableNS,
        // Android applies its own hardware AEC/NS/AGC when echoCancellation is
        // requested (the default). That hardware processing plus our own gain/
        // booster chain is what turns the voice into garbly/noisy audio on
        // phones. Ask for the raw, unprocessed mic instead.
        echoCancellation: false
      })
    });
    return origGUM(patched).then((stream) => {
      try {
        const engine = buildEngine(stream);
        engines.push(engine);
        const outStream = engine.dest.stream;
        stream.getVideoTracks().forEach((vt) => outStream.addTrack(vt));
        window.postMessage({ type: TAG + "_HOOKED" }, "*");
        return outStream;
      } catch (err) {
        window.postMessage({ type: TAG + "_ERROR", message: (err && err.message) || "engine error" }, "*");
        return stream;
      }
    });
  };

  // ---------------------------------------------------------------------
  // Local mic test loopback
  // ---------------------------------------------------------------------
  function stopTest() {
    if (testEngine) {
      testEngine.sourceStream.getTracks().forEach((t) => t.stop());
      if (testEngine.audioEl) { testEngine.audioEl.pause(); testEngine.audioEl.srcObject = null; }
      testEngine = null;
    }
  }
  function startTest() {
    stopTest();
    origGUM({ audio: { autoGainControl: !state.disableAGC, noiseSuppression: !state.disableNS, echoCancellation: false } })
      .then((stream) => {
        testEngine = buildEngine(stream);
        const audioEl = new Audio();
        audioEl.srcObject = testEngine.dest.stream;
        audioEl.autoplay = true;
        testEngine.audioEl = audioEl;
      })
      .catch(() => window.postMessage({ type: TAG + "_ERROR", message: "Mic permission denied" }, "*"));
  }

  // ---------------------------------------------------------------------
  // Soundboard — load an audio file, then play it mixed into the live call
  // ---------------------------------------------------------------------
  function loadSoundboard(arrayBuffer) {
    const c = getCtx();
    c.decodeAudioData(arrayBuffer.slice(0)).then((buf) => {
      sbBuffer = buf;
      window.postMessage({ type: TAG + "_SB_LOADED", duration: buf.duration }, "*");
    }).catch((err) => {
      window.postMessage({ type: TAG + "_ERROR", message: "Couldn't decode sound file: " + (err && err.message) }, "*");
    });
  }

  // ---------------------------------------------------------------------
  // Song playback: effect chain, loop, and the independent song/mic recorder
  // ---------------------------------------------------------------------
  let sbNodes = [];
  let sbVolNode = null;       // live volume node of the playing song
  function track(n) { sbNodes.push(n); return n; }
  function disposeSongFx() {
    sbNodes.forEach((n) => {
      try { if (typeof n.stop === "function") n.stop(); } catch (_) {}
      try { n.disconnect(); } catch (_) {}
    });
    sbNodes = [];
  }
  function lfo(c, freq, depth, target) {
    const osc = track(c.createOscillator());
    osc.frequency.value = freq;
    const g = track(c.createGain());
    g.gain.value = depth;
    osc.connect(g); g.connect(target);
    osc.start();
    return osc;
  }
  function makeImpulse(c, seconds) {
    const len = Math.max(1, Math.floor(c.sampleRate * seconds));
    const buf = c.createBuffer(2, len, c.sampleRate);
    for (let ch = 0; ch < 2; ch++) {
      const d = buf.getChannelData(ch);
      for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3);
    }
    return buf;
  }
  function distortionCurve(k) {
    const n = 44100, curve = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      const x = (i * 2) / n - 1;
      curve[i] = ((3 + k) * x * 20 * (Math.PI / 180)) / (Math.PI + k * Math.abs(x));
    }
    return curve;
  }

  // Builds the effect chain for one playback. Serial effects first, then
  // parallel wet effects (echo, reverb, chorus) mixed with the dry signal.
  function buildSongFx(c, out, tapRec) {
    const fx = Object.assign({ echo: false, echoRepeats: 4, echoDelay: 300, reverb: false, chorus: false,
      tremolo: false, distortion: false, robot: false, telephone: false, bass: false, speed: 100 }, state.songFx || {});
    const input = track(c.createGain());
    let node = input;
    const serial = (n) => { node.connect(n); node = n; };

    if (fx.distortion) {
      const ws = track(c.createWaveShaper());
      ws.curve = distortionCurve(60); ws.oversample = "4x";
      const g = track(c.createGain()); g.gain.value = 2.5;
      serial(ws); serial(g);
    }
    if (fx.bass) {
      const b = track(c.createBiquadFilter());
      b.type = "lowshelf"; b.frequency.value = 150; b.gain.value = 10;
      serial(b);
    }
    if (fx.telephone) {
      const bp = track(c.createBiquadFilter());
      bp.type = "bandpass"; bp.frequency.value = 1400; bp.Q.value = 1.2;
      serial(bp);
    }
    if (fx.robot) {
      const ring = track(c.createGain());
      ring.gain.value = 0;
      lfo(c, 45, 1, ring.gain);           // ring modulation = robotic voice
      serial(ring);
    }
    if (fx.tremolo) {
      const trem = track(c.createGain());
      trem.gain.value = 0.5;
      lfo(c, 5, 0.5, trem.gain);
      serial(trem);
    }

    const mix = track(c.createGain());
    mix.connect(out);
    if (tapRec) mix.connect(recSongTap);
    node.connect(mix);                     // dry

    if (fx.echo) {
      const delay = track(c.createDelay(2));
      delay.delayTime.value = Math.min(2, Math.max(0.05, Number(fx.echoDelay) / 1000));
      const fb = track(c.createGain());
      fb.gain.value = Math.min(0.85, 0.15 + Number(fx.echoRepeats) * 0.08);
      const wet = track(c.createGain()); wet.gain.value = 0.6;
      node.connect(delay); delay.connect(fb); fb.connect(delay);
      delay.connect(wet); wet.connect(mix);
    }
    if (fx.reverb) {
      const conv = track(c.createConvolver());
      conv.buffer = makeImpulse(c, 2.5);
      const wet = track(c.createGain()); wet.gain.value = 0.5;
      node.connect(conv); conv.connect(wet); wet.connect(mix);
    }
    if (fx.chorus) {
      const d = track(c.createDelay(0.1));
      d.delayTime.value = 0.02;
      lfo(c, 0.8, 0.004, d.delayTime);
      const wet = track(c.createGain()); wet.gain.value = 0.5;
      node.connect(d); d.connect(wet); wet.connect(mix);
    }
    return input;
  }

  function stopSoundboard() {
    if (sbSourceNode) {
      try { sbSourceNode.stop(); } catch (_) {}
      sbSourceNode = null;
    }
    disposeSongFx();
  }

  function playSoundboard() {
    const engine = engines[engines.length - 1] || testEngine;
    if (!state.soundboardEnabled) return;
    if (!sbBuffer) {
      window.postMessage({ type: TAG + "_ERROR", message: "No song loaded" }, "*");
      return;
    }
    stopSoundboard();
    const c = getCtx();
    const src = c.createBufferSource();
    src.buffer = sbBuffer;
    src.loop = !!state.songLoop;
    const speed = Number((state.songFx && state.songFx.speed) || 100);
    src.playbackRate.value = Math.min(1.5, Math.max(0.5, speed / 100));

    let target;
    let viaMicChain = false;
    if (c.state === "suspended") { try { c.resume(); } catch (_) {} }
    if (!engine) {
      // No call / mic engine: the song gets its own volume node, so the slider and
      // effects work on their own, without the main ALPHA switch.
      const vol = track(c.createGain());
      vol.gain.value = state.sbVolume;
      vol.connect(c.destination);
      sbVolNode = vol;
      target = vol;
    } else {
      try { engine.sbGain.disconnect(); } catch (_) {}
      viaMicChain = !!state.sbFxEnabled;
      engine.sbGain.connect(viaMicChain ? engine.eq60 : engine.dest);
      target = engine.sbGain;
      sbVolNode = null;             // engine's applyState() already controls this volume
    }
    // The mic chain already reaches the recorder through its analyser tap,
    // so the song is tapped separately only when it bypasses that chain.
    const tapRec = !!recSongTap && !viaMicChain;

    src.connect(buildSongFx(c, target, tapRec));
    src.onended = () => {
      if (sbSourceNode === src) {
        sbSourceNode = null;
        disposeSongFx();
        window.postMessage({ type: TAG + "_SB_ENDED" }, "*");
      }
    };
    src.start(0);
    sbSourceNode = src;
    window.postMessage({ type: TAG + "_SB_PLAYING" }, "*");
  }

  // Recorder: its own destination, fed by every mic engine's output and by the
  // song, so it records whatever is actually being heard, call or no call.
  let recSongTap = null;
  let recDest = null;
  let recTapped = [];
  function teardownRecTaps() {
    recTapped.forEach((n) => { try { n.disconnect(recDest); } catch (_) {} });
    try { if (recSongTap) recSongTap.disconnect(); } catch (_) {}
    recTapped = []; recSongTap = null; recDest = null;
  }
  function startRecording() {
    if (recorder && recorder.state !== "inactive") return;
    const all = engines.concat(testEngine ? [testEngine] : []);
    if (!all.length && !sbSourceNode) {
      window.postMessage({ type: TAG + "_ERROR", message: "Start Test My Mic, join a call or play a song before recording" }, "*");
      return;
    }
    try {
      const c = getCtx();
      recDest = c.createMediaStreamDestination();
      recSongTap = c.createGain();
      recSongTap.connect(recDest);
      all.forEach((e) => { if (e && e.analyser) { e.analyser.connect(recDest); recTapped.push(e.analyser); } });

      recordedChunks = [];
      const mime = MediaRecorder.isTypeSupported("audio/webm;codecs=opus") ? "audio/webm;codecs=opus" : (MediaRecorder.isTypeSupported("audio/webm") ? "audio/webm" : "");
      recorder = mime ? new MediaRecorder(recDest.stream, { mimeType: mime }) : new MediaRecorder(recDest.stream);
      recorder.ondataavailable = (ev) => { if (ev.data && ev.data.size) recordedChunks.push(ev.data); };
      recorder.onstop = () => {
        const blob = new Blob(recordedChunks, { type: "audio/webm" });
        teardownRecTaps();
        window.postMessage({ type: TAG + "_REC_DONE", blob }, "*");
      };
      recorder.start(250);
      window.postMessage({ type: TAG + "_REC_STARTED" }, "*");
    } catch (err) {
      teardownRecTaps();
      window.postMessage({ type: TAG + "_ERROR", message: "Recorder failed: " + (err && err.message) }, "*");
    }
  }
  function stopRecording() {
    if (recorder && recorder.state !== "inactive") recorder.stop();
  }

  // ---------------------------------------------------------------------
  // Message bridge (content script <-> page engine)
  // ---------------------------------------------------------------------
  const MODE_PRESETS = {
    ALPHA: {
      enabled: true,
      intensity: 10, boosterX: 100, autoGainMax: true, disableAGC: true, disableNS: true,
      eq60: -24, eq250: -24, eq1k: 24, eq4k: -24, eq12k: -24,
      gateEnabled: true, gateThreshold: -20,
      heavyEnabled: true, heavyDrive: 100,
      echoEnabled: false, echoRepeats: 6, echoDelay: 250,
      pitchEnabled: false, pitchSemitones: 0, voicePreset: "Normal",
      soundboardEnabled: false, soundAutoPlay: false, sbFxEnabled: false, sbVolume: 1,
      slot1: false, slot2: false, slot3: false, slot4: false,
      slot5: false, slot6: false, slot7: false, slot8: false
    },
    GOD:   { intensity: 8, boosterX: 10, eq60: -2, eq250: 1, eq1k: 4, eq4k: 5, eq12k: 3, gateEnabled: true, gateThreshold: -48, heavyEnabled: true, heavyDrive: 35 },
    BEAST: { intensity: 10, boosterX: 25, eq60: -1, eq250: 2, eq1k: 5, eq4k: 7, eq12k: 4, gateEnabled: true, gateThreshold: -55, heavyEnabled: true, heavyDrive: 65 },
    LORD:  { intensity: 10, boosterX: 100, eq60: 0, eq250: 3, eq1k: 7, eq4k: 9, eq12k: 6, gateEnabled: true, gateThreshold: -60, heavyEnabled: true, heavyDrive: 90, autoGainMax: true }
  };

  const VOICE_PRESETS = {
    Normal: { pitchEnabled: false, pitchSemitones: 0 },
    Male: { pitchEnabled: true, pitchSemitones: -6 },
    Deep: { pitchEnabled: true, pitchSemitones: -9 },
    Female: { pitchEnabled: true, pitchSemitones: 7 },
    Baby: { pitchEnabled: true, pitchSemitones: 9 },
    Chipmunk: { pitchEnabled: true, pitchSemitones: 12 },
    Robot: { pitchEnabled: true, pitchSemitones: -12, heavyEnabled: true, heavyDrive: 75 }
  };

  window.addEventListener("message", (ev) => {
    if (ev.source !== window || !ev.data) return;
    const msg = ev.data;

    if (msg.type === TAG + "_SET_STATE") {
      state = Object.assign({}, state, msg.value);
      if (sbSourceNode) sbSourceNode.loop = !!state.songLoop;
      if (sbVolNode) sbVolNode.gain.setTargetAtTime(state.sbVolume, ctx.currentTime, 0.02);
      engines.forEach(applyState);
      if (testEngine) applyState(testEngine);

    } else if (msg.type === TAG + "_APPLY_MODE") {
      const preset = MODE_PRESETS[msg.name];
      if (preset) {
        state = Object.assign({}, state, preset, { mode: msg.name });
        engines.forEach(applyState);
        if (testEngine) applyState(testEngine);
        window.postMessage({ type: TAG + "_STATE_SYNC", value: state }, "*");
      }

    } else if (msg.type === TAG + "_APPLY_VOICE") {
      const preset = VOICE_PRESETS[msg.name];
      if (preset) {
        state = Object.assign({}, state, preset, { voicePreset: msg.name });
        engines.forEach(applyState);
        if (testEngine) applyState(testEngine);
        window.postMessage({ type: TAG + "_STATE_SYNC", value: state }, "*");
      }

    } else if (msg.type === TAG + "_GET_STATE") {
      window.postMessage({ type: TAG + "_STATE_SYNC", value: state }, "*");

    } else if (msg.type === TAG + "_PING") {
      window.postMessage({ type: TAG + "_PONG", hooked: engines.length > 0 }, "*");

    } else if (msg.type === TAG + "_START_TEST") {
      startTest();
    } else if (msg.type === TAG + "_STOP_TEST") {
      stopTest();

    } else if (msg.type === TAG + "_SB_LOAD") {
      loadSoundboard(msg.buffer);
    } else if (msg.type === TAG + "_SB_PLAY") {
      playSoundboard();
    } else if (msg.type === TAG + "_SB_STOP") {
      stopSoundboard();

    } else if (msg.type === TAG + "_REC_TEST") {
      // Test recorder: runs until you press Stop, then the panel plays it back
      startRecording();
    } else if (msg.type === TAG + "_REC_START") {
      startRecording();
    } else if (msg.type === TAG + "_REC_STOP") {
      stopRecording();
    }
  });
})();
