(function () {
const { api, toast, loadMe, renderUserChip, statusLabel, setDebug } = window.Imadj || {};
if (!api) {
  document.getElementById('debugBar').textContent = 'FATAL: /js/api.js не загрузился';
  throw new Error('Imadj api missing');
}

const params = new URLSearchParams(location.search);
const gameId = params.get('id');
if (!gameId) {
  setDebug?.('no game id in URL');
  location.href = '/';
}
setDebug?.('game.js v9 · gameId=' + gameId);

const els = {
  userChip: document.getElementById('userChip'),
  lobbyPanel: document.getElementById('lobbyPanel'),
  playPanel: document.getElementById('playPanel'),
  roomTitle: document.getElementById('roomTitle'),
  roomMeta: document.getElementById('roomMeta'),
  playerPills: document.getElementById('playerPills'),
  deckGrid: document.getElementById('deckGrid'),
  readyBtn: document.getElementById('readyBtn'),
  unreadyBtn: document.getElementById('unreadyBtn'),
  scoreList: document.getElementById('scoreList'),
  scoringHint: document.getElementById('scoringHint'),
  stageTitle: document.getElementById('stageTitle'),
  stageHelp: document.getElementById('stageHelp'),
  roundBadge: document.getElementById('roundBadge'),
  associationText: document.getElementById('associationText'),
  tableSection: document.getElementById('tableSection'),
  tableGrid: document.getElementById('tableGrid'),
  handGrid: document.getElementById('handGrid'),
  assocForm: document.getElementById('assocForm'),
  associationInput: document.getElementById('associationInput'),
  submitAssocBtn: document.getElementById('submitAssocBtn'),
  resultsBox: document.getElementById('resultsBox'),
  resultsDelta: document.getElementById('resultsDelta'),
  nextRoundBtn: document.getElementById('nextRoundBtn'),
  waitMasterBox: document.getElementById('waitMasterBox'),
  gameOverBox: document.getElementById('gameOverBox'),
  botVoteBox: document.getElementById('botVoteBox'),
  botVoteMeta: document.getElementById('botVoteMeta'),
  botVoteYesBtn: document.getElementById('botVoteYesBtn'),
  botVoteNoBtn: document.getElementById('botVoteNoBtn'),
  cardInspect: document.getElementById('cardInspect'),
  cardInspectImg: document.getElementById('cardInspectImg'),
  cardInspectConfirm: document.getElementById('cardInspectConfirm'),
  cardInspectClose: document.getElementById('cardInspectClose'),
};

let me = null;
let state = null;
let decksById = {};
let selectedDecks = new Set();
/** @type {{ zone: 'hand'|'table', id: string, url: string } | null} */
let inspected = null;
let selectedHandCard = null;
let ws = null;
let pollTimer = null;
let intentionalLeave = false;
let lastToastAt = 0;
let lastToastMsg = '';

function myClientId() {
  return state?.yourClientId || me?.clientId || null;
}

function softToast(message) {
  const now = Date.now();
  if (message === lastToastMsg && now - lastToastAt < 4000) return;
  lastToastMsg = message;
  lastToastAt = now;
  toast(message);
}

function syncIdentityFromState() {
  if (state?.yourClientId && me) {
    me.clientId = state.yourClientId;
  }
}

const stageHelp = {
  THEME_SELECTION: 'Ведущий: клик по карте — приблизить, затем «Выбрать» и введи ассоциацию.',
  CARD_COLLECTION: 'Клик — приблизить, «Положить» — подтвердить ход.',
  VOTING: 'Клик — приблизить, «Проголосовать» — подтвердить. Свою карту выбрать нельзя; ведущий только смотрит.',
  RESULTS: 'Можно приближать карты. Дальше — «Следующий раунд».',
  GAME_OVER: 'Карты закончились — игра окончена.',
};

function mePlayer() {
  return (state?.players || []).find(p => p.clientId === myClientId()) || null;
}

function canPickHand() {
  if (!state) return false;
  const mp = mePlayer();
  return (state.stage === 'THEME_SELECTION' && state.youAreMaster) ||
    (state.stage === 'CARD_COLLECTION' && !state.youAreMaster && !mp?.hasPlayed);
}

function canVoteFor(cardId) {
  if (!state || state.stage !== 'VOTING') return false;
  if (state.youAreMaster) return false;
  if (mePlayer()?.hasVoted) return false;
  if (cardId && state.yourPlayedCardId && cardId === state.yourPlayedCardId) return false;
  return true;
}

function confirmLabel(zone, cardId) {
  if (zone === 'hand') {
    if (state?.stage === 'THEME_SELECTION' && state.youAreMaster) return 'Выбрать';
    if (state?.stage === 'CARD_COLLECTION') return 'Положить';
    return null;
  }
  if (zone === 'table' && canVoteFor(cardId)) return 'Проголосовать';
  return null;
}

function closeInspect() {
  inspected = null;
  if (els.cardInspect) els.cardInspect.hidden = true;
  if (els.cardInspectConfirm) els.cardInspectConfirm.hidden = true;
  renderPlayCardsOnly();
}

function openInspect(zone, id, url) {
  if (inspected && inspected.zone === zone && inspected.id === id) {
    closeInspect();
    return;
  }
  inspected = { zone, id, url };
  if (els.cardInspectImg) els.cardInspectImg.src = url;
  const label = confirmLabel(zone, id);
  if (els.cardInspectConfirm) {
    els.cardInspectConfirm.hidden = !label;
    els.cardInspectConfirm.textContent = label || '';
  }
  if (els.cardInspect) els.cardInspect.hidden = false;
  renderPlayCardsOnly();
}

function renderPlayCardsOnly() {
  if (!state || state.stage === 'LOBBY') return;
  // Avoid full lobby re-render; only refresh card highlights when inspect toggles.
  if (els.playPanel && !els.playPanel.hidden) {
    paintTable();
    paintHand();
  }
}

async function init() {
  try {
    setDebug('loadMe…');
    me = await loadMe();
    renderUserChip(me, els.userChip);
    setDebug('join ' + gameId + ' as ' + me.clientId);
    state = await api(`/api/games/${gameId}/join`, { method: 'POST' });
    syncIdentityFromState();
    setDebug('joined room=' + state.name + ' stage=' + state.stage + ' as ' + myClientId());
    const decks = await api('/api/decks');
    decksById = Object.fromEntries((decks || []).map(d => [String(d.id), d]));
    selectedDecks = new Set((state.yourSelectedDeckIds || []).map(String));
    render();
    connectWs();
    ensurePolling(2000);
  } catch (err) {
    console.error(err);
    toast(err.message || 'Не удалось открыть комнату');
    setDebug('ERROR: ' + (err.message || err));
    els.roomTitle.textContent = 'Ошибка загрузки';
    els.roomMeta.textContent = err.message || '';
  }
}

function ensurePolling(ms) {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(refreshHttp, ms);
}

async function refreshHttp() {
  try {
    state = await api(`/api/games/${gameId}`);
    syncIdentityFromState();
    selectedDecks = new Set((state.yourSelectedDeckIds || []).map(String));
    render();
    if (state.status === 'PLAYING' || state.youReady) {
      connectWs();
    }
  } catch (err) {
    if (err.message && /join the lobby first/i.test(err.message)) {
      try {
        state = await api(`/api/games/${gameId}/join`, { method: 'POST' });
        syncIdentityFromState();
        render();
        connectWs();
        return;
      } catch (_) {}
    }
    softToast(err.message);
  }
}

function connectWs() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  const clientId = myClientId();
  if (!clientId) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  try {
    ws = new WebSocket(`${proto}://${location.host}/ws/game?gameId=${encodeURIComponent(gameId)}&clientId=${encodeURIComponent(clientId)}`);
  } catch (err) {
    console.warn('ws create failed', err);
    return;
  }
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.type === 'STATE') {
      state = msg.state;
      syncIdentityFromState();
      selectedDecks = new Set((state.yourSelectedDeckIds || []).map(String));
      render();
    } else if (msg.type === 'ERROR') {
      console.warn('ws error', msg);
    }
  };
  ws.onopen = () => setDebug?.('ws open as ' + myClientId());
  ws.onerror = () => console.warn('ws error event');
  ws.onclose = () => {
    ws = null;
    if (intentionalLeave) return;
    setTimeout(() => {
      if (state && !intentionalLeave) connectWs();
    }, 5000);
  };
}

