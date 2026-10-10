(function () {
const { api, toast, loadMe, openLightbox, setDebug } = window.Imadj || {};
if (!api) {
  document.getElementById('debugBar').textContent = 'FATAL: api.js не загрузился';
  throw new Error('Imadj api missing');
}

if (window.pdfjsLib) {
  pdfjsLib.GlobalWorkerOptions.workerSrc =
    'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js';
}

const guestGate = document.getElementById('guestGate');
const profileRoot = document.getElementById('profileRoot');
const avatarPreview = document.getElementById('avatarPreview');
const displayName = document.getElementById('displayName');
const usernameLine = document.getElementById('usernameLine');
const myDecks = document.getElementById('myDecks');
const publicDecks = document.getElementById('publicDecks');
const deckViewer = document.getElementById('deckViewer');
const deckViewerTitle = document.getElementById('deckViewerTitle');
const deckViewerHint = document.getElementById('deckViewerHint');
const deckEditActions = document.getElementById('deckEditActions');
const cardGrid = document.getElementById('cardGrid');
const pageInfo = document.getElementById('pageInfo');
const newDeckModal = document.getElementById('newDeckModal');
const newDeckBtn = document.getElementById('newDeckBtn');
const closeDeckModal = document.getElementById('closeDeckModal');
const sheetModal = document.getElementById('sheetModal');
const sheetCutBtn = document.getElementById('sheetCutBtn');
const sheetFile = document.getElementById('sheetFile');
const sheetPreview = document.getElementById('sheetPreview');
const sheetSourceInfo = document.getElementById('sheetSourceInfo');
const sheetCountHint = document.getElementById('sheetCountHint');
const sheetProgress = document.getElementById('sheetProgress');
const sheetCutConfirm = document.getElementById('sheetCutConfirm');

const sheetInputs = {
  w: document.getElementById('sheetCardW'),
  h: document.getElementById('sheetCardH'),
  x: document.getElementById('sheetOffsetX'),
  y: document.getElementById('sheetOffsetY'),
  cols: document.getElementById('sheetCols'),
  rows: document.getElementById('sheetRows'),
  rot: document.getElementById('sheetRotation'),
};

let me = null;
let currentDeckId = null;
let currentDeckOwnerId = null;
let deckCanEdit = false;
let page = 0;
const pageSize = 24;
let totalPages = 0;

/** Full-resolution preview source (image or first PDF page). */
let sourceCanvas = null;
/** Loaded PDF for multi-page cut; null for plain images. */
let pdfDoc = null;
let pdfRenderScale = 1;
let pdfPageCount = 1;
const PREVIEW_MAX = 900;

function openModal() {
  if (!newDeckModal) {
    toast('Модалка не найдена');
    return;
  }
  newDeckModal.classList.add('open');
  newDeckModal.setAttribute('aria-hidden', 'false');
}

function closeModal() {
  if (!newDeckModal) return;
  newDeckModal.classList.remove('open');
  newDeckModal.setAttribute('aria-hidden', 'true');
}

function openSheetModal() {
  if (!currentDeckId || !deckCanEdit) {
    toast(deckCanEdit ? 'Сначала открой колоду' : 'Нарезка только для автора колоды');
    return;
  }
  sheetModal.classList.add('open');
  sheetModal.setAttribute('aria-hidden', 'false');
  updateSheetHints();
  drawSheetPreview();
}

function closeSheetModal() {
  sheetModal.classList.remove('open');
  sheetModal.setAttribute('aria-hidden', 'true');
  sheetProgress.hidden = true;
}

function iOwnDeck(deck) {
  return !!(me?.id && deck?.ownerId && String(me.id) === String(deck.ownerId));
}

async function init() {
  setDebug?.('loadMe…');
  me = await loadMe();
  setDebug?.('me id=' + (me && me.id));
  if (!me?.id) {
    guestGate.hidden = false;
    profileRoot.hidden = true;
  } else {
    guestGate.hidden = true;
    profileRoot.hidden = false;
    displayName.value = me.displayName || '';
    usernameLine.textContent = `@${me.username}`;
    avatarPreview.src = me.avatarUrl || '';
    avatarPreview.style.background = '#1d3340';
    await loadMyDecks();
  }
  await loadPublicDecks();
  setDebug?.('profile ready');
}

async function loadMyDecks() {
  if (!myDecks || !me?.id) return;
  const decks = await api('/api/decks/mine');
  if (!decks.length) {
    myDecks.innerHTML = '<div class="empty">Пока нет колод</div>';
    return;
  }
  myDecks.innerHTML = decks.map(d => `
    <button type="button" class="deck-option" data-open-mine="${d.id}" data-owner="${d.ownerId || ''}">
      <strong>${escapeHtml(d.name)}</strong>
      <div class="meta">${d.cardCount} карт · ${d.isPublic ? 'публичная' : 'приватная'}</div>
    </button>
  `).join('');
}

async function loadPublicDecks() {
  if (!publicDecks) return;
  const decks = await api('/api/decks/public');
  if (!decks.length) {
    publicDecks.innerHTML = '<div class="empty">Публичных колод пока нет</div>';
    return;
  }
  publicDecks.innerHTML = decks.map(d => {
    const mine = iOwnDeck(d);
    return `
    <button type="button" class="deck-option ${mine ? 'selected' : ''}" data-open-public="${d.id}" data-owner="${d.ownerId || ''}">
      <strong>${escapeHtml(d.name)}</strong>
      <div class="meta">${d.cardCount} карт${mine ? ' · твоя' : ''}</div>
    </button>`;
  }).join('');
}

async function openDeck(id, name, ownerId, forceEdit) {
  currentDeckId = id;
  currentDeckOwnerId = ownerId != null ? String(ownerId) : null;
  deckCanEdit = !!forceEdit || !!(me?.id && currentDeckOwnerId && String(me.id) === currentDeckOwnerId);
  if (deckViewer) deckViewer.hidden = false;
  if (deckViewerTitle) deckViewerTitle.textContent = name || `Колода #${id}`;
  if (deckEditActions) deckEditActions.hidden = !deckCanEdit;
  if (deckViewerHint) {
    deckViewerHint.textContent = deckCanEdit
      ? 'Режим автора: можно добавлять, нарезать и удалять.'
      : 'Только просмотр. Листай страницами, клик по карте — увеличить.';
  }
  page = 0;
  await loadCards();
}

async function loadCards() {
  if (!currentDeckId) return;
  const data = await api(`/api/decks/${currentDeckId}/cards?page=${page}&size=${pageSize}`);
  totalPages = data.totalPages || 0;
  pageInfo.textContent = `стр. ${page + 1} / ${Math.max(totalPages, 1)} · ${data.totalElements} карт`;
  const bust = Date.now();
  cardGrid.innerHTML = (data.items || []).map(c => {
    const url = c.imageUrl
      ? (c.imageUrl + (c.imageUrl.includes('?') ? '&' : '?') + 't=' + bust)
      : '';
    const meta = deckCanEdit ? 'удалить · зум' : 'зум';
    const delAttr = deckCanEdit ? ` data-del="${c.id}"` : '';
    return `
    <button type="button" class="card-tile" data-zoom="${url}"${delAttr}>
      <img src="${url}" alt="card">
      <div class="meta">${meta}</div>
    </button>`;
  }).join('') || '<div class="empty">Карт нет</div>';
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

function sheetParams() {
  const rotRaw = sheetInputs.rot?.value || '0';
  const mirrorCols = rotRaw === 'mirror-cols';
  const flipColsUd = rotRaw === 'flip-cols-ud';
  return {
    w: Math.max(8, Number(sheetInputs.w.value) || 8),
    h: Math.max(8, Number(sheetInputs.h.value) || 8),
    x: Math.max(0, Number(sheetInputs.x.value) || 0),
    y: Math.max(0, Number(sheetInputs.y.value) || 0),
    cols: Math.max(1, Math.min(40, Number(sheetInputs.cols.value) || 1)),
    rows: Math.max(1, Math.min(40, Number(sheetInputs.rows.value) || 1)),
    rot: mirrorCols || flipColsUd ? 0 : Number(rotRaw),
    mirrorCols,
    flipColsUd,
  };
}

/** Rotation for a cell. */
function cellRotation(p, colIndex) {
  if (p.mirrorCols) {
    // Facing columns: bottom toward the gutter (left −90, right +90).
    return colIndex % 2 === 0 ? -90 : 90;
  }
  if (p.flipColsUd) {
    // Left: bottom at top (180°). Right: top at top (0°).
    return colIndex % 2 === 0 ? 180 : 0;
  }
  return p.rot;
}

function updateSheetHints() {
  const p = sheetParams();
  const perPage = p.cols * p.rows;
  const pages = pdfDoc ? pdfPageCount : 1;
  const n = perPage * pages;
  let hint = pdfDoc && pages > 1
    ? `Получится ${n} карт (${perPage} × ${pages} стр.)`
    : `Получится ${n} карт`;
  if ((p.mirrorCols || p.flipColsUd) && p.cols !== 2) {
    hint += ' · режим «2 столбца»: чётные слева / нечётные справа';
  }
  sheetCountHint.textContent = hint;
  sheetCutConfirm.disabled = !sourceCanvas;
}

function cellBottomSide(p, colIndex) {
  if (p.mirrorCols) {
    return colIndex % 2 === 0 ? 'left' : 'right';
  }
  if (p.flipColsUd) {
    return colIndex % 2 === 0 ? 'top' : 'bottom';
  }
  if (p.rot === 90) return 'left';
  if (p.rot === -90) return 'right';
  if (p.rot === 180) return 'top';
  return 'bottom';
}

function drawBottomMarker(ctx, x, y, w, h, side, scale) {
  // Dot = where the card's bottom sits on the sheet.
  const cx = x + w / 2;
  const cy = y + h / 2;
  const inset = Math.min(w, h) * 0.18;
  let mx = cx;
  let my = cy;
  if (side === 'left') {
    mx = x + inset;
    my = cy;
  } else if (side === 'right') {
    mx = x + w - inset;
    my = cy;
  } else if (side === 'top') {
    mx = cx;
    my = y + inset;
  } else {
    mx = cx;
    my = y + h - inset;
  }
  ctx.beginPath();
  ctx.fillStyle = 'rgba(227,163,75,0.95)';
  ctx.arc(mx, my, Math.max(2.5, 4 * scale), 0, Math.PI * 2);
  ctx.fill();
}

function drawSheetPreview() {
  const ctx = sheetPreview.getContext('2d');
  if (!sourceCanvas) {
    sheetPreview.width = 320;
    sheetPreview.height = 180;
    ctx.fillStyle = '#0a1216';
    ctx.fillRect(0, 0, sheetPreview.width, sheetPreview.height);
    ctx.fillStyle = '#9bb3bd';
    ctx.font = '14px sans-serif';
    ctx.fillText('Выбери картинку или PDF', 16, 96);
    return;
  }

  const sw = sourceCanvas.width;
  const sh = sourceCanvas.height;
  const scale = Math.min(1, PREVIEW_MAX / Math.max(sw, sh));
  const pw = Math.max(1, Math.round(sw * scale));
  const ph = Math.max(1, Math.round(sh * scale));
  sheetPreview.width = pw;
  sheetPreview.height = ph;
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(sourceCanvas, 0, 0, pw, ph);

  const p = sheetParams();
  ctx.save();
  ctx.strokeStyle = 'rgba(227,163,75,0.95)';
  ctx.lineWidth = Math.max(1, 1.5 * scale);
  ctx.setLineDash([6 * scale, 4 * scale]);
  for (let r = 0; r < p.rows; r++) {
    for (let c = 0; c < p.cols; c++) {
      const x = (p.x + c * p.w) * scale;
      const y = (p.y + r * p.h) * scale;
      const w = p.w * scale;
      const h = p.h * scale;
      ctx.strokeRect(x + 0.5, y + 0.5, w, h);
      drawBottomMarker(ctx, x, y, w, h, cellBottomSide(p, c), scale);
    }
  }
  ctx.restore();
  const pdfNote = pdfDoc ? ` · PDF ${pdfPageCount} стр. (превью 1-я)` : '';
  sheetSourceInfo.textContent = `Исходник ${sw}×${sh}px · превью ×${scale.toFixed(2)}${pdfNote}`;
}

async function loadSheetFile(file) {
  if (!file) return;
  sheetCutConfirm.disabled = true;
  sheetSourceInfo.textContent = 'Загрузка…';
  pdfDoc = null;
  pdfPageCount = 1;
  pdfRenderScale = 1;
  try {
    if (file.type === 'application/pdf' || /\.pdf$/i.test(file.name)) {
      const loaded = await loadPdf(file);
      pdfDoc = loaded.pdf;
      pdfRenderScale = loaded.scale;
      pdfPageCount = pdfDoc.numPages;
      sourceCanvas = await renderPdfPageToCanvas(pdfDoc, 1, pdfRenderScale);
    } else if (file.type.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/i.test(file.name)) {
      sourceCanvas = await imageFileToCanvas(file);
    } else {
      throw new Error('Нужна картинка или PDF');
    }
    updateSheetHints();
    drawSheetPreview();
    toast(pdfDoc ? `PDF: ${pdfPageCount} стр.` : 'Лист загружен');
  } catch (err) {
    sourceCanvas = null;
    pdfDoc = null;
    updateSheetHints();
    drawSheetPreview();
    toast(err.message || 'Не удалось открыть файл');
  } finally {
    sheetFile.value = '';
  }
}

function imageFileToCanvas(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      const c = document.createElement('canvas');
      c.width = img.naturalWidth;
      c.height = img.naturalHeight;
      c.getContext('2d').drawImage(img, 0, 0);
      resolve(c);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('Не удалось прочитать картинку'));
    };
    img.src = url;
  });
}

