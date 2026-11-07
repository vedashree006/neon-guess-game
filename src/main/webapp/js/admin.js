/* Admin dashboard: polls the protected JSON APIs and updates the page in place (no full reload). */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const SERIES = [
    { key: 'visitors', label: 'Unique visitors', cls: 's1' },
    { key: 'pageViews', label: 'Page views', cls: 's2' },
    { key: 'gamesStarted', label: 'Games started', cls: 's3' },
    { key: 'gamesCompleted', label: 'Games completed', cls: 's4' }
  ];
  const SVG_NS = 'http://www.w3.org/2000/svg';

  let range = '7d';
  let refreshMs = 15000;
  let timer = null;
  let inFlight = false;
  let signedOut = false;

  class AuthError extends Error { }

  async function getJson(url) {
    const response = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
    if (response.status === 401) { throw new AuthError('Session expired'); }
    if (!response.ok) { throw new Error('Request failed (' + response.status + ')'); }
    return response.json();
  }

  /* ---------- formatting and binding ---------- */

  function lookup(object, path) {
    return path.split('.').reduce((value, key) => (value == null ? undefined : value[key]), object);
  }

  function format(value, kind) {
    if (kind === 'na') { return value == null ? 'n/a' : String(value); }
    if (value == null) { return '-'; }
    switch (kind) {
      case 'percent': return value.toFixed(1) + ' %';
      case 'decimal': return value.toFixed(1);
      case 'minutes': return String(Math.round(value / 60));
      case 'datetime': {
        const date = new Date(value);
        return isNaN(date.getTime()) ? '-' : date.toLocaleString([], { dateStyle: 'medium', timeStyle: 'medium' });
      }
      default: return typeof value === 'number' ? value.toLocaleString() : String(value);
    }
  }

  function bind(summary) {
    document.querySelectorAll('[data-bind]').forEach((node) => {
      node.textContent = format(lookup(summary, node.dataset.bind), node.dataset.format);
    });
  }

  function cell(text) {
    const td = document.createElement('td');
    td.textContent = text;
    return td;
  }

  function row(values) {
    const tr = document.createElement('tr');
    values.forEach((value) => tr.appendChild(cell(String(value))));
    return tr;
  }

  function renderDaily(points) {
    $('daily-body').replaceChildren(...points.slice().reverse().map((p) =>
      row([p.t.slice(0, 10), p.visitors, p.pageViews, p.gamesStarted, p.gamesCompleted])));
  }

  /* ---------- chart (plain SVG) ---------- */

  function node(tag, attrs, text) {
    const element = document.createElementNS(SVG_NS, tag);
    Object.entries(attrs || {}).forEach(([key, value]) => element.setAttribute(key, String(value)));
    if (text != null) { element.textContent = text; }
    return element;
  }

  function label(point, unit) {
    const date = new Date(point.t);
    return unit === 'hour'
      ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
      : point.t.slice(5, 10);
  }

  function niceMax(max) {
    if (max <= 4) { return 4; }
    const power = Math.pow(10, Math.floor(Math.log10(max)));
    return Math.ceil(max / power * 2) / 2 * power;
  }

  function renderChart(data) {
    const W = 760; const H = 280; const L = 44; const R = 14; const T = 14; const B = 34;
    const points = data.points;
    const max = niceMax(Math.max(1, ...points.map((p) => Math.max(...SERIES.map((s) => p[s.key])))));
    const x = (i) => L + (points.length === 1 ? (W - L - R) / 2 : i * (W - L - R) / (points.length - 1));
    const y = (v) => T + (H - T - B) * (1 - v / max);

    const svg = node('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'img',
      'aria-label': 'Line chart of visitors, page views and games over the selected range' });
    for (let i = 0; i <= 4; i++) {
      const value = max * i / 4;
      svg.appendChild(node('line', { class: 'grid', x1: L, x2: W - R, y1: y(value), y2: y(value) }));
      svg.appendChild(node('text', { class: 'axis-text', x: L - 6, y: y(value) + 4, 'text-anchor': 'end' },
        Number.isInteger(value) ? value : value.toFixed(1)));
    }
    const step = Math.max(1, Math.ceil(points.length / 8));
    points.forEach((p, i) => {
      if (i % step === 0 || i === points.length - 1) {
        svg.appendChild(node('text', { class: 'axis-text', x: x(i), y: H - 10, 'text-anchor': 'middle' }, label(p, data.bucket)));
      }
    });
    SERIES.forEach((s) => {
      const d = points.map((p, i) => (i === 0 ? 'M' : 'L') + x(i).toFixed(1) + ' ' + y(p[s.key]).toFixed(1)).join(' ');
      svg.appendChild(node('path', { class: 'line ' + s.cls, d }));
      if (points.length <= 31) {
        points.forEach((p, i) => svg.appendChild(node('circle', { class: 'dot ' + s.cls, cx: x(i), cy: y(p[s.key]), r: 2.6 })));
      }
    });
    $('chart').replaceChildren(svg);
    $('series-body').replaceChildren(...points.slice().reverse().map((p) =>
      row([p.t.replace('T', ' ').replace(':00Z', ' UTC').replace('Z', ' UTC'), p.visitors, p.pageViews, p.gamesStarted, p.gamesCompleted])));
  }

  function renderLegend() {
    $('legend').replaceChildren(...SERIES.map((s) => {
      const li = document.createElement('li');
      const swatch = document.createElement('i');
      swatch.className = s.cls;
      li.append(swatch, document.createTextNode(s.label));
      return li;
    }));
  }

  /* ---------- status, polling ---------- */

  function setStatus(kind, text) {
    const pill = $('status');
    pill.className = 'pill' + (kind ? ' ' + kind : '');
    pill.textContent = text;
  }

  function showBanner(text) {
    $('banner').textContent = text;
    $('banner').hidden = !text;
  }

  function schedule() {
    window.clearTimeout(timer);
    if (!signedOut && !document.hidden) { timer = window.setTimeout(refresh, refreshMs); }
  }

  async function refresh() {
    if (inFlight || signedOut) { return; }
    inFlight = true;
    try {
      const [summary, series] = await Promise.all([
        getJson('../api/analytics/summary'),
        getJson('../api/analytics/timeseries?range=' + encodeURIComponent(range))
      ]);
      bind(summary);
      renderDaily(summary.daily);
      renderChart(series);
      refreshMs = Math.min(15, Math.max(10, summary.settings.refreshSeconds)) * 1000;
      $('updated').textContent = new Date().toLocaleTimeString();
      setStatus('ok', 'Live');
      showBanner('');
    } catch (error) {
      if (error instanceof AuthError) {
        signedOut = true;
        setStatus('err', 'Signed out');
        showBanner('Your admin session expired. Reload this page to sign in again.');
      } else {
        setStatus('err', 'Offline');
        showBanner('Could not refresh the data. Retrying automatically.');
      }
    } finally {
      inFlight = false;
      schedule();
    }
  }

  function setRange(next) {
    range = next;
    document.querySelectorAll('.range-btn').forEach((button) => {
      button.setAttribute('aria-pressed', String(button.dataset.range === range));
    });
    refresh();
  }

  document.querySelectorAll('.range-btn').forEach((button) => {
    button.addEventListener('click', () => setRange(button.dataset.range));
  });
  $('refresh').addEventListener('click', refresh);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { window.clearTimeout(timer); } else { refresh(); }
  });

  renderLegend();
  refresh();
})();