async function leaveRoom() {
  intentionalLeave = true;
  try {
    if (ws) {
      try { ws.close(); } catch (_) {}
    }
    await api(`/api/games/${gameId}/leave`, { method: 'POST' });
  } catch (err) {
    console.warn('leave failed', err);
  }
  window.location.href = '/';
}

async function sendOrHttp(_payload, httpCall) {
  try {
    state = await httpCall();
    syncIdentityFromState();
    closeInspect();
    selectedHandCard = null;
    render();
    connectWs();
  } catch (err) {
    softToast(err.message || 'Не удалось выполнить ход');
  }
}

function render() {
  if (!state) return;
  els.roomTitle.textContent = state.name;
  els.roomMeta.textContent = `${state.playerCount} игроков · ${statusLabel(state.status, state.stage)}`;
  els.scoringHint.textContent = state.scoringHint || '';

  els.scoreList.innerHTML = (state.players || []).map(p => `
    <div class="score-item ${p.isMaster ? 'master' : ''}">
      <span>${escapeHtml(p.displayName)}${p.bot ? ' · бот' : ''}${p.isMaster ? ' · ведущий' : ''}${p.connected || p.bot ? '' : ' · оффлайн'}</span>
      <strong>${p.score}</strong>
    </div>
  `).join('');

  const inLobby = state.stage === 'LOBBY' && state.status === 'WAITING';
  els.lobbyPanel.hidden = !inLobby;
  els.playPanel.hidden = inLobby;

  if (inLobby) {
    closeInspect();
    renderLobby();
  } else {
    renderPlay();
  }
}