async function loadPdf(file) {
  if (!window.pdfjsLib) {
    throw new Error('PDF.js не загрузился');
  }
  const data = new Uint8Array(await file.arrayBuffer());
  const pdf = await pdfjsLib.getDocument({ data }).promise;
  const page = await pdf.getPage(1);
  const base = page.getViewport({ scale: 1 });
  const target = Math.min(2400, Math.max(1200, base.width * 2));
  const scale = target / base.width;
  return { pdf, scale };
}

async function renderPdfPageToCanvas(pdf, pageNumber, scale) {
  const page = await pdf.getPage(pageNumber);
  const viewport = page.getViewport({ scale });
  const c = document.createElement('canvas');
  c.width = Math.floor(viewport.width);
  c.height = Math.floor(viewport.height);
  await page.render({ canvasContext: c.getContext('2d'), viewport }).promise;
  return c;
}

function cropTile(fromCanvas, sx, sy, sw, sh, rot) {
  const raw = document.createElement('canvas');
  raw.width = sw;
  raw.height = sh;
  const rctx = raw.getContext('2d');
  rctx.fillStyle = '#fff';
  rctx.fillRect(0, 0, sw, sh);
  rctx.drawImage(fromCanvas, sx, sy, sw, sh, 0, 0, sw, sh);

  if (!rot) return raw;

  const out = document.createElement('canvas');
  const octx = out.getContext('2d');
  octx.imageSmoothingEnabled = true;
  if (rot === 90 || rot === -90) {
    out.width = sh;
    out.height = sw;
  } else {
    out.width = sw;
    out.height = sh;
  }
  octx.translate(out.width / 2, out.height / 2);
  octx.rotate((rot * Math.PI) / 180);
  octx.drawImage(raw, -sw / 2, -sh / 2);
  return out;
}

