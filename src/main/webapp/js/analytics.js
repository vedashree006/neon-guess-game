/*
 * Anonymous analytics client. consent.js loads this file ONLY after the server confirmed analytics
 * consent, and it stops immediately when consent is withdrawn. It sends one page_view per page load
 * and a lightweight heartbeat while the tab is visible and the person is active. Heartbeats never
 * create page views.
 */
(() => {
  'use strict';
  if (window.NeonAnalytics) { return; }

  const HEARTBEAT_MS = 30 * 1000;
  const IDLE_AFTER_MS = 5 * 60 * 1000;
  const PATH_PATTERN = /^\/[A-Za-z0-9/_.\-]{0,99}$/;
  const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'scroll', 'touchstart'];

  let running = false;
  let timer = null;
  let lastInteraction = Date.now();

  function newId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') { return window.crypto.randomUUID(); }
    const b = window.crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
    return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
  }

  async function post(url, params) {
    return fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'X-Requested-With': 'fetch',
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
      },
      body: new URLSearchParams(params || {}).toString()
    });
  }

  async function sendPageView() {
    const path = PATH_PATTERN.test(location.pathname) ? location.pathname : '/';
    try {
      const response = await post('api/analytics/event', { type: 'page_view', eventId: newId(), path });
      if (response.status === 403) { stop(); }
    } catch (ignored) { /* analytics must never disturb the game */ }
  }

  async function beat() {
    if (!running || document.hidden || Date.now() - lastInteraction > IDLE_AFTER_MS) { return; }
    try {
      const response = await post('api/analytics/heartbeat');
      if (response.status === 409) { await sendPageView(); }
      else if (response.status === 403) { stop(); }
    } catch (ignored) { /* ignore */ }
  }

  function markActive() { lastInteraction = Date.now(); }
  function onVisible() { if (!document.hidden) { markActive(); beat(); } }

  function start() {
    if (running) { return; }
    running = true;
    markActive();
    ACTIVITY_EVENTS.forEach((name) => window.addEventListener(name, markActive, { passive: true }));
    document.addEventListener('visibilitychange', onVisible);
    sendPageView();
    timer = window.setInterval(beat, HEARTBEAT_MS);
  }

  function stop() {
    if (!running) { return; }
    running = false;
    window.clearInterval(timer);
    ACTIVITY_EVENTS.forEach((name) => window.removeEventListener(name, markActive));
    document.removeEventListener('visibilitychange', onVisible);
  }

  document.addEventListener('neon:consent', (event) => {
    if (event.detail && event.detail.analytics) { start(); } else { stop(); }
  });

  window.NeonAnalytics = { start, stop };
  if (window.NeonConsent && window.NeonConsent.get().analytics) { start(); }
})();
