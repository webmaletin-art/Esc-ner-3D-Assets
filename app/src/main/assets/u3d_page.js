/*
 * Universal 3D Asset Inspector — script de página (se inyecta al inicio del documento).
 * - Detecta indicios de contenido 3D (model-viewer, enlaces/recursos con extensiones 3D, librerías, canvas WebGL con dibujo real).
 * - Opcionalmente observa buffers de WebGL para la "captura de geometría" (solo cuando el usuario pulsa Copiar/Replicar).
 * - Sólo envía DATOS a la app mediante el canal WebMessage "U3DBridge"; no recibe ni ejecuta órdenes de la página.
 * - No lee cookies, almacenamiento, formularios ni contraseñas.
 * Todas las intervenciones están envueltas en try/catch y siempre llaman a la función original.
 */
(function () {
  'use strict';
  if (window.__u3d) return;
  var CFG = { capture: __U3D_CAPTURE__, maxBytes: __U3D_MAXBYTES__ };
  var RE3D = /\.(glb|gltf|obj|fbx|usdz|stl|ply|dae|3ds|splat|ksplat|spz|3mf|vrm)(?:$|[?#])/i;
  var U = (window.__u3d = { posts: 0, bytes: 0, truncated: false, ctxCount: 0, version: 1 });

  function listen() {
    try {
      if (window.U3DBridge && !window.U3DBridge.__u3dListen) {
        window.U3DBridge.onmessage = function (ev) {
          var d = ev && ev.data;
          if (typeof d === 'string' && d.indexOf('capture:') === 0 && U.requestCapture) U.requestCapture(d.slice(8));
        };
        window.U3DBridge.__u3dListen = true;
      }
    } catch (e) {}
  }
  function post(o) {
    try { listen(); if (window.U3DBridge) window.U3DBridge.postMessage(JSON.stringify(o)); } catch (e) {}
  }
  listen();
  function wrap(obj, name, before, after) {
    var orig = obj[name];
    if (typeof orig !== 'function') return;
    obj[name] = function () {
      if (before) { try { before.apply(this, arguments); } catch (e) {} }
      var r = orig.apply(this, arguments);
      if (after) { try { after.call(this, r, arguments); } catch (e) {} }
      return r;
    };
  }

  var contexts = [];   // estados de contexto WebGL
  var bufIds = new WeakMap(), bufRecs = {}, nextBuf = 1;
  var texIds = new WeakMap(), texRecs = {}, nextTex = 1, texCount = 0;

  function instrument(gl, canvas) {
    if (gl.__u3d) return;
    try { Object.defineProperty(gl, '__u3d', { value: true }); } catch (e) { return; }
    U.ctxCount++;
    var st = { canvas: canvas, gl: gl, draws: 0, tris: 0, arrayBuf: null, vaos: new WeakMap(), def: { attribs: {}, element: null }, cur: null,
      program: null, progAttrs: new WeakMap(), uniNames: new WeakMap(), mats: {}, units: [], active: 0, rec: [], keys: {}, gl2: typeof WebGL2RenderingContext !== 'undefined' && gl instanceof WebGL2RenderingContext };
    st.cur = st.def;
    contexts.push(st);

    wrap(gl, 'createBuffer', null, function (b) { if (b) bufIds.set(b, 'b' + (nextBuf++)); });
    wrap(gl, 'bindBuffer', function (target, b) {
      if (target === 34962) st.arrayBuf = b; else if (target === 34963) st.cur.element = b;
    });
    function targetBuf(target) { return target === 34962 ? st.arrayBuf : target === 34963 ? st.cur.element : null; }
    wrap(gl, 'bufferData', function (target, data) {
      if (!CFG.capture) return;
      var b = targetBuf(target); if (!b) return;
      var id = bufIds.get(b); if (!id) return;
      var rec = bufRecs[id] || (bufRecs[id] = { data: null, len: 0, uploads: 0, dynamic: false, target: target });
      rec.uploads++;
      if (rec.data) { U.bytes -= rec.data.length; rec.data = null; }
      if (rec.dynamic || rec.uploads > 12) { rec.dynamic = true; return; }
      var u8 = null;
      if (data instanceof ArrayBuffer) u8 = new Uint8Array(data);
      else if (data && data.buffer instanceof ArrayBuffer && typeof data.byteLength === 'number') u8 = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
      if (!u8) { rec.len = typeof data === 'number' ? data : 0; return; }
      if (U.bytes + u8.length > CFG.maxBytes) { U.truncated = true; return; }
      rec.data = u8.slice(); rec.len = u8.length; U.bytes += u8.length;
    });
    wrap(gl, 'bufferSubData', function (target, off, data) {
      if (!CFG.capture) return;
      var b = targetBuf(target); var id = b && bufIds.get(b); var rec = id && bufRecs[id];
      if (!rec || !rec.data) return;
      var u8 = data instanceof ArrayBuffer ? new Uint8Array(data) : (data && data.buffer ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : null);
      if (u8 && off + u8.length <= rec.data.length) rec.data.set(u8, off);
    });
    wrap(gl, 'deleteBuffer', function (b) {
      var id = b && bufIds.get(b); var rec = id && bufRecs[id];
      if (rec && rec.data) U.bytes -= rec.data.length;
      if (id) delete bufRecs[id];
    });

    function vaoState(v) { if (!v) return st.def; var s = st.vaos.get(v); if (!s) { s = { attribs: {}, element: null }; st.vaos.set(v, s); } return s; }
    if (st.gl2) {
      wrap(gl, 'bindVertexArray', function (v) { st.cur = vaoState(v); });
      wrap(gl, 'vertexAttribIPointer', function (i, size, type, stride, offset) { st.cur.attribs[i] = { size: size, type: type, norm: false, stride: stride, offset: offset, buf: st.arrayBuf, en: (st.cur.attribs[i] || {}).en, int: true }; });
    } else {
      wrap(gl, 'getExtension', null, function (ext, args) {
        if (ext && args[0] === 'OES_vertex_array_object' && !ext.__u3d) {
          try { Object.defineProperty(ext, '__u3d', { value: true }); } catch (e) {}
          wrap(ext, 'bindVertexArrayOES', function (v) { st.cur = vaoState(v); });
        }
      });
    }
    wrap(gl, 'vertexAttribPointer', function (i, size, type, norm, stride, offset) {
      st.cur.attribs[i] = { size: size, type: type, norm: !!norm, stride: stride, offset: offset, buf: st.arrayBuf, en: (st.cur.attribs[i] || {}).en };
    });
    wrap(gl, 'enableVertexAttribArray', function (i) { var a = st.cur.attribs[i] || (st.cur.attribs[i] = {}); a.en = true; });
    wrap(gl, 'disableVertexAttribArray', function (i) { var a = st.cur.attribs[i] || (st.cur.attribs[i] = {}); a.en = false; });

    wrap(gl, 'getAttribLocation', null, function (loc, args) { var m = st.progAttrs.get(args[0]); if (!m) st.progAttrs.set(args[0], (m = {})); if (loc >= 0) m[loc] = String(args[1]); });
    wrap(gl, 'bindAttribLocation', function (p, loc, name) { var m = st.progAttrs.get(p); if (!m) st.progAttrs.set(p, (m = {})); m[loc] = String(name); });
    wrap(gl, 'useProgram', function (p) { st.program = p; });
    wrap(gl, 'getUniformLocation', null, function (loc, args) { if (loc) st.uniNames.set(loc, String(args[1])); });
    wrap(gl, 'uniformMatrix4fv', function (loc, tr, v) {
      if (!CFG.capture || !loc || !v || v.length < 16) return;
      var n = st.uniNames.get(loc); if (!n) return;
      if (!(n in st.mats) && Object.keys(st.mats).length >= 10) return;
      st.mats[n] = Array.prototype.slice.call(v, 0, 16);
    });

    wrap(gl, 'activeTexture', function (u) { st.active = u - 33984; });
    wrap(gl, 'createTexture', null, function (t) { if (t) texIds.set(t, 't' + (nextTex++)); });
    wrap(gl, 'bindTexture', function (target, t) { if (target === 3553) st.units[st.active] = t; });
    wrap(gl, 'texImage2D', function () {
      if (!CFG.capture) return;
      var a = arguments; if (a[0] !== 3553 || a[1] !== 0) return;
      var t = st.units[st.active]; var id = t && texIds.get(t); if (!id) return;
      var src = null, w = 0, h = 0;
      if (a.length >= 9 && a[8] && a[8].buffer) {
        w = a[3]; h = a[4];
        if ((a[6] === 6408 || a[6] === 6407) && a[7] === 5121 && w * h <= 4194304) src = { px: new Uint8Array(a[8].buffer.slice(a[8].byteOffset, a[8].byteOffset + a[8].byteLength)), fmt: a[6] };
      } else if (a.length >= 6 && a[5] && typeof a[5] === 'object') { src = { el: a[5] }; }
      if (!texRecs[id] && texCount >= 80) return;
      if (!texRecs[id]) texCount++;
      texRecs[id] = { src: src, w: w, h: h };
    });

    function countTris(mode, count) { return mode === 4 ? count / 3 : (mode === 5 || mode === 6) ? Math.max(count - 2, 0) : 0; }
    function onDraw(kind, mode, first, count, type, offset) {
      st.draws++; U.drawsTotal = (U.drawsTotal || 0) + 1;
      var tr = countTris(mode, count); st.tris += tr;
      if (!CFG.capture || tr < 4 || mode < 4) return;
      var v = st.cur; var parts = [kind, mode, first, count, type, offset, v.element ? bufIds.get(v.element) : ''];
      var attrs = [];
      var names = st.progAttrs.get(st.program) || {};
      for (var k in v.attribs) {
        var a = v.attribs[k];
        if (!a || !a.en || !a.buf || a.int) continue;
        var bid = bufIds.get(a.buf); var rec = bid && bufRecs[bid];
        if (!rec || !rec.data) continue;
        attrs.push({ name: names[k] || '', loc: +k, size: a.size, type: a.type, norm: a.norm, stride: a.stride, offset: a.offset, buffer: bid });
        parts.push(k + ':' + bid + ':' + a.offset + ':' + a.stride + ':' + a.size + ':' + a.type);
      }
      var key = parts.join('|');
      if (st.keys[key] || attrs.length === 0) return;
      if (st.rec.length >= 300) return;
      var d = { mode: mode, count: count, attribs: attrs };
      if (kind === 'e') {
        var eb = v.element && bufIds.get(v.element); var er = eb && bufRecs[eb];
        if (!er || !er.data) return;
        d.indexType = type; d.indexOffset = offset; d.elementBuffer = eb;
      } else d.first = first;
      var tex = [];
      for (var u = 0; u < 8; u++) { var t = st.units[u]; var tid = t && texIds.get(t); if (tid && texRecs[tid]) tex.push(tid); }
      if (tex.length) d.textures = tex;
      var mats = [];
      for (var nme in st.mats) mats.push({ name: nme, value: st.mats[nme] });
      if (mats.length) d.uniforms = mats;
      st.keys[key] = 1; st.rec.push(d);
    }
    wrap(gl, 'drawArrays', function (mode, first, count) { onDraw('a', mode, first, count, 0, 0); });
    wrap(gl, 'drawElements', function (mode, count, type, offset) { onDraw('e', mode, 0, count, type, offset); });
    if (st.gl2) {
      wrap(gl, 'drawArraysInstanced', function (mode, first, count) { onDraw('a', mode, first, count, 0, 0); });
      wrap(gl, 'drawElementsInstanced', function (mode, count, type, offset) { onDraw('e', mode, 0, count, type, offset); });
      wrap(gl, 'drawRangeElements', function (mode, s, e, count, type, offset) { onDraw('e', mode, 0, count, type, offset); });
    }
  }

  // ---- Intercepta la creación de contextos WebGL ----
  try {
    var origGet = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (type) {
      var ctx = origGet.apply(this, arguments);
      try { if (ctx && typeof type === 'string' && /webgl/i.test(type)) instrument(ctx, this); } catch (e) {}
      return ctx;
    };
  } catch (e) {}

  // ---- Detección ----
  function abs(u) { if (!u) return null; try { return new URL(u, location.href).href; } catch (e) { return null; } }
  function scan() {
    var o = { v: 1, url: location.href, title: (document.title || '').slice(0, 200), viewers: [], links: [], iframes: [], libs: [], canvases: [], net: [] };
    var seen = {};
    function addLink(u) { var a = u && abs(u); if (a && RE3D.test(a) && !seen[a] && o.links.length < 120) { seen[a] = 1; o.links.push(a); } }
    try {
      var mv = document.querySelectorAll('model-viewer');
      for (var i = 0; i < mv.length && i < 10; i++) {
        var e = mv[i];
        o.viewers.push({ type: 'model-viewer', src: abs(e.getAttribute('src') || ''), iosSrc: abs(e.getAttribute('ios-src') || ''), poster: abs(e.getAttribute('poster') || '') });
      }
      var els = document.querySelectorAll('a[href],source[src],object[data],embed[src],link[href],[data-src],[data-model],[data-glb],[data-gltf],[data-model-url]');
      for (var j = 0; j < els.length && j < 2000; j++) {
        var el = els[j];
        addLink(el.getAttribute('href')); addLink(el.getAttribute('src')); addLink(el.getAttribute('data'));
        addLink(el.getAttribute('data-src')); addLink(el.getAttribute('data-model')); addLink(el.getAttribute('data-glb')); addLink(el.getAttribute('data-gltf')); addLink(el.getAttribute('data-model-url'));
      }
      var fr = document.querySelectorAll('iframe[src]'); var xo = false;
      for (var k = 0; k < fr.length && o.iframes.length < 20; k++) {
        var s = abs(fr[k].getAttribute('src')); if (!s) continue; o.iframes.push(s);
        try { if (new URL(s).origin !== location.origin) xo = true; } catch (e2) {}
      }
      o.xoFrames = xo;
    } catch (e) {}
    try {
      var L = o.libs;
      if (window.THREE || window.__THREE__) L.push('three');
      if (window.BABYLON) L.push('babylon');
      if (window.AFRAME) L.push('aframe');
      if (window.pc && window.pc.Application) L.push('playcanvas');
      if (window.Cesium) L.push('cesium');
      if (window.Sketchfab) L.push('sketchfab');
      if (window.createUnityInstance) L.push('unity');
      if (window.customElements && window.customElements.get('model-viewer')) L.push('model-viewer');
      var scripts = document.scripts;
      for (var q = 0; q < scripts.length && q < 300; q++) {
        var ss = scripts[q].src || '';
        if (/three(\.min|\.module)?\.js|three@|\/three\//i.test(ss)) L.push('three');
        else if (/babylon(\.max|\.min)?\.js|babylonjs/i.test(ss)) L.push('babylon');
        else if (/model-viewer(\.min)?\.(m?js)|@google\/model-viewer/i.test(ss)) L.push('model-viewer');
        else if (/aframe(\.min)?\.js/i.test(ss)) L.push('aframe');
        else if (/playcanvas/i.test(ss)) L.push('playcanvas');
      }
    } catch (e) {}
    try {
      for (var c = 0; c < contexts.length && c < 12; c++) {
        var cs = contexts[c], cv = cs.canvas, r = cv && cv.getBoundingClientRect ? cv.getBoundingClientRect() : { width: 0, height: 0 };
        var vis = !!(cv && cv.isConnected && r.width > 20 && r.height > 20);
        o.canvases.push({ w: cv ? cv.width : 0, h: cv ? cv.height : 0, gl: cs.gl2 ? 'webgl2' : 'webgl', draws: cs.draws, tris: Math.round(cs.tris), visible: vis });
      }
      var all = document.querySelectorAll('canvas'); 
      if (all.length > contexts.length) {
        for (var z = 0; z < all.length && o.canvases.length < 12; z++) {
          var known = false; for (var y = 0; y < contexts.length; y++) if (contexts[y].canvas === all[z]) known = true;
          if (!known) o.canvases.push({ w: all[z].width, h: all[z].height, gl: null, draws: 0, tris: 0, visible: true });
        }
      }
    } catch (e) {}
    try {
      var ents = performance.getEntriesByType('resource');
      for (var n = 0; n < ents.length && o.net.length < 100; n++) { var nm = ents[n].name; if (RE3D.test(nm)) o.net.push(nm); }
    } catch (e) {}
    o.libs = o.libs.filter(function (v, i, a) { return a.indexOf(v) === i; });
    var recDraws = 0; for (var m = 0; m < contexts.length; m++) recDraws += contexts[m].rec.length;
    o.capture = { available: !!CFG.capture && contexts.length > 0, draws: recDraws, bufferBytes: U.bytes, truncated: U.truncated };
    return o;
  }

  var lastJson = '', timer = null, MAXPOSTS = 30;
  function publish() {
    timer = null;
    if (U.posts >= MAXPOSTS) return;
    var s = JSON.stringify(scan());
    if (s === lastJson) return;
    lastJson = s; U.posts++;
    post({ t: 'sig', d: s });
  }
  function schedule(ms) { if (timer || U.posts >= MAXPOSTS) return; timer = setTimeout(publish, ms); }
  U.scan = scan;
  try {
    document.addEventListener('DOMContentLoaded', function () { schedule(700); });
    window.addEventListener('load', function () { schedule(1200); });
    var mo = new MutationObserver(function () { schedule(2000); });
    var startMo = function () { if (document.documentElement) mo.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'href', 'data-src'] }); };
    if (document.documentElement) startMo(); else document.addEventListener('readystatechange', startMo, { once: true });
    var ticks = 0; var iv = setInterval(function () { ticks++; schedule(50); if (ticks > 14) clearInterval(iv); }, 4000);
  } catch (e) {}

  // ---- Captura bajo demanda ----
  function b64(u8) {
    var CH = 0x8000, parts = [];
    for (var i = 0; i < u8.length; i += CH) parts.push(String.fromCharCode.apply(null, u8.subarray(i, i + CH)));
    return btoa(parts.join(''));
  }
  function texToPng(rec) {
    try {
      var cv = document.createElement('canvas'), ctx;
      if (rec.src && rec.src.px) {
        var w = rec.w, h = rec.h; cv.width = w; cv.height = h; ctx = cv.getContext('2d');
        var img = ctx.createImageData(w, h), d = img.data, px = rec.src.px, n = w * h;
        if (rec.src.fmt === 6408) d.set(px.subarray(0, n * 4)); else for (var i = 0; i < n; i++) { d[i * 4] = px[i * 3]; d[i * 4 + 1] = px[i * 3 + 1]; d[i * 4 + 2] = px[i * 3 + 2]; d[i * 4 + 3] = 255; }
        ctx.putImageData(img, 0, 0);
      } else if (rec.src && rec.src.el) {
        var el = rec.src.el; var sw = el.naturalWidth || el.videoWidth || el.width, sh = el.naturalHeight || el.videoHeight || el.height;
        if (!sw || !sh) return { reason: 'vacía' };
        var sc = Math.min(1, 2048 / Math.max(sw, sh)); cv.width = Math.max(1, Math.round(sw * sc)); cv.height = Math.max(1, Math.round(sh * sc));
        ctx = cv.getContext('2d'); ctx.drawImage(el, 0, 0, cv.width, cv.height);
      } else return { reason: 'sin datos' };
      var url = cv.toDataURL('image/png');
      return { w: cv.width, h: cv.height, png: url.slice(url.indexOf(',') + 1) };
    } catch (e) { return { reason: /security/i.test(String(e)) ? 'origen cruzado (CORS)' : 'no legible' }; }
  }
  U.requestCapture = function (id) {
    var step = 0;
    var out = { v: 1, truncated: U.truncated, buffers: {}, draws: [], textures: {} };
    var draws = []; contexts.forEach(function (c) { draws = draws.concat(c.rec); });
    out.draws = draws;
    var need = {}, needTex = {};
    draws.forEach(function (d) {
      d.attribs.forEach(function (a) { need[a.buffer] = 1; });
      if (d.elementBuffer) need[d.elementBuffer] = 1;
      (d.textures || []).forEach(function (t) { needTex[t] = 1; });
    });
    var ids = Object.keys(need), tids = Object.keys(needTex);
    var i = 0, j = 0;
    function next() {
      try {
        if (i < ids.length) { var r = bufRecs[ids[i]]; if (r && r.data) out.buffers[ids[i]] = { len: r.len, data: b64(r.data) }; i++; return setTimeout(next, 0); }
        if (j < tids.length) { var tr = texRecs[tids[j]]; if (tr) out.textures[tids[j]] = texToPng(tr); j++; return setTimeout(next, 0); }
        var json = JSON.stringify(out);
        var CH = 180000, n = Math.max(1, Math.ceil(json.length / CH));
        var k = 0;
        (function send() {
          post({ t: 'cap', id: id, i: k, n: n, d: json.substr(k * CH, CH) });
          k++; if (k < n) setTimeout(send, 0);
        })();
      } catch (e) { post({ t: 'cap', id: id, i: 0, n: 1, d: '{"v":1,"error":"' + String(e).replace(/"/g, "'").slice(0, 120) + '","draws":[],"buffers":{}}' }); }
    }
    if (!CFG.capture) { post({ t: 'cap', id: id, i: 0, n: 1, d: '{"v":1,"error":"captura desactivada","draws":[],"buffers":{}}' }); return; }
    next();
  };
})();
