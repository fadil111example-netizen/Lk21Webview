(function () {
  'use strict';
  if (window !== window.top) return;
  if (window.__lkHydraxInstalled) return;
  window.__lkHydraxInstalled = true;

  var userPicked = false;
  var curPath = location.pathname;
  var lastKey = '';
  var tries = 0;
  var MAX_TRIES = 4;

  function isHydrax(u) {
    return typeof u === 'string' && /\/hydrax\//i.test(u);
  }

  // Jika pengguna sendiri memilih server (klik asli), jangan dipaksa kembali ke hydrax.
  document.addEventListener('click', function (e) {
    if (!e.isTrusted) return;
    var t = e.target;
    if (t && t.closest && t.closest('#player-list')) userPicked = true;
  }, true);
  document.addEventListener('change', function (e) {
    if (!e.isTrusted) return;
    if (e.target && e.target.id === 'player-select') userPicked = true;
  }, true);

  // Klik sintetis pada tautan server tanpa pernah menavigasi halaman utama.
  function synthClick(el) {
    var guard = function (ev) {
      if (ev.target === el || el.contains(ev.target)) ev.preventDefault();
    };
    window.addEventListener('click', guard, true);
    try {
      el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
    } catch (e) {}
    window.removeEventListener('click', guard, true);
  }

  function setActive(link) {
    var list = document.getElementById('player-list');
    if (!list) return;
    var items = list.querySelectorAll('a');
    for (var i = 0; i < items.length; i++) items[i].classList.remove('active');
    link.classList.add('active');
  }

  function ensureFrame(frame) {
    var allow = frame.getAttribute('allow') || '';
    if (allow.indexOf('fullscreen') < 0) {
      frame.setAttribute('allow', (allow ? allow + '; ' : '') + 'autoplay; fullscreen; picture-in-picture');
    }
    if (frame.getAttribute('allowfullscreen') !== 'true') {
      frame.setAttribute('allowfullscreen', 'true');
    }
  }

  function apply() {
    var frame = document.getElementById('main-player');
    if (!frame) return;
    var list = document.getElementById('player-list');
    var sel = document.getElementById('player-select');

    var link = list ? list.querySelector('a[data-server="hydrax"]') : null;
    var opt = null;
    if (sel) {
      for (var i = 0; i < sel.options.length; i++) {
        if (sel.options[i].getAttribute('data-server') === 'hydrax') { opt = sel.options[i]; break; }
      }
    }
    var url = link ? (link.getAttribute('data-url') || link.href) : (opt ? opt.value : '');
    if (!url) return; // hydrax tidak tersedia pada halaman ini

    if (location.pathname !== curPath) {
      curPath = location.pathname;
      userPicked = false;
      lastKey = '';
      tries = 0;
    }
    var key = location.pathname + '|' + url;
    if (key !== lastKey) { lastKey = key; tries = 0; }

    ensureFrame(frame);
    if (userPicked) return;
    if (isHydrax(frame.getAttribute('src') || frame.src)) return;
    if (tries >= MAX_TRIES) return;
    tries++;

    if (link) {
      synthClick(link);
    } else if (opt) {
      sel.value = opt.value;
      sel.dispatchEvent(new Event('change', { bubbles: true }));
    }

    // Cadangan: jika handler situs belum terpasang, arahkan iframe langsung ke server hydrax.
    setTimeout(function () {
      if (userPicked) return;
      var cur = frame.getAttribute('src') || '';
      if (!isHydrax(cur)) {
        frame.setAttribute('src', url);
        if (link) setActive(link);
        if (sel && opt) sel.value = opt.value;
      }
    }, 700);
  }

  document.addEventListener('DOMContentLoaded', apply);
  setInterval(apply, 700);
})();
