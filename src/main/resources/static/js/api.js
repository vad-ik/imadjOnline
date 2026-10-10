/* All helpers live only on window.Imadj — no global function/const names. */
(function () {
  async function api(path, options = {}) {
    const headers = { ...(options.headers || {}) };
    let body = options.body;

    if (body && !(body instanceof FormData) && typeof body === 'object') {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(body);
    }

    const method = options.method || (body ? 'POST' : 'GET');
    console.log('[api]', method, path);

    const res = await fetch(path, {
      method,
      credentials: 'same-origin',
      cache: 'no-store',
      headers,
      body,
    });

    if (res.status === 204) return null;

    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = { message: text };
    }

    console.log('[api]', method, path, '->', res.status);

    if (!res.ok) {
      const message =
        (data && (data.message || data.error || data.title)) ||
        text ||
        res.statusText ||
        'Request failed';
      const err = new Error(message);
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }

  function toast(message) {
    let el = document.getElementById('toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'toast';
      el.className = 'toast';
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.classList.add('show');
    clearTimeout(el._t);
    el._t = setTimeout(() => el.classList.remove('show'), 4000);
  }

  function statusLabel(status, stage) {
    if (status === 'WAITING') return 'Лобби';
    if (status === 'FINISHED' || stage === 'GAME_OVER') return 'Финал';
    const map = {
      THEME_SELECTION: 'Ассоциация',
      CARD_COLLECTION: 'Карты на стол',
      VOTING: 'Голосование',
      RESULTS: 'Итоги раунда',
      GAME_OVER: 'Финал',
      LOBBY: 'Идёт',
    };
    return map[stage] || 'Игра';
  }

  async function loadMe() {
    return api('/api/auth/me');
  }

  function renderUserChip(user, mount) {
    if (!mount) return;
    if (!user || !user.id) {
      mount.innerHTML = `<span class="muted small">Гость · ${user?.displayName || ''}</span>`;
      return;
    }
    const avatar = user.avatarUrl
      ? `<img src="${user.avatarUrl}" alt="">`
      : `<div class="avatar"></div>`;
    mount.innerHTML = `${avatar}<span>${user.displayName}</span>`;
  }

  function openLightbox(url) {
    let box = document.getElementById('lightbox');
    if (!box) {
      box = document.createElement('div');
      box.id = 'lightbox';
      box.className = 'lightbox';
      box.innerHTML = `<button class="btn lightbox-close" type="button">Закрыть</button><img alt="card">`;
      document.body.appendChild(box);
      box.addEventListener('click', (e) => {
        if (e.target === box || e.target.classList.contains('lightbox-close')) {
          box.classList.remove('open');
        }
      });
    }
    box.querySelector('img').src = url;
    box.classList.add('open');
  }

  function setDebug(msg) {
    const bar = document.getElementById('debugBar');
    if (bar) bar.textContent = '[' + new Date().toLocaleTimeString() + '] ' + msg;
  }

  window.Imadj = { api, toast, statusLabel, loadMe, renderUserChip, openLightbox, setDebug };
})();