function canvasToBlob(canvas) {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('blob failed'))),
      'image/jpeg',
      0.92
    );
  });
}

async function cutPageTiles(fromCanvas, p, pageIndex, total, okStart) {
  let ok = okStart;
  for (let r = 0; r < p.rows; r++) {
    for (let c = 0; c < p.cols; c++) {
      const sx = Math.round(p.x + c * p.w);
      const sy = Math.round(p.y + r * p.h);
      const tile = cropTile(fromCanvas, sx, sy, p.w, p.h, cellRotation(p, c));
      const blob = await canvasToBlob(tile);
      const fd = new FormData();
      const name = pdfDoc
        ? `card_p${pageIndex}_r${r + 1}_c${c + 1}.jpg`
        : `card_r${r + 1}_c${c + 1}.jpg`;
      fd.append('file', blob, name);
      sheetProgress.textContent = `Загрузка ${ok + 1} / ${total}…`;
      await api(`/api/decks/${currentDeckId}/cards`, { method: 'POST', body: fd });
      ok += 1;
    }
  }
  return ok;
}

async function cutAndUpload() {
  if (!sourceCanvas || !currentDeckId) return;
  const p = sheetParams();
  const pages = pdfDoc ? pdfPageCount : 1;
  const perPage = p.cols * p.rows;
  const total = perPage * pages;
  const maxX = p.x + p.cols * p.w;
  const maxY = p.y + p.rows * p.h;
  if (maxX > sourceCanvas.width + 1 || maxY > sourceCanvas.height + 1) {
    if (!confirm(`Сетка выходит за край листа (${sourceCanvas.width}×${sourceCanvas.height}). Всё равно нарезать?`)) {
      return;
    }
  }

  sheetCutConfirm.disabled = true;
  sheetProgress.hidden = false;
  let ok = 0;
  try {
    if (pdfDoc) {
      for (let pageNum = 1; pageNum <= pages; pageNum++) {
        sheetProgress.textContent = `Страница ${pageNum}/${pages}…`;
        const pageCanvas = pageNum === 1
          ? sourceCanvas
          : await renderPdfPageToCanvas(pdfDoc, pageNum, pdfRenderScale);
        ok = await cutPageTiles(pageCanvas, p, pageNum, total, ok);
      }
    } else {
      ok = await cutPageTiles(sourceCanvas, p, 1, total, 0);
    }
    await loadCards();
    await loadMyDecks();
    await loadPublicDecks();
    toast(`Добавлено карт: ${ok}`);
    closeSheetModal();
  } catch (err) {
    toast(err.message || 'Ошибка загрузки');
    sheetProgress.textContent = `Ошибка после ${ok} из ${total}`;
  } finally {
    sheetCutConfirm.disabled = !sourceCanvas;
  }
}

