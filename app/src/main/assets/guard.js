(function () {
  'use strict';
  if (window.__lkGuardInstalled) return;
  window.__lkGuardInstalled = true;

  var ALLOW_SUFFIX = ['lk21official.dev', 'lk21official.cc', 'lk21.de', 'nontondrama.vip', 'assetsy.de', 'dadadidi.de', 'd21.team', 'videonode.de'];
  var ALLOW_KEYWORD = ['lk21', 'nontondrama', 'layarkaca'];
  var BLOCK_SUFFIX = [
    'yellowishgather.com', 'doubleclick.net', 'googlesyndication.com', 'googleadservices.com',
    'popads.net', 'popcash.net', 'propellerads.com', 'adsterra.com', 'exoclick.com',
    'juicyads.com', 'trafficjunky.com', 'hilltopads.net', 'clickadu.com', 'adcash.com',
    'onclickads.net', 'mgid.com'
  ];

  function hostOf(u) {
    try { return new URL(u, location.href).hostname.toLowerCase(); } catch (e) { return ''; }
  }
  function match(h, s) {
    return h === s || (h.length > s.length && h.slice(-(s.length + 1)) === '.' + s);
  }
  function isBlocked(u) {
    var h = hostOf(u);
    if (!h) return false;
    return BLOCK_SUFFIX.some(function (s) { return match(h, s); });
  }
  function isAllowed(u) {
    var h = hostOf(u);
    if (!h) return false;
    if (isBlocked(u)) return false;
    if (ALLOW_SUFFIX.some(function (s) { return match(h, s); })) return true;
    return ALLOW_KEYWORD.some(function (k) { return h.indexOf(k) !== -1; });
  }

  // 1) window.open: jangan pernah membuka jendela baru. Hanya halaman situs di jendela teratas yang dinavigasi di tempat.
  function fakeWindow() {
    return {
      closed: true,
      focus: function () {},
      blur: function () {},
      close: function () {},
      postMessage: function () {},
      document: { write: function () {}, writeln: function () {}, close: function () {}, open: function () {} },
      location: { href: '', assign: function () {}, replace: function () {} }
    };
  }
  window.open = function (url) {
    try {
      if (window === window.top && url && isAllowed(url)) {
        location.href = new URL(url, location.href).href;
      }
    } catch (e) {}
    return fakeWindow();
  };

  // 2) Klik pada tautan: blokir tautan iklan / target=_blank eksternal, ubah _blank internal menjadi _self.
  document.addEventListener('click', function (e) {
    var el = e.target;
    var a = el && el.closest ? el.closest('a') : null;
    if (!a) return;
    var href = a.href || '';
    if (isBlocked(href) || (a.target === '_blank' && href && !isAllowed(href))) {
      e.preventDefault();
      e.stopImmediatePropagation();
      return;
    }
    if (a.target === '_blank' && href && isAllowed(href)) {
      a.target = '_self';
    }
  }, true);

  // 3) Overlay iklan (a#uyeouyeo) dimatikan: klik langsung diteruskan ke iframe player di bawahnya.
  var CSS = 'a#uyeouyeo{display:none!important;pointer-events:none!important;visibility:hidden!important}' +
    BLOCK_SUFFIX.map(function (s) { return 'a[href*="' + s + '"]'; }).join(',') +
    '{display:none!important;pointer-events:none!important}';

  function injectStyle() {
    if (document.getElementById('__lk_guard_style')) return true;
    var root = document.head || document.documentElement;
    if (!root) return false;
    var s = document.createElement('style');
    s.id = '__lk_guard_style';
    s.textContent = CSS;
    root.appendChild(s);
    return true;
  }
  if (!injectStyle()) {
    var t = setInterval(function () { if (injectStyle()) clearInterval(t); }, 20);
  }
  document.addEventListener('DOMContentLoaded', injectStyle);

  function neutralize() {
    try {
      var list = document.querySelectorAll('a#uyeouyeo');
      for (var i = 0; i < list.length; i++) {
        list[i].removeAttribute('href');
        list[i].removeAttribute('target');
      }
    } catch (e) {}
  }

  var pending = false;
  function schedule() {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; neutralize(); }, 200);
  }
  function startObserver() {
    if (!document.documentElement) { setTimeout(startObserver, 20); return; }
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    neutralize();
  }
  startObserver();

  // 4) Laporan status pemutaran video ke aplikasi (layar tetap menyala, PiP, episode berikutnya).
  var frameId = Math.random().toString(36).slice(2) + Date.now().toString(36);
  function bridge() { try { return window.LkBridge || null; } catch (e) { return null; } }
  function report(playing) {
    var b = bridge();
    if (b && b.onPlayback) { try { b.onPlayback(frameId, !!playing); } catch (e) {} }
  }
  function anyPlaying() {
    var v = document.getElementsByTagName('video');
    for (var i = 0; i < v.length; i++) {
      if (!v[i].paused && !v[i].ended && v[i].readyState > 2) return true;
    }
    return false;
  }
  function isVideo(e) { return e.target && e.target.tagName === 'VIDEO'; }
  document.addEventListener('playing', function (e) { if (isVideo(e)) report(true); }, true);
  document.addEventListener('play', function (e) { if (isVideo(e)) report(true); }, true);
  document.addEventListener('pause', function (e) { if (isVideo(e)) report(anyPlaying()); }, true);
  document.addEventListener('emptied', function (e) { if (isVideo(e)) report(anyPlaying()); }, true);
  document.addEventListener('ended', function (e) {
    if (!isVideo(e)) return;
    report(anyPlaying());
    var b = bridge();
    // Hanya video panjang (>10 menit) yang dianggap film/episode, bukan iklan.
    if (b && b.onEnded && e.target.duration > 600) { try { b.onEnded(); } catch (x) {} }
  }, true);
  window.addEventListener('pagehide', function () { report(false); });
})();
