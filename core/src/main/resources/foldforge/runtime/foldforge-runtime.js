/*
 * FOLD FORGE preview runtime.
 * Injected into every page served by the FOLD FORGE preview sandbox. It provides:
 *  - runtime error / unhandled rejection capture (with stack traces)
 *  - frame metrics (FPS, frame time, long frames, load time, heap when available)
 *  - optional frame rate limiting (30 / 60 / auto)
 *  - touch & pointer event inspector ring buffer
 *  - FoldForgeTestInput: synthetic keyboard / pointer / touch input for the preview sandbox only
 *  - the QA scenario engine used by the FOLD FORGE Gameplay QA Agent
 * It exposes NO native/Android capability: communication with the host happens only through
 * console messages and host-initiated evaluateJavascript polling.
 */
(function () {
  'use strict';
  if (window.__ff) return;
  var cfg = window.__ffConfig || {};
  var ff = { version: 1, errors: [], startedAt: Date.now() };
  window.__ff = ff;

  // ---------------------------------------------------------------- errors
  function recordError(kind, message, source, line, col, stack) {
    var err = {
      kind: kind, message: String(message || 'Unknown error'), source: source || '',
      line: line || 0, column: col || 0, stack: stack || '', time: Date.now() - ff.startedAt
    };
    ff.errors.push(err);
    if (ff.errors.length > 200) ff.errors.shift();
    try { console.debug('__ffstack:' + JSON.stringify(err)); } catch (e) { /* ignore */ }
  }
  window.addEventListener('error', function (e) {
    if (e && e.target && e.target !== window && (e.target.src || e.target.href)) {
      recordError('resource', 'Failed to load resource: ' + (e.target.src || e.target.href), e.target.src || e.target.href, 0, 0, '');
      return;
    }
    recordError('error', e.message, e.filename, e.lineno, e.colno, e.error && e.error.stack);
  }, true);
  window.addEventListener('unhandledrejection', function (e) {
    var r = e.reason;
    recordError('rejection', 'Unhandled promise rejection: ' + (r && r.message ? r.message : String(r)), '', 0, 0, r && r.stack);
  });
  var origConsoleError = console.error;
  console.error = function () {
    try {
      var msg = Array.prototype.map.call(arguments, function (a) {
        return a && a.stack ? a.stack : (typeof a === 'object' ? safeStringify(a) : String(a));
      }).join(' ');
      if (msg.indexOf('__ffstack:') !== 0) ff.errors.push({ kind: 'console', message: msg, source: '', line: 0, column: 0, stack: '', time: Date.now() - ff.startedAt });
      if (ff.errors.length > 200) ff.errors.shift();
    } catch (e) { /* ignore */ }
    return origConsoleError.apply(console, arguments);
  };
  function safeStringify(v) { try { return JSON.stringify(v); } catch (e) { return String(v); } }

  // ---------------------------------------------------------------- frame metrics + fps limit
  var nativeRaf = window.requestAnimationFrame.bind(window);
  var fpsLimit = cfg.fpsLimit || 0; // 0 = auto
  if (fpsLimit > 0 && fpsLimit < 60) {
    var minInterval = 1000 / fpsLimit - 2;
    var lastFire = 0;
    window.requestAnimationFrame = function (cb) {
      var wrapped = function (t) {
        if (t - lastFire >= minInterval) { lastFire = t; cb(t); } else { nativeRaf(wrapped); }
      };
      return nativeRaf(wrapped);
    };
  }
  var metrics = { frames: 0, fps: 0, frameTime: 0, longFrames: 0, worstFrame: 0, samples: [], loadTimeMs: null };
  ff.metrics = metrics;
  var lastT = 0, windowStart = 0, windowFrames = 0;
  function tick(t) {
    if (lastT) {
      var dt = t - lastT;
      metrics.frameTime = dt;
      if (dt > 50) metrics.longFrames++;
      if (dt > metrics.worstFrame) metrics.worstFrame = dt;
      metrics.samples.push(dt);
      if (metrics.samples.length > 600) metrics.samples.shift();
    }
    lastT = t;
    metrics.frames++;
    windowFrames++;
    if (!windowStart) windowStart = t;
    if (t - windowStart >= 1000) {
      metrics.fps = Math.round(windowFrames * 1000 / (t - windowStart));
      windowFrames = 0; windowStart = t;
    }
    nativeRaf(tick);
  }
  nativeRaf(tick);
  window.addEventListener('load', function () {
    try {
      var nav = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
      metrics.loadTimeMs = nav ? Math.round(nav.loadEventStart || nav.duration) : Math.round(performance.now());
    } catch (e) { metrics.loadTimeMs = Math.round(performance.now()); }
  });
  try {
    if (window.PerformanceObserver && PerformanceObserver.supportedEntryTypes && PerformanceObserver.supportedEntryTypes.indexOf('longtask') >= 0) {
      metrics.longTasks = 0;
      new PerformanceObserver(function (list) { metrics.longTasks += list.getEntries().length; }).observe({ entryTypes: ['longtask'] });
    }
  } catch (e) { /* ignore */ }

  ff.snapshotMetrics = function () {
    var s = metrics.samples.slice(-120);
    var avg = s.length ? s.reduce(function (a, b) { return a + b; }, 0) / s.length : 0;
    var heap = null;
    if (performance.memory) heap = { usedMB: +(performance.memory.usedJSHeapSize / 1048576).toFixed(1), limitMB: +(performance.memory.jsHeapSizeLimit / 1048576).toFixed(1) };
    return {
      fps: metrics.fps, avgFrameMs: +avg.toFixed(2), avgFps: avg ? Math.round(1000 / avg) : 0,
      longFrames: metrics.longFrames, worstFrameMs: Math.round(metrics.worstFrame), longTasks: metrics.longTasks || 0,
      loadTimeMs: metrics.loadTimeMs, heap: heap, errors: ff.errors.length
    };
  };

  // ---------------------------------------------------------------- touch inspector
  var touches = [];
  ['touchstart', 'touchmove', 'touchend', 'pointerdown', 'pointermove', 'pointerup'].forEach(function (type) {
    window.addEventListener(type, function (e) {
      var p = e.touches && e.touches.length ? e.touches[0] : (e.changedTouches && e.changedTouches.length ? e.changedTouches[0] : e);
      touches.push({ type: type, x: Math.round(p.clientX || 0), y: Math.round(p.clientY || 0), id: e.pointerId || (p.identifier || 0), t: Math.round(performance.now()), synthetic: !e.isTrusted });
      if (touches.length > 300) touches.shift();
    }, { capture: true, passive: true });
  });
  ff.drainTouches = function () { var out = touches; touches = []; return JSON.stringify(out); };

  // ---------------------------------------------------------------- input injection
  var KEYMAP = {
    left: { key: 'ArrowLeft', code: 'ArrowLeft', keyCode: 37 },
    right: { key: 'ArrowRight', code: 'ArrowRight', keyCode: 39 },
    up: { key: 'ArrowUp', code: 'ArrowUp', keyCode: 38 },
    down: { key: 'ArrowDown', code: 'ArrowDown', keyCode: 40 },
    jump: { key: ' ', code: 'Space', keyCode: 32 },
    attack: { key: 'j', code: 'KeyJ', keyCode: 74 },
    dash: { key: 'Shift', code: 'ShiftLeft', keyCode: 16 },
    interact: { key: 'e', code: 'KeyE', keyCode: 69 },
    menu: { key: 'Escape', code: 'Escape', keyCode: 27 },
    restart: { key: 'r', code: 'KeyR', keyCode: 82 },
    a: { key: 'j', code: 'KeyJ', keyCode: 74 },
    b: { key: ' ', code: 'Space', keyCode: 32 },
    x: { key: 'Shift', code: 'ShiftLeft', keyCode: 16 },
    y: { key: 'e', code: 'KeyE', keyCode: 69 }
  };
  function keyInfo(k) {
    if (KEYMAP[k]) return KEYMAP[k];
    if (typeof k === 'string' && k.length === 1) {
      var up = k.toUpperCase();
      return { key: k, code: /[A-Z]/.test(up) ? 'Key' + up : (k === ' ' ? 'Space' : k), keyCode: up.charCodeAt(0) };
    }
    return { key: k, code: k, keyCode: 0 };
  }
  function keyTarget() {
    var a = document.activeElement;
    return a && a !== document.body && a !== document.documentElement ? a : document;
  }
  function fireKey(type, k) {
    var i = keyInfo(k);
    var ev = new KeyboardEvent(type, { key: i.key, code: i.code, bubbles: true, cancelable: true });
    try { Object.defineProperty(ev, 'keyCode', { get: function () { return i.keyCode; } }); Object.defineProperty(ev, 'which', { get: function () { return i.keyCode; } }); } catch (e) { /* ignore */ }
    keyTarget().dispatchEvent(ev);
  }
  function adapter() { return window.__foldForgeTest; }
  function point(x, y) {
    // Accepts normalised (0..1) or pixel coordinates.
    var px = x <= 1 && x >= 0 ? x * window.innerWidth : x;
    var py = y <= 1 && y >= 0 ? y * window.innerHeight : y;
    return { x: px, y: py };
  }
  function firePointer(type, x, y, id) {
    var el = document.elementFromPoint(x, y) || document.body;
    var opts = { clientX: x, clientY: y, bubbles: true, cancelable: true, pointerId: id || 1, pointerType: 'touch', isPrimary: true, button: 0, buttons: type === 'pointerup' ? 0 : 1 };
    try { el.dispatchEvent(new PointerEvent(type, opts)); } catch (e) { /* ignore */ }
    var touchType = { pointerdown: 'touchstart', pointermove: 'touchmove', pointerup: 'touchend' }[type];
    try {
      var t = new Touch({ identifier: id || 1, target: el, clientX: x, clientY: y, pageX: x, pageY: y });
      var list = type === 'pointerup' ? [] : [t];
      el.dispatchEvent(new TouchEvent(touchType, { touches: list, targetTouches: list, changedTouches: [t], bubbles: true, cancelable: true }));
    } catch (e) { /* Touch not supported */ }
    var mouseType = { pointerdown: 'mousedown', pointermove: 'mousemove', pointerup: 'mouseup' }[type];
    try { el.dispatchEvent(new MouseEvent(mouseType, opts)); } catch (e) { /* ignore */ }
    if (type === 'pointerup') { try { el.dispatchEvent(new MouseEvent('click', opts)); } catch (e) { /* ignore */ } }
    return el;
  }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  var Input = {
    keyDown: function (k) { fireKey('keydown', k); },
    keyUp: function (k) { fireKey('keyup', k); },
    pressButton: function (name, down) {
      var a = adapter();
      if (a && typeof a.pressButton === 'function') { a.pressButton(name, down !== false); return 'adapter'; }
      fireKey(down !== false ? 'keydown' : 'keyup', name);
      return 'keyboard';
    },
    hold: function (name, ms) {
      Input.pressButton(name, true);
      return sleep(ms || 200).then(function () { Input.pressButton(name, false); });
    },
    moveLeft: function (ms) { return Input.hold('left', ms || 400); },
    moveRight: function (ms) { return Input.hold('right', ms || 400); },
    moveUp: function (ms) { return Input.hold('up', ms || 400); },
    moveDown: function (ms) { return Input.hold('down', ms || 400); },
    jump: function () { return Input.hold('jump', 120); },
    attack: function () { return Input.hold('attack', 120); },
    dash: function () { return Input.hold('dash', 120); },
    interact: function () { return Input.hold('interact', 120); },
    joystick: function (x, y) {
      var a = adapter();
      if (a && typeof a.moveJoystick === 'function') { a.moveJoystick(x, y); return; }
      Input.pressButton('left', x < -0.3); Input.pressButton('right', x > 0.3);
      Input.pressButton('up', y < -0.3); Input.pressButton('down', y > 0.3);
    },
    tap: function (x, y) {
      var p = point(x, y);
      firePointer('pointerdown', p.x, p.y, 1);
      return sleep(60).then(function () { firePointer('pointerup', p.x, p.y, 1); });
    },
    drag: function (x1, y1, x2, y2, ms) {
      var a = point(x1, y1), b = point(x2, y2), steps = 8, dur = ms || 300;
      firePointer('pointerdown', a.x, a.y, 2);
      var i = 0;
      return new Promise(function (resolve) {
        function step() {
          i++;
          var px = a.x + (b.x - a.x) * i / steps, py = a.y + (b.y - a.y) * i / steps;
          firePointer('pointermove', px, py, 2);
          if (i < steps) setTimeout(step, dur / steps); else { firePointer('pointerup', b.x, b.y, 2); resolve(); }
        }
        setTimeout(step, dur / steps);
      });
    },
    restart: function () {
      var a = adapter();
      if (a && typeof a.restart === 'function') { a.restart(); return 'adapter'; }
      fireKey('keydown', 'restart'); fireKey('keyup', 'restart');
      return 'keyboard';
    }
  };
  window.FoldForgeTestInput = Input;
  ff.input = Input;

  // ---------------------------------------------------------------- state & observation
  function getPath(obj, path) {
    if (!path) return obj;
    return String(path).split('.').reduce(function (o, k) { return o == null ? undefined : o[k]; }, obj);
  }
  function readState() {
    var a = adapter();
    if (a && typeof a.getState === 'function') {
      try { return { source: 'adapter', state: JSON.parse(JSON.stringify(a.getState())) }; } catch (e) { return { source: 'adapter-error', error: String(e) }; }
    }
    var canvases = document.querySelectorAll('canvas');
    return {
      source: 'blackbox',
      state: {
        title: document.title, canvases: canvases.length,
        canvasSize: canvases[0] ? [canvases[0].width, canvases[0].height] : null,
        buttons: document.querySelectorAll('button,[role=button]').length,
        textLength: (document.body && document.body.innerText || '').length
      }
    };
  }
  ff.getState = function () { return JSON.stringify(readState()); };

  function mainCanvas() {
    var list = Array.prototype.slice.call(document.querySelectorAll('canvas'));
    list.sort(function (a, b) { return b.width * b.height - a.width * a.height; });
    return list[0] || null;
  }
  // Samples the largest canvas into a 24x24 luminance grid. Runs inside a rAF callback so that
  // WebGL drawing buffers are still valid even without preserveDrawingBuffer.
  function sampleCanvas() {
    return new Promise(function (resolve) {
      var c = mainCanvas();
      if (!c) { resolve(null); return; }
      nativeRaf(function () {
        try {
          var s = document.createElement('canvas');
          s.width = 24; s.height = 24;
          var g = s.getContext('2d', { willReadFrequently: true });
          g.drawImage(c, 0, 0, 24, 24);
          var d = g.getImageData(0, 0, 24, 24).data, lum = [];
          for (var i = 0; i < d.length; i += 4) lum.push(0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2]);
          resolve(lum);
        } catch (e) { resolve({ error: String(e) }); }
      });
    });
  }
  function lumStats(l) {
    var mean = l.reduce(function (a, b) { return a + b; }, 0) / l.length;
    var v = l.reduce(function (a, b) { return a + (b - mean) * (b - mean); }, 0) / l.length;
    return { mean: mean, variance: v };
  }
  function layoutIssues() {
    var vw = window.innerWidth, vh = window.innerHeight, issues = [];
    var els = Array.prototype.slice.call(document.querySelectorAll('button,[role=button],a,input,select,[data-hud],.hud,.btn,.control'));
    var rects = [];
    els.forEach(function (el) {
      var st = getComputedStyle(el);
      if (st.display === 'none' || st.visibility === 'hidden' || +st.opacity === 0) return;
      var r = el.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) return;
      var label = (el.id ? '#' + el.id : el.tagName.toLowerCase()) + (el.className && typeof el.className === 'string' ? '.' + el.className.split(' ')[0] : '');
      if (r.left < -1 || r.top < -1 || r.right > vw + 1 || r.bottom > vh + 1) {
        issues.push({ type: 'overflow', element: label, rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)], viewport: [vw, vh] });
      }
      var interactive = el.matches('button,[role=button],a,input,select,.btn,.control');
      if (interactive && (r.width < 32 || r.height < 32)) issues.push({ type: 'small-target', element: label, size: [Math.round(r.width), Math.round(r.height)] });
      if (interactive) rects.push({ el: el, r: r, label: label });
    });
    for (var i = 0; i < rects.length; i++) {
      for (var j = i + 1; j < rects.length; j++) {
        var a = rects[i], b = rects[j];
        if (a.el.contains(b.el) || b.el.contains(a.el)) continue;
        var ox = Math.min(a.r.right, b.r.right) - Math.max(a.r.left, b.r.left);
        var oy = Math.min(a.r.bottom, b.r.bottom) - Math.max(a.r.top, b.r.top);
        if (ox > 4 && oy > 4) issues.push({ type: 'overlap', element: a.label + ' ∩ ' + b.label });
      }
    }
    var docW = document.documentElement.scrollWidth;
    if (docW > vw + 2) issues.push({ type: 'horizontal-scroll', element: 'document', size: [docW, vw] });
    return issues;
  }
  ff.layoutIssues = function () { return JSON.stringify(layoutIssues()); };

  ff.probe = function () {
    var a = adapter();
    var c = mainCanvas();
    var gl = false;
    if (c) { try { gl = !!(c.getContext('webgl2') || c.getContext('webgl')); } catch (e) { gl = false; } }
    var st = readState();
    return JSON.stringify({
      adapter: !!a, adapterVersion: a && a.version || 0,
      actions: a && a.actions ? a.actions : ['left', 'right', 'up', 'down', 'jump', 'attack'],
      debug: a && a.debug ? Object.keys(a.debug) : [],
      customScenarios: a && typeof a.scenarios === 'function' ? a.scenarios().length : 0,
      canvas: !!c, webgl: gl, canvasSize: c ? [c.width, c.height] : null,
      buttons: document.querySelectorAll('button,[role=button]').length,
      stateKeys: st.state ? Object.keys(st.state) : [],
      viewport: [window.innerWidth, window.innerHeight], title: document.title,
      readyState: document.readyState, errors: ff.errors.length
    });
  };
  ff.customScenarios = function () {
    var a = adapter();
    return JSON.stringify(a && typeof a.scenarios === 'function' ? a.scenarios() : []);
  };

  // ---------------------------------------------------------------- QA scenario engine
  var runs = {};
  var runSeq = 0;
  function compare(op, a, b) {
    switch (op) {
      case 'gt': return a > b; case 'gte': return a >= b;
      case 'lt': return a < b; case 'lte': return a <= b;
      case 'eq': return a === b; case 'neq': return a !== b;
      default: return false;
    }
  }
  function describe(v) { return typeof v === 'object' ? safeStringify(v) : String(v); }

  function runStep(step, ctx) {
    var sev = step.severity === 'warn' ? 'WARN' : 'FAIL';
    function check(name, ok, message, evidence) {
      ctx.checks.push({ name: name, status: ok ? 'PASS' : sev, message: message, evidence: evidence || null });
    }
    if (step.action) {
      ctx.timeline.push({ t: Math.round(performance.now() - ctx.t0), action: step.action, args: step });
      switch (step.action) {
        case 'wait': return sleep(step.ms || 300);
        case 'press': return Input.hold(step.button, step.ms || 200);
        case 'key': fireKey('keydown', step.key); return sleep(step.ms || 100).then(function () { fireKey('keyup', step.key); });
        case 'tap': return Input.tap(step.x, step.y);
        case 'drag': return Input.drag(step.x1, step.y1, step.x2, step.y2, step.ms);
        case 'joystick': Input.joystick(step.x || 0, step.y || 0); return sleep(step.ms || 400).then(function () { Input.joystick(0, 0); });
        case 'restart': Input.restart(); return sleep(step.ms || 400);
        case 'debug': {
          var a = adapter();
          if (a && a.debug && typeof a.debug[step.call] === 'function') { a.debug[step.call].apply(a.debug, step.args || []); }
          else ctx.checks.push({ name: 'debug:' + step.call, status: 'WARN', message: 'Adapter debug hook "' + step.call + '" not available' });
          return sleep(step.ms || 100);
        }
        case 'snapshot': ctx.vars[step.as || 'snap'] = readState().state; return Promise.resolve();
        case 'canvasSnapshot': return sampleCanvas().then(function (l) { ctx.vars[step.as || 'canvas'] = l; });
        case 'sampleFps': {
          var startFrames = metrics.frames, startLong = metrics.longFrames, startT = performance.now();
          var startWorst = metrics.worstFrame; metrics.worstFrame = 0;
          return sleep(step.ms || 2000).then(function () {
            var dt = performance.now() - startT;
            ctx.vars[step.as || 'perf'] = {
              fps: Math.round((metrics.frames - startFrames) * 1000 / dt),
              longFrames: metrics.longFrames - startLong, worstFrameMs: Math.round(metrics.worstFrame)
            };
            metrics.worstFrame = Math.max(metrics.worstFrame, startWorst);
          });
        }
        default:
          ctx.checks.push({ name: 'action:' + step.action, status: 'WARN', message: 'Unknown action' });
          return Promise.resolve();
      }
    }
    var name = step.name || step.expect;
    switch (step.expect) {
      case 'noErrors': {
        var newErrors = ff.errors.slice(ctx.errorStart).filter(function (e) { return e.kind !== 'resource' || !step.ignoreResources; });
        check(name, newErrors.length === 0, newErrors.length ? newErrors.length + ' runtime error(s): ' + newErrors[0].message : 'No runtime errors', newErrors.slice(0, 5));
        break;
      }
      case 'stateAvailable': {
        var s = readState();
        check(name, s.source === 'adapter', s.source === 'adapter' ? 'Test adapter state available' : 'No window.__foldForgeTest adapter (black-box mode)', s.state);
        break;
      }
      case 'truthy': case 'falsy': {
        var v = getPath(readState().state, step.path);
        var ok = step.expect === 'truthy' ? !!v : !v;
        check(name, ok, step.path + ' = ' + describe(v));
        break;
      }
      case 'changed': {
        var before = getPath(ctx.vars[step.from || 'snap'], step.path);
        var now = getPath(readState().state, step.path);
        check(name, describe(before) !== describe(now), step.path + ': ' + describe(before) + ' → ' + describe(now));
        break;
      }
      case 'compare': {
        var cur = getPath(readState().state, step.path);
        var ref = step.from ? getPath(ctx.vars[step.from], step.refPath || step.path) : step.value;
        check(name, compare(step.op, cur, ref), step.path + ' (' + describe(cur) + ') ' + step.op + ' ' + describe(ref));
        break;
      }
      case 'canvasNotBlank': {
        return sampleCanvas().then(function (l) {
          if (!l) { check(name, false, 'No canvas element found'); return; }
          if (l.error) { check(name, false, 'Canvas could not be sampled: ' + l.error); return; }
          var st = lumStats(l);
          check(name, st.variance > (step.minVariance || 2), 'Canvas luminance mean=' + st.mean.toFixed(1) + ' variance=' + st.variance.toFixed(1) + (st.variance <= 2 ? ' (screen appears blank/frozen)' : ''));
        });
      }
      case 'canvasChanged': {
        return sampleCanvas().then(function (l) {
          var prev = ctx.vars[step.from || 'canvas'];
          if (!l || !prev || l.error || prev.error) { check(name, false, 'Canvas snapshot unavailable'); return; }
          var diff = 0;
          for (var i = 0; i < l.length; i++) diff += Math.abs(l[i] - prev[i]);
          diff /= l.length;
          check(name, diff > (step.minDiff || 0.5), 'Mean pixel change ' + diff.toFixed(2));
        });
      }
      case 'layout': {
        var issues = layoutIssues();
        var overflow = issues.filter(function (i) { return i.type === 'overflow' || i.type === 'horizontal-scroll'; });
        var overlap = issues.filter(function (i) { return i.type === 'overlap'; });
        var small = issues.filter(function (i) { return i.type === 'small-target'; });
        ctx.checks.push({ name: name + ':overflow', status: overflow.length ? 'FAIL' : 'PASS', message: overflow.length ? overflow.length + ' element(s) outside viewport ' + window.innerWidth + 'x' + window.innerHeight + ': ' + overflow.map(function (o) { return o.element; }).join(', ') : 'All UI inside viewport ' + window.innerWidth + 'x' + window.innerHeight, evidence: overflow });
        ctx.checks.push({ name: name + ':overlap', status: overlap.length ? 'FAIL' : 'PASS', message: overlap.length ? 'Overlapping controls: ' + overlap.map(function (o) { return o.element; }).join(', ') : 'No overlapping controls', evidence: overlap });
        if (small.length) ctx.checks.push({ name: name + ':touch-targets', status: 'WARN', message: small.length + ' touch target(s) smaller than 32px', evidence: small });
        break;
      }
      case 'fps': {
        var p = ctx.vars[step.from || 'perf'] || { fps: metrics.fps };
        var min = step.min || 45;
        ctx.checks.push({ name: name, status: p.fps >= min ? 'PASS' : 'WARN', message: 'Average FPS ' + p.fps + ' (threshold ' + min + ')', evidence: p });
        break;
      }
      case 'longFrames': {
        var q = ctx.vars[step.from || 'perf'] || {};
        var maxMs = step.maxMs || 50;
        var bad = (q.worstFrameMs || 0) > maxMs;
        ctx.checks.push({ name: name, status: bad ? 'WARN' : 'PASS', message: 'Worst frame ' + (q.worstFrameMs || 0) + 'ms, long frames ' + (q.longFrames || 0), evidence: q });
        break;
      }
      default:
        ctx.checks.push({ name: 'expect:' + step.expect, status: 'WARN', message: 'Unknown expectation' });
    }
    return Promise.resolve();
  }

  function runScenario(scn) {
    var ctx = { checks: [], vars: {}, timeline: [], t0: performance.now(), errorStart: ff.errors.length };
    var steps = scn.steps || [];
    var i = 0;
    return new Promise(function (resolve) {
      function next() {
        if (i >= steps.length) { resolve(ctx); return; }
        var step = steps[i++];
        var p;
        try { p = runStep(step, ctx); } catch (e) {
          ctx.checks.push({ name: 'step ' + i, status: 'FAIL', message: 'Step threw: ' + e });
          p = Promise.resolve();
        }
        Promise.resolve(p).then(next, function (e) {
          ctx.checks.push({ name: 'step ' + i, status: 'FAIL', message: 'Step failed: ' + e });
          next();
        });
      }
      next();
    }).then(function (ctx) {
      var status = 'PASS';
      ctx.checks.forEach(function (c) {
        if (c.status === 'FAIL') status = 'FAIL';
        else if (c.status === 'WARN' && status === 'PASS') status = 'WARN';
      });
      return {
        id: scn.id, name: scn.name, category: scn.category, status: status, checks: ctx.checks,
        errors: ff.errors.slice(ctx.errorStart), timeline: ctx.timeline,
        durationMs: Math.round(performance.now() - ctx.t0), metrics: ff.snapshotMetrics()
      };
    });
  }

  ff.qa = {
    start: function (json) {
      var scn = typeof json === 'string' ? JSON.parse(json) : json;
      var id = 'run' + (++runSeq);
      runs[id] = null;
      runScenario(scn).then(function (r) { runs[id] = r; }, function (e) {
        runs[id] = { id: scn.id, name: scn.name, category: scn.category, status: 'FAIL', checks: [{ name: 'engine', status: 'FAIL', message: String(e) }], errors: [], timeline: [], durationMs: 0 };
      });
      return id;
    },
    poll: function (id) {
      var r = runs[id];
      if (r === undefined) return JSON.stringify({ done: true, missing: true });
      if (r === null) return JSON.stringify({ done: false });
      delete runs[id];
      return JSON.stringify({ done: true, result: r });
    }
  };
})();