document.getElementById('saveProfile').addEventListener('click', async () => {
  try {
    me = await api('/api/auth/profile', {
      method: 'PUT',
      body: { displayName: displayName.value.trim() },
    });
    toast('Профиль сохранён');
  } catch (err) {
    toast(err.message);
  }
});

document.getElementById('avatarInput').addEventListener('change', async (e) => {
  const file = e.target.files?.[0];
  if (!file) return;
  avatarPreview.src = URL.createObjectURL(file);
  const fd = new FormData();
  fd.append('file', file);
  try {
    me = await api('/api/auth/avatar', { method: 'POST', body: fd });
    if (me.avatarUrl) {
      avatarPreview.src = me.avatarUrl + (me.avatarUrl.includes('?') ? '&' : '?') + 't=' + Date.now();
    }
    toast('Аватар обновлён');
  } catch (err) {
    toast(err.message);
  } finally {
    e.target.value = '';
  }
});

if (newDeckBtn) {
  newDeckBtn.addEventListener('click', (e) => {
    e.preventDefault();
    openModal();
  });
}
if (closeDeckModal) closeDeckModal.addEventListener('click', closeModal);
if (newDeckModal) {
  newDeckModal.addEventListener('click', (e) => {
    if (e.target === newDeckModal) closeModal();
  });
}