function renderLobby() {
  els.playerPills.innerHTML = (state.players || []).map(p => `
    <span class="pill ${p.ready ? 'ready' : ''} ${p.connected || p.bot ? '' : 'offline'}">
      ${escapeHtml(p.displayName)}${p.bot ? ' · бот' : ''}${p.ready ? ' ✓' : ''}
    </span>
  `).join('');

  if (els.botVoteBox) {
    const hasBot = !!state.hasBot;
    const isHost = !!state.youAreHost;
    els.botVoteBox.hidden = !isHost && !hasBot;
    if (els.botVoteMeta) {
      if (!isHost) {
        els.botVoteMeta.textContent = hasBot
          ? 'В комнате есть Кисонька Сосисонька (добавил хост).'
          : '';
      } else {
        els.botVoteMeta.textContent = hasBot
          ? 'Кисонька в комнате. Можно убрать до старта.'
          : 'Добавить бота: кидает случайные карты, не голосует и не ведёт.';
      }
    }
    if (els.botVoteYesBtn) els.botVoteYesBtn.hidden = !isHost || hasBot;
    if (els.botVoteNoBtn) els.botVoteNoBtn.hidden = !isHost || !hasBot;
  }

  const available = (state.availableDeckIds || []).map(String);
  const pickedByAnyone = new Set();
  (state.players || []).forEach(p => (p.selectedDeckIds || []).forEach(id => pickedByAnyone.add(String(id))));

  els.deckGrid.innerHTML = available.map(id => {
    const d = decksById[id] || { id, name: `Колода #${id}`, cardCount: '?', isPublic: true };
    const selected = selectedDecks.has(String(d.id));
    const inPool = pickedByAnyone.has(String(d.id));
    return `
      <button type="button" class="deck-option ${selected ? 'selected' : ''}" data-deck="${d.id}">
        <strong>${escapeHtml(d.name)}</strong>
        <div class="meta">${d.cardCount} карт · ${d.isPublic ? 'публичная' : 'приватная'}${inPool ? ' · в пуле' : ''}</div>
      </button>
    `;
  }).join('') || '<div class="empty">Нет доступных колод</div>';

  els.readyBtn.hidden = !!state.youReady;
  els.unreadyBtn.hidden = !state.youReady;
}

function paintTable() {
  if (state.stage === 'CARD_COLLECTION') {
    els.tableGrid.innerHTML = `<div class="face-down">${state.tableCardCount} на столе</div>`;
    return;
  }
  if (state.stage !== 'VOTING' && state.stage !== 'RESULTS' && state.stage !== 'GAME_OVER') {
    els.tableGrid.innerHTML = '';
    return;
  }
  const ownId = state.yourPlayedCardId;
  els.tableGrid.innerHTML = (state.tableCards || []).map(c => {
    const isMasterReveal = state.masterCardId && c.id === state.masterCardId;
    const isOwn = ownId && c.id === ownId;
    const isInspected = inspected?.zone === 'table' && inspected.id === c.id;
    const meta = c.ownerName
      ? escapeHtml(c.ownerName)
      : (isOwn && state.stage === 'VOTING'
        ? 'твоя карта'
        : (state.youAreMaster && state.stage === 'VOTING' ? 'смотреть' : 'приблизить'));
    const classes = [
      'card-tile',
      isInspected ? 'inspected' : '',
      // Only the voter's own card is grayed; master can inspect all without gray wash.
      isOwn && state.stage === 'VOTING' ? 'own-card' : '',
    ].filter(Boolean).join(' ');
    return `
      <div class="card-slot">
        <button type="button" class="${classes}" data-table="${c.id}" data-zoom="${c.imageUrl}">
          <img src="${c.imageUrl}" alt="card">
          <div class="meta">${meta}${isMasterReveal ? ' · ведущий' : ''}</div>
        </button>
      </div>
    `;
  }).join('');
}

