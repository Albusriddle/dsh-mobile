/* ============================================================
 * 深海女仆工坊 · 移动外壳交互层（v2）
 * 关键布局用「内联 + !important」直接写到元素上——官方样式用了高特异性
 * 选择器，外部样式表会被压过；内联 important 是唯一稳的做法。
 * 不碰官方路由与数据逻辑；命中不到关键节点时自动降级。
 * ============================================================ */
(function () {
  if (window.__DSH_SHELL_DISABLED__ === true) return;
  if (window.__DSH_SHELL__) return;

  var KEY_SIDEBAR = '[class*="_sidebarCol"]';
  var KEY_SESSION = '[class*="_sessionRow"]';
  var KEY_FRAME = '[class*="_frame"]';
  var KEY_CENTER = '[class*="_centerCol"]';
  var KEY_COMPOSER = '[class*="_composerSeat"]';
  var KEY_PANEL = '[class*="_panel"]';

  var state = { ready: false, drawer: false, degraded: false };

  function root() { return document.documentElement; }

  /** !important inline write — wins over any stylesheet rule. */
  function set(el, prop, value) {
    if (!el || !el.style) return;
    el.style.setProperty(prop, value, 'important');
  }

  function openDrawer(open) {
    state.drawer = open;
    root().classList.toggle('dsh-drawer-open', open);
    var b = document.querySelector('.dsh-drawer-btn');
    if (b) b.textContent = open ? '×' : '☰';
    var side = document.querySelector(KEY_SIDEBAR);
    set(side, 'transform', open ? 'translateX(0)' : 'translateX(-104%)');
  }

  function ensureShellChrome() {
    if (!document.body || document.querySelector('.dsh-scrim')) return;
    var btn = document.createElement('button');
    btn.className = 'dsh-drawer-btn';
    btn.style.setProperty('z-index', '2147483001', 'important');
    btn.type = 'button';
    btn.setAttribute('aria-label', '会话列表');
    btn.textContent = '☰';
    btn.textContent = state.drawer ? '×' : '☰';
    btn.addEventListener('click', function () { openDrawer(!state.drawer); });
    document.body.appendChild(btn);
  }

  function layout() {
    set(root(), 'height', '100%');
    set(root(), 'overflow', 'hidden');
    if (document.body) {
      set(document.body, 'height', '100%');
      set(document.body, 'overflow', 'hidden');
    }
    var frame = document.querySelector(KEY_FRAME);
    set(frame, 'height', '100vh');
    set(frame, 'max-height', '100vh');
    set(frame, 'overflow', 'hidden');

    var center = document.querySelector(KEY_CENTER);
    set(center, 'width', '100vw');
    set(center, 'max-width', '100vw');
    // 抽屉打开时压暗主列：它的层叠上下文比侧栏高，压暗后抽屉更显眼，且不吞点击。
    set(center, 'filter', state.drawer ? 'brightness(0.42) blur(0.6px)' : 'none');
    set(center, 'transition', 'filter .2s ease');

    var side = document.querySelector(KEY_SIDEBAR);
    if (side) {
      set(side, 'position', 'fixed');
      set(side, 'top', '0');
      set(side, 'bottom', '0');
      set(side, 'left', '0');
      set(side, 'height', '100vh');
      set(side, 'width', 'min(86vw, 340px)');
      set(side, 'max-width', '340px');
      set(side, 'z-index', '2147483000');
      set(side, 'background', '#10131c');
      set(side, 'transition', 'transform .22s cubic-bezier(.2,.8,.2,1)');
      set(side, 'background-color', '#10131c');
      set(side, 'opacity', '1');
      set(side, 'visibility', 'visible');
      set(side, 'pointer-events', 'auto');
      set(side, 'transform', state.drawer ? 'translateX(0)' : 'translateX(-104%)');
    }

    var composer = document.querySelector(KEY_COMPOSER);
    if (composer) {
      set(composer, 'position', 'sticky');
      set(composer, 'bottom', '0');
      set(composer, 'z-index', '50');
      set(composer, 'background', '#0f1115');
      set(composer, 'padding-bottom', 'calc(6px + env(safe-area-inset-bottom))');
    }

    // 右侧桌面面板（详情/分栏）在手机上不显示
    var width = window.innerWidth;
    var panels = document.querySelectorAll(KEY_PANEL);
    for (var i = 0; i < panels.length; i++) {
      var el = panels[i];
      if (el.closest && el.closest(KEY_SIDEBAR)) continue;
      var rect = el.getBoundingClientRect();
      if (rect.left > width * 0.55 && rect.width < width * 0.5) set(el, 'display', 'none');
    }
  }

  function detect() {
    var sidebar = document.querySelector(KEY_SIDEBAR);
    var session = document.querySelector(KEY_SESSION);
    state.ready = Boolean(sidebar);
    state.degraded = !sidebar || !session;
    root().classList.toggle('dsh-shell-degraded', state.degraded);
  }

  function tick() {
    detect();
    if (state.ready) {
      ensureShellChrome();
      layout();
    }
  }

  // 会话行点击：官方 SPA 负责跳转，这里只收起抽屉。
  document.addEventListener('click', function (event) {
    var row = event.target && event.target.closest ? event.target.closest(KEY_SESSION) : null;
    if (row) openDrawer(false);
  }, true);

  window.__DSH_SHELL__ = {
    version: 2,
    isDrawerOpen: function () { return state.drawer; },
    closeDrawer: function () { openDrawer(false); },
    toggleDrawer: function () { openDrawer(!state.drawer); },
    isDegraded: function () { return state.degraded; },
  };

  function boot() {
    root().classList.add('dsh-shell');
    tick();
    var observer = new MutationObserver(function () { tick(); });
    observer.observe(document.body, { childList: true, subtree: true });
    window.addEventListener('resize', function () { tick(); });
    window.addEventListener('orientationchange', function () { openDrawer(false); tick(); });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
})();