if (sheetCutBtn) sheetCutBtn.addEventListener('click', openSheetModal);
document.getElementById('sheetModalClose').addEventListener('click', closeSheetModal);
sheetModal.addEventListener('click', (e) => {
  if (e.target === sheetModal) closeSheetModal();
});
sheetFile.addEventListener('change', (e) => loadSheetFile(e.target.files?.[0]));
Object.values(sheetInputs).forEach((el) => {
  el.addEventListener('input', () => {
    updateSheetHints();
    drawSheetPreview();
  });
});
sheetCutConfirm.addEventListener('click', () => cutAndUpload());

document.getElementById('createDeckForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  try {
    const deck = await api('/api/decks', {
      method: 'POST',
      body: {
        name: String(fd.get('name') || '').trim(),
        isPublic: fd.get('isPublic') === 'on',
      },
    });
    closeModal();
    e.target.reset();
    await loadMyDecks();
    await loadPublicDecks();
    await openDeck(deck.id, deck.name, deck.ownerId, true);
    toast('Колода создана');
  } catch (err) {
    toast(err.message);
  }
});

if (myDecks) {
  myDecks.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-open-mine]');
    if (!btn) return;
    await openDeck(btn.dataset.openMine, btn.querySelector('strong')?.textContent, btn.dataset.owner, true);
  });
}
if (publicDecks) {
  publicDecks.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-open-public]');
    if (!btn) return;
    await openDeck(btn.dataset.openPublic, btn.querySelector('strong')?.textContent, btn.dataset.owner, false);
  });
}
const refreshPublicBtn = document.getElementById('refreshPublicBtn');
if (refreshPublicBtn) {
  refreshPublicBtn.addEventListener('click', () => {
    loadPublicDecks().catch(err => toast(err.message));
  });
}