function paintHand() {
  if (state.stage === 'GAME_OVER' || state.status === 'FINISHED') {
    els.handGrid.innerHTML = '<div class="empty">Игра окончена</div>';
    return;
  }
  const pickable = canPickHand();
  els.handGrid.innerHTML = (state.hand || []).map(c => {
    const isInspected = inspected?.zone === 'hand' && inspected.id === c.id;
    const isSelected = selectedHandCard === c.id && pickable;
    const classes = [
      'card-tile',
      isInspected ? 'inspected' : '',
      isSelected ? 'selected' : '',
    ].filter(Boolean).join(' ');
    return `
      <div class="card-slot">
        <button type="button" class="${classes}" data-hand="${c.id}" data-zoom="${c.imageUrl}">
          <img src="${c.imageUrl}" alt="hand">
        </button>
      </div>
    `;
  }).join('') || '<div class="empty">Рука пуста</div>';
}

function renderPlay() {
  els.stageTitle.textContent = statusLabel(state.status, state.stage);
  els.stageHelp.textContent = stageHelp[state.stage] || '';
  const pile = typeof state.drawPileSize === 'number' ? ` · колода ${state.drawPileSize}` : '';
  els.roundBadge.textContent = state.stage === 'GAME_OVER' ? 'Финал' : `Раунд ${state.roundNumber}${pile}`;

  const showAssoc = !!state.association && state.stage !== 'THEME_SELECTION';
  els.associationText.hidden = !showAssoc;
  els.associationText.textContent = state.association || '';

  const isMaster = !!state.youAreMaster;
  const waitingMaster = state.stage === 'THEME_SELECTION' && !isMaster;
  const showAssocForm = state.stage === 'THEME_SELECTION' && isMaster;
  if (els.waitMasterBox) els.waitMasterBox.hidden = !waitingMaster;
  els.assocForm.hidden = !showAssocForm;
  els.resultsBox.hidden = state.stage !== 'RESULTS';
  if (els.gameOverBox) els.gameOverBox.hidden = state.stage !== 'GAME_OVER' && state.status !== 'FINISHED';

  if (state.stage === 'RESULTS') {
    const nameById = Object.fromEntries((state.players || []).map(p => [p.clientId, p.displayName]));
    els.resultsDelta.innerHTML = Object.entries(state.lastRoundScores || {}).map(([id, delta]) => `
      <div class="score-item"><span>${escapeHtml(nameById[id] || id)}</span><strong>+${delta}</strong></div>
    `).join('') || '<div class="muted">Нет данных</div>';
  }

  const showTable = state.stage === 'CARD_COLLECTION' || state.stage === 'VOTING' || state.stage === 'RESULTS' || state.stage === 'GAME_OVER';
  els.tableSection.hidden = !showTable;
  paintTable();
  paintHand();

  // Keep inspect confirm label in sync with stage changes.
  if (inspected && els.cardInspect && !els.cardInspect.hidden) {
    const label = confirmLabel(inspected.zone, inspected.id);
    if (els.cardInspectConfirm) {
      els.cardInspectConfirm.hidden = !label;
      els.cardInspectConfirm.textContent = label || '';
    }
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

async function confirmInspected() {
  if (!inspected || !state) return;
  const { zone, id } = inspected;

  if (zone === 'hand') {
    if (state.stage === 'THEME_SELECTION' && state.youAreMaster) {
      selectedHandCard = id;
      closeInspect();
      render();
      toast('Карта выбрана — введи ассоциацию и нажми «Загадать»');
      return;
    }
    if (state.stage === 'CARD_COLLECTION' && !state.youAreMaster) {
      await sendOrHttp(
        { type: 'PLAY_CARD', cardId: id },
        () => api(`/api/games/${gameId}/play`, { method: 'POST', body: { cardId: id } })
      );
      return;
    }
    softToast('Сейчас эту карту положить нельзя');
    return;
  }

  if (zone === 'table') {
    if (!canVoteFor(id)) {
      if (state.youAreMaster) softToast('Ведущий не голосует');
      else if (id === state.yourPlayedCardId) softToast('Нельзя голосовать за свою карту');
      else softToast('Сейчас голосовать нельзя');
      return;
    }
    await sendOrHttp(
      { type: 'VOTE', cardId: id },
      () => api(`/api/games/${gameId}/vote`, { method: 'POST', body: { cardId: id } })
    );
  }
}

els.deckGrid.addEventListener('click', async (e) => {
  const btn = e.target.closest('[data-deck]');
  if (!btn || state.youReady) return;
  const id = btn.dataset.deck;
  if (selectedDecks.has(id)) selectedDecks.delete(id);
  else selectedDecks.add(id);
  try {
    state = await api(`/api/games/${gameId}/decks`, {
      method: 'POST',
      body: { deckIds: [...selectedDecks].map(Number) },
    });
    render();
  } catch (err) {
    toast(err.message);
  }
});

els.readyBtn.addEventListener('click', async () => {
  try {
    state = await api(`/api/games/${gameId}/ready`, { method: 'POST' });
    syncIdentityFromState();
    connectWs();
    ensurePolling(2000);
    render();
  } catch (err) {
    toast(err.message);
  }
});

els.unreadyBtn.addEventListener('click', async () => {
  try {
    state = await api(`/api/games/${gameId}/unready`, { method: 'POST' });
    syncIdentityFromState();
    ensurePolling(2000);
    render();
  } catch (err) {
    toast(err.message);
  }
});

els.handGrid.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-hand]');
  if (!btn) return;
  openInspect('hand', btn.dataset.hand, btn.dataset.zoom);
});

