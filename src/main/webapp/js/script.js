(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const els = {
    error: $('error'), startForm: $('start-form'), startBtn: $('start-btn'), name: $('name'),
    play: $('play'), playerName: $('player-name'), left: $('stat-left'), tries: $('stat-tries'),
    status: $('stat-status'), meter: $('meter'), meterFill: $('meter-fill'),
    guessForm: $('guess-form'), guess: $('guess'), guessBtn: $('guess-btn'),
    message: $('message'), list: $('guess-list'), restartBtn: $('restart-btn'),
    statsLine: $('stats-line'), sort: $('sort'), body: $('board-body'), empty: $('board-empty'),
    confetti: $('confetti'), sparkles: $('sparkles')
  };

  const STATUS_LABELS = { IN_PROGRESS: 'Playing', WON: 'Won', LOST: 'Lost' };
  const ARROW_UP = '\u2191';
  const ARROW_DOWN = '\u2193';
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  let busy = false;
  let gameOver = false;
  let currentName = '';

  /* ---------- networking ---------- */

  async function api(method, url, params) {
    const options = {
      method,
      credentials: 'same-origin',
      headers: { Accept: 'application/json' }
    };
    if (method === 'POST') {
      options.headers['X-Requested-With'] = 'fetch';
      options.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
      options.body = new URLSearchParams(params).toString();
    }

    let response;
    try {
      response = await fetch(url, options);
    } catch (networkError) {
      throw new Error('Cannot reach the server. Is Tomcat running?');
    }

    let data = null;
    try {
      data = await response.json();
    } catch (parseError) {
      data = null;
    }
    if (!response.ok) {
      throw new Error((data && data.error) || 'Request failed (' + response.status + ').');
    }
    if (data === null) {
      throw new Error('Unexpected response from the server.');
    }
    return data;
  }

  /* ---------- UI helpers ---------- */

  function showError(text) {
    els.error.textContent = text;
    els.error.hidden = false;
  }

  function clearError() {
    els.error.textContent = '';
    els.error.hidden = true;
  }

  function applyControls() {
    els.guess.disabled = busy || gameOver;
    els.guessBtn.disabled = busy || gameOver;
    els.startBtn.disabled = busy;
    els.restartBtn.disabled = busy;
    [els.startBtn, els.guessBtn, els.restartBtn].forEach((button) => {
      button.setAttribute('aria-busy', busy ? 'true' : 'false');
    });
  }

  /** Runs an async action with loading state and error display. Returns true on success. */
  async function run(action) {
    if (busy) {
      return false;
    }
    busy = true;
    clearError();
    applyControls();
    try {
      await action();
      return true;
    } catch (error) {
      showError(error.message);
      return false;
    } finally {
      busy = false;
      applyControls();
    }
  }

  function messageClass(state) {
    if (state.status === 'WON') { return 'win'; }
    if (state.status === 'LOST') { return 'lose'; }
    const last = state.guesses[state.guesses.length - 1];
    if (!last) { return ''; }
    if (last.close) { return 'close'; }
    return last.hint === 'TOO_HIGH' ? 'high' : 'low';
  }

  function buildChip(guess) {
    const chip = document.createElement('li');
    chip.className = 'chip';
    let label;
    let symbol;
    if (guess.hint === 'CORRECT') {
      chip.classList.add('win');
      label = 'correct';
      symbol = '\u2713';
    } else if (guess.hint === 'TOO_HIGH') {
      chip.classList.add('high');
      label = 'too high';
      symbol = ARROW_DOWN;
    } else {
      chip.classList.add('low');
      label = 'too low';
      symbol = ARROW_UP;
    }
    if (guess.close) {
      chip.classList.add('close');
      label += ', close';
    }
    chip.appendChild(document.createTextNode(guess.value + ' ' + symbol));
    const hidden = document.createElement('span');
    hidden.className = 'sr-only';
    hidden.textContent = ' (' + label + ')';
    chip.appendChild(hidden);
    return chip;
  }

  function render(state) {
    if (!state.active) {
      els.startForm.hidden = false;
      els.play.hidden = true;
      gameOver = false;
      applyControls();
      return;
    }
    currentName = state.playerName;
    gameOver = state.over;

    els.startForm.hidden = true;
    els.play.hidden = false;
    els.playerName.textContent = state.playerName;
    els.left.textContent = state.attemptsRemaining;
    els.tries.textContent = state.attemptsUsed;
    els.status.textContent = STATUS_LABELS[state.status] || state.status;

    els.meter.setAttribute('aria-valuemax', String(state.maxAttempts));
    els.meter.setAttribute('aria-valuenow', String(state.attemptsUsed));
    els.meterFill.style.width = (state.attemptsUsed / state.maxAttempts * 100) + '%';

    els.guess.min = state.min;
    els.guess.max = state.max;
    els.guess.setAttribute('aria-label', 'Your guess, ' + state.min + ' to ' + state.max);

    els.message.textContent = state.message;
    els.message.className = 'message ' + messageClass(state);

    els.list.replaceChildren(...state.guesses.map(buildChip));
    els.restartBtn.hidden = !state.over;
    applyControls();

    if (state.over && !state.saved) {
      showError('The game finished, but the result could not be saved to the database.');
    }
  }

  /* ---------- game actions ---------- */

  async function startGame(name) {
    const ok = await run(async () => {
      const state = await api('POST', 'game', { action: 'start', name });
      els.guess.value = '';
      render(state);
    });
    if (ok) {
      rememberPrefs({ name: currentName });
      els.guess.focus();
    }
  }

  async function submitGuess() {
    const raw = els.guess.value.trim();
    if (!/^\d{1,2}$/.test(raw) || Number(raw) < 1 || Number(raw) > 50) {
      showError('Enter a whole number from 1 to 50.');
      els.guess.focus();
      return;
    }
    let finished = false;
    let won = false;
    const ok = await run(async () => {
      const state = await api('POST', 'game', { action: 'guess', guess: raw });
      render(state);
      els.guess.value = '';
      finished = state.over;
      won = state.status === 'WON';
    });
    if (!ok) { return; }
    if (won) { launchConfetti(); }
    if (finished) {
      els.restartBtn.focus();
      await loadHistory();
    } else {
      els.guess.focus();
    }
  }

  /* ---------- leaderboard ---------- */

  function cell(text, className) {
    const td = document.createElement('td');
    if (className) {
      const span = document.createElement('span');
      span.className = className;
      span.textContent = text;
      td.appendChild(span);
    } else {
      td.textContent = text;
    }
    return td;
  }

  function formatWhen(iso) {
    const date = new Date(iso);
    if (isNaN(date.getTime())) { return ''; }
    return date.toLocaleString([], { dateStyle: 'short', timeStyle: 'short' });
  }

  async function loadHistory() {
    try {
      const data = await api('GET', 'history?limit=10&sort=' + encodeURIComponent(els.sort.value));
      const s = data.stats;
      const dot = ' \u00b7 ';
      els.statsLine.textContent = s.played + ' games' + dot + s.wins + ' won' + dot + s.losses + ' lost';
      const rows = data.results.map((r) => {
        const tr = document.createElement('tr');
        tr.appendChild(cell(r.playerName));
        tr.appendChild(cell(r.outcome === 'WON' ? 'Won' : 'Lost', 'badge ' + r.outcome.toLowerCase()));
        tr.appendChild(cell(String(r.attemptsUsed)));
        tr.appendChild(cell(String(r.score)));
        tr.appendChild(cell(formatWhen(r.playedAt)));
        return tr;
      });
      els.body.replaceChildren(...rows);
      els.empty.hidden = rows.length > 0;
    } catch (error) {
      els.statsLine.textContent = 'Leaderboard unavailable: ' + error.message;
    }
  }

  /* ---------- visual effects ---------- */

  function createSparkles() {
    if (reduceMotion) { return; }
    for (let i = 0; i < 70; i++) {
      const s = document.createElement('span');
      s.className = 'sparkle';
      s.style.left = (Math.random() * 100) + '%';
      s.style.top = (Math.random() * 100) + '%';
      s.style.setProperty('--size', (1 + Math.random() * 3) + 'px');
      s.style.animationDelay = (Math.random() * 4) + 's';
      s.style.animationDuration = (2 + Math.random() * 3) + 's';
      els.sparkles.appendChild(s);
    }
  }

  function launchConfetti() {
    if (reduceMotion) { return; }
    const colors = ['#f472b6', '#8b5cf6', '#38bdf8', '#4ade80', '#fde047', '#ffffff'];
    const pieces = [];
    for (let i = 0; i < 100; i++) {
      const p = document.createElement('span');
      p.className = 'piece';
      p.style.left = (Math.random() * 100) + '%';
      p.style.background = colors[i % colors.length];
      p.style.setProperty('--dx', (Math.random() * 240 - 120) + 'px');
      p.style.setProperty('--rot', (360 + Math.random() * 720) + 'deg');
      p.style.setProperty('--dur', (2.4 + Math.random() * 2) + 's');
      p.style.setProperty('--delay', (Math.random() * 0.6) + 's');
      pieces.push(p);
    }
    els.confetti.replaceChildren(...pieces);
    window.setTimeout(() => els.confetti.replaceChildren(), 5500);
  }

  /* ---------- startup ---------- */

  els.startForm.addEventListener('submit', (event) => {
    event.preventDefault();
    startGame(els.name.value);
  });
  els.guessForm.addEventListener('submit', (event) => {
    event.preventDefault();
    submitGuess();
  });
  els.restartBtn.addEventListener('click', () => startGame(currentName));
  els.sort.addEventListener('change', () => {
    rememberPrefs({ sort: els.sort.value });
    loadHistory();
  });

  /* ---------- optional preferences cookie (only when the visitor allowed it) ---------- */

  function rememberPrefs(update) {
    if (window.NeonConsent) { window.NeonConsent.writePrefs(update); }
  }

  document.addEventListener('neon:consent', (event) => {
    if (!event.detail.preferences || !window.NeonConsent) { return; }
    const prefs = window.NeonConsent.readPrefs();
    if (prefs.name && !els.name.value) { els.name.value = prefs.name; }
    if (prefs.sort && els.sort.value !== prefs.sort) {
      els.sort.value = prefs.sort;
      loadHistory();
    }
    if (currentName) { rememberPrefs({ name: currentName }); }
  });

  async function init() {
    createSparkles();
    applyControls();
    try {
      render(await api('GET', 'game'));
    } catch (error) {
      showError(error.message);
    }
    loadHistory();
  }

  init();
})();