document.getElementById('cardFiles').addEventListener('change', async (e) => {
  const files = [...(e.target.files || [])];
  if (!files.length || !currentDeckId || !deckCanEdit) return;
  try {
    for (const file of files) {
      const fd = new FormData();
      fd.append('file', file);
      await api(`/api/decks/${currentDeckId}/cards`, { method: 'POST', body: fd });
    }
    await loadCards();
    await loadMyDecks();
    await loadPublicDecks();
    toast(`Загружено: ${files.length}`);
  } catch (err) {
    toast(err.message);
  } finally {
    e.target.value = '';
  }
});

cardGrid.addEventListener('click', async (e) => {
  const btn = e.target.closest('[data-zoom]');
  if (!btn) return;
  if (deckCanEdit && btn.dataset.del && e.target.closest('.meta')) {
    if (!confirm('Удалить карту?')) return;
    try {
      await api(`/api/decks/${currentDeckId}/cards/${btn.dataset.del}`, { method: 'DELETE' });
      await loadCards();
      await loadMyDecks();
      await loadPublicDecks();
    } catch (err) {
      toast(err.message);
    }
    return;
  }
  openLightbox(btn.dataset.zoom);
});

document.getElementById('deleteDeckBtn').addEventListener('click', async () => {
  if (!currentDeckId || !deckCanEdit || !confirm('Удалить колоду целиком?')) return;
  try {
    await api(`/api/decks/${currentDeckId}`, { method: 'DELETE' });
    currentDeckId = null;
    deckCanEdit = false;
    if (deckViewer) deckViewer.hidden = true;
    await loadMyDecks();
    await loadPublicDecks();
    toast('Колода удалена');
  } catch (err) {
    toast(err.message);
  }
});

const flipDeckBtn = document.getElementById('flipDeckBtn');
if (flipDeckBtn) {
  flipDeckBtn.addEventListener('click', async () => {
    if (!currentDeckId || !deckCanEdit) {
      toast(deckCanEdit ? 'Сначала открой колоду' : 'Только автор может переворачивать');
      return;
    }
    if (!confirm('Перевернуть все карты колоды на 180°?')) return;
    try {
      flipDeckBtn.disabled = true;
      toast('Переворачиваю…');
      const res = await api(`/api/decks/${currentDeckId}/rotate?degrees=180`, {
        method: 'POST',
      });
      await loadCards();
      toast(`Перевёрнуто карт: ${res?.rotated ?? 'ок'}`);
    } catch (err) {
      console.error('flip deck', err);
      toast(err.message || 'Не удалось перевернуть');
    } finally {
      flipDeckBtn.disabled = false;
    }
  });
}

document.getElementById('prevPage').addEventListener('click', async () => {
  if (page <= 0) return;
  page -= 1;
  await loadCards();
});
document.getElementById('nextPage').addEventListener('click', async () => {
  if (page + 1 >= totalPages) return;
  page += 1;
  await loadCards();
});

init().catch(err => toast(err.message));
})();