els.tableGrid.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-table]');
  if (!btn) return;
  if (state.stage !== 'VOTING' && state.stage !== 'RESULTS' && state.stage !== 'GAME_OVER') return;
  openInspect('table', btn.dataset.table, btn.dataset.zoom);
});

if (els.cardInspectConfirm) {
  els.cardInspectConfirm.addEventListener('click', (e) => {
    e.stopPropagation();
    confirmInspected();
  });
}
if (els.cardInspectClose) {
  els.cardInspectClose.addEventListener('click', (e) => {
    e.stopPropagation();
    closeInspect();
  });
}
if (els.cardInspect) {
  els.cardInspect.addEventListener('click', (e) => {
    if (e.target === els.cardInspect) closeInspect();
  });
}

els.submitAssocBtn.addEventListener('click', () => {
  if (!state.youAreMaster) {
    softToast('Только ведущий загадывает тему');
    return;
  }
  if (!selectedHandCard) {
    toast('Сначала приблизь карту и нажми «Выбрать»');
    return;
  }
  const association = els.associationInput.value.trim();
  if (!association) {
    toast('Введи ассоциацию');
    return;
  }
  sendOrHttp(
    { type: 'SET_ASSOCIATION', association, cardId: selectedHandCard },
    () => api(`/api/games/${gameId}/association`, {
      method: 'POST',
      body: { association, cardId: selectedHandCard },
    })
  );
});

els.nextRoundBtn.addEventListener('click', () => {
  sendOrHttp(
    { type: 'NEXT_ROUND' },
    () => api(`/api/games/${gameId}/next-round`, { method: 'POST' })
  );
});

async function setBot(enabled) {
  try {
    state = await api(`/api/games/${gameId}/bot`, {
      method: 'POST',
      body: { yes: enabled },
    });
    syncIdentityFromState();
    render();
  } catch (err) {
    toast(err.message);
  }
}
if (els.botVoteYesBtn) els.botVoteYesBtn.addEventListener('click', () => setBot(true));
if (els.botVoteNoBtn) els.botVoteNoBtn.addEventListener('click', () => setBot(false));

const leaveBtn = document.getElementById('leaveBtn');
if (leaveBtn) {
  leaveBtn.addEventListener('click', () => leaveRoom());
}
const lobbyLink = document.getElementById('lobbyLink');
if (lobbyLink) {
  lobbyLink.addEventListener('click', (e) => {
    e.preventDefault();
    leaveRoom();
  });
}

window.addEventListener('pagehide', () => {
  if (intentionalLeave) return;
  if (state && state.status === 'WAITING') {
    intentionalLeave = true;
    try {
      navigator.sendBeacon(
        `/api/games/${gameId}/leave`,
        new Blob([], { type: 'application/json' })
      );
    } catch (_) {}
  }
});

init();
})();
