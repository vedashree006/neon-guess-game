/*
 * Cookie consent: banner, settings dialog and the preferences cookie.
 * The server owns the decision (HttpOnly cookie neon_consent); this script only shows the choices,
 * posts them, and starts analytics.js after (and only after) the server confirms analytics consent.
 */
(() => {
  'use strict';

  const PREFS_COOKIE = 'neon_prefs';
  const PREFS_MAX_AGE = 180 * 24 * 60 * 60;
  const NAME_PATTERN = /^[\p{L}\p{N} _.\-]{1,20}$/u;

  let state = { decided: false, analytics: false, preferences: false };
  let analyticsRequested = false;
  let lastOpener = null;

  /* ---------- tiny DOM helper (no innerHTML, CSP friendly) ---------- */

  function el(tag, props, ...children) {
    const node = document.createElement(tag);
    Object.entries(props || {}).forEach(([key, value]) => {
      if (key === 'text') { node.textContent = value; }
      else if (key === 'class') { node.className = value; }
      else { node.setAttribute(key, value); }
    });
    children.forEach((child) => node.append(child));
    return node;
  }

  /* ---------- networking ---------- */

  async function request(method, params) {
    const options = { method, credentials: 'same-origin', headers: { Accept: 'application/json' } };
    if (method === 'POST') {
      options.headers['X-Requested-With'] = 'fetch';
      options.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
      options.body = new URLSearchParams(params).toString();
    }
    const response = await fetch('api/cookie-consent', options);
    if (!response.ok) { throw new Error('Request failed (' + response.status + ').'); }
    return response.json();
  }

  /* ---------- preferences cookie (optional category) ---------- */

  function cookiePath() { return location.pathname.replace(/[^/]*$/, ''); }
  function secureFlag() { return location.protocol === 'https:' ? '; Secure' : ''; }

  function readPrefs() {
    if (!state.preferences) { return {}; }
    const entry = document.cookie.split('; ').find((c) => c.startsWith(PREFS_COOKIE + '='));
    if (!entry) { return {}; }
    try {
      const data = JSON.parse(decodeURIComponent(entry.slice(PREFS_COOKIE.length + 1)));
      const clean = {};
      if (typeof data.name === 'string' && NAME_PATTERN.test(data.name)) { clean.name = data.name; }
      if (data.sort === 'recent' || data.sort === 'top') { clean.sort = data.sort; }
      return clean;
    } catch (ignored) {
      return {};
    }
  }

  function writePrefs(update) {
    if (!state.preferences) { return; }
    const merged = Object.assign(readPrefs(), update);
    document.cookie = PREFS_COOKIE + '=' + encodeURIComponent(JSON.stringify(merged))
      + '; Max-Age=' + PREFS_MAX_AGE + '; Path=' + cookiePath() + '; SameSite=Strict' + secureFlag();
  }

  function clearPrefs() {
    document.cookie = PREFS_COOKIE + '=; Max-Age=0; Path=' + cookiePath() + '; SameSite=Strict' + secureFlag();
  }

  /* ---------- state changes ---------- */

  function loadAnalytics() {
    if (analyticsRequested) { return; }
    analyticsRequested = true;
    const script = document.createElement('script');
    script.src = 'js/analytics.js';
    script.defer = true;
    document.head.appendChild(script);
  }

  function apply(next) {
    state = { decided: !!next.decided, analytics: !!next.analytics, preferences: !!next.preferences };
    if (!state.preferences) { clearPrefs(); }
    if (state.analytics) { loadAnalytics(); }
    document.dispatchEvent(new CustomEvent('neon:consent', { detail: Object.assign({}, state) }));
    banner.hidden = state.decided;
  }

  async function save(analytics, preferences) {
    errorBox.hidden = true;
    try {
      apply(await request('POST', { analytics: String(analytics), preferences: String(preferences) }));
      closeDialog();
    } catch (error) {
      errorBox.textContent = 'Could not save your choice. Please try again.';
      errorBox.hidden = false;
    }
  }

  /* ---------- UI ---------- */

  const errorBox = el('p', { class: 'alert', role: 'alert', hidden: '' });

  function button(label, id, extraClass) {
    return el('button', { type: 'button', id, class: 'btn cc-btn' + (extraClass ? ' ' + extraClass : ''), text: label });
  }

  const acceptBtn = button('Accept all', 'cc-accept');
  const rejectBtn = button('Reject optional', 'cc-reject');
  const customizeBtn = button('Customize', 'cc-customize', 'btn-ghost');

  const banner = el('section', { class: 'cc-banner', 'aria-labelledby': 'cc-title', hidden: '' },
    el('h2', { id: 'cc-title', text: 'Cookies and your privacy' }),
    el('p', {
      text: 'The game needs a few necessary cookies to work. With your permission we also use optional '
        + 'cookies for anonymous visitor statistics and to remember your preferences. '
        + 'You can change your choice at any time via "Cookie settings" in the footer.'
    }),
    el('p', { class: 'help' }, el('a', { href: 'privacy.html', text: 'Read the privacy and cookie information' })),
    el('div', { class: 'cc-actions' }, acceptBtn, rejectBtn, customizeBtn));

  function category(id, title, text, checked, locked) {
    const input = el('input', { type: 'checkbox', id, class: 'cc-check' });
    input.checked = checked;
    input.disabled = locked;
    return {
      input,
      node: el('div', { class: 'cc-cat' },
        el('label', { for: id, class: 'cc-cat-title' }, input, el('span', { text: title + (locked ? ' (always on)' : '') })),
        el('p', { class: 'help', text }))
    };
  }

  const necessary = category('cc-necessary', 'Necessary',
    'Keep the game working: a session cookie (JSESSIONID) holds your current game and the secret number on the '
    + 'server, and a cookie (neon_consent) remembers this choice for about 6 months. They contain no tracking '
    + 'identifier and cannot be switched off.', true, true);
  const analyticsCat = category('cc-analytics', 'Analytics',
    'Anonymous visitor statistics. A random ID in the cookie neon_vid (about 6 months) lets us count unique '
    + 'visitors, visits, page views and game activity. We do not store your IP address, name or e-mail. '
    + 'Off unless you allow it. If you withdraw, the cookie and the data linked to it are deleted.', false, false);
  const prefsCat = category('cc-preferences', 'Preferences',
    'Remember your player name and the leaderboard view on this device (cookie neon_prefs, about 6 months) so '
    + 'you do not have to enter them again. Off unless you allow it.', false, false);

  const dialog = el('dialog', { class: 'cc-dialog', 'aria-labelledby': 'cc-dialog-title' },
    el('h2', { id: 'cc-dialog-title', text: 'Cookie settings' }),
    el('p', { class: 'help', text: 'Choose which optional cookies you allow. Nothing optional is set until you decide.' }),
    necessary.node, analyticsCat.node, prefsCat.node, errorBox,
    el('div', { class: 'cc-actions' },
      button('Save preferences', 'cc-save'),
      button('Accept all', 'cc-dialog-accept'),
      button('Reject optional', 'cc-dialog-reject'),
      button('Close', 'cc-close', 'btn-ghost')));

  function openDialog(opener) {
    lastOpener = opener || null;
    analyticsCat.input.checked = state.analytics;
    prefsCat.input.checked = state.preferences;
    errorBox.hidden = true;
    if (typeof dialog.showModal === 'function') { dialog.showModal(); } else { dialog.setAttribute('open', ''); }
  }

  function closeDialog() {
    if (typeof dialog.close === 'function') { dialog.close(); } else { dialog.removeAttribute('open'); }
    if (lastOpener && typeof lastOpener.focus === 'function') { lastOpener.focus(); }
  }

  /* ---------- startup ---------- */

  async function init() {
    document.body.append(banner, dialog);
    acceptBtn.addEventListener('click', () => save(true, true));
    rejectBtn.addEventListener('click', () => save(false, false));
    customizeBtn.addEventListener('click', () => openDialog(customizeBtn));
    dialog.querySelector('#cc-save').addEventListener('click',
      () => save(analyticsCat.input.checked, prefsCat.input.checked));
    dialog.querySelector('#cc-dialog-accept').addEventListener('click', () => save(true, true));
    dialog.querySelector('#cc-dialog-reject').addEventListener('click', () => save(false, false));
    dialog.querySelector('#cc-close').addEventListener('click', closeDialog);
    document.querySelectorAll('[data-cookie-settings]').forEach((node) => {
      node.addEventListener('click', () => openDialog(node));
    });
    try {
      apply(await request('GET'));
    } catch (error) {
      // Server unreachable: stay in the safe state (no optional cookies, no tracking, no banner noise).
      state = { decided: false, analytics: false, preferences: false };
    }
  }

  window.NeonConsent = {
    get: () => Object.assign({}, state),
    open: () => openDialog(null),
    readPrefs,
    writePrefs
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
