const { api, toast, statusLabel, loadMe, renderUserChip } = window.Imadj;

const gameList = document.getElementById('gameList');
const userChip = document.getElementById('userChip');
const authModal = document.getElementById('authModal');
const authBtn = document.getElementById('authBtn');
let me = null;

async function refreshMe() {
  me = await loadMe();
  renderUserChip(me, userChip);
  authBtn.textContent = me?.id ? 'Выйти' : 'Войти';
}

async function loadGames() {
  const games = await api('/api/games');
  const list = Array.isArray(games) ? games : [];
  if (!list.length) {
    gameList.innerHTML = '<div class="empty">Пока нет комнат — создай первую</div>';
    return;
  }
  gameList.innerHTML = list.map(g => `
    <article class="game-card">
      <div>
        <strong>${escapeHtml(g.name)}</strong>
        <div class="muted small">${g.playerCount}/${g.maxPlayers} игроков · id ${escapeHtml(g.id)}</div>
      </div>
      <div style="display:grid;gap:0.45rem;justify-items:end">
        <span class="badge ${g.status === 'WAITING' ? 'waiting' : 'playing'}">${statusLabel(g.status, g.stage)}</span>
        <a class="btn btn-primary" href="/game.html?id=${encodeURIComponent(g.id)}">Войти</a>
      </div>
    </article>
  `).join('');
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

async function createAndEnter(name) {
  const created = await api('/api/games', { method: 'POST', body: { name } });
  if (!created?.id) {
    throw new Error('Сервер не вернул id комнаты');
  }
  await api(`/api/games/${created.id}/join`, { method: 'POST' });
  window.location.assign(`/game.html?id=${encodeURIComponent(created.id)}`);
}

document.getElementById('createForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const name = String(new FormData(e.target).get('name') || '').trim();
  if (!name) {
    toast('Введи название комнаты');
    return;
  }
  try {
    await createAndEnter(name);
  } catch (err) {
    toast(err.message || 'Не удалось создать комнату');
  }
});

document.getElementById('refreshBtn').addEventListener('click', () => {
  loadGames().catch(err => toast(err.message));
});

authBtn.addEventListener('click', async () => {
  if (me?.id) {
    try {
      await api('/api/auth/logout', { method: 'POST' });
      await refreshMe();
      toast('Вы вышли');
    } catch (err) {
      toast(err.message);
    }
    return;
  }
  authModal.classList.add('open');
});

document.getElementById('closeAuth').addEventListener('click', () => authModal.classList.remove('open'));

document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    const isLogin = tab.dataset.tab === 'login';
    document.getElementById('loginForm').hidden = !isLogin;
    document.getElementById('registerForm').hidden = isLogin;
  });
});

document.getElementById('loginForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  try {
    await api('/api/auth/login', {
      method: 'POST',
      body: { username: fd.get('username'), password: fd.get('password') },
    });
    authModal.classList.remove('open');
    await refreshMe();
    toast('С возвращением');
  } catch (err) {
    toast(err.message);
  }
});

document.getElementById('registerForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  try {
    await api('/api/auth/register', {
      method: 'POST',
      body: {
        username: fd.get('username'),
        displayName: fd.get('displayName'),
        password: fd.get('password'),
      },
    });
    authModal.classList.remove('open');
    await refreshMe();
    toast('Аккаунт создан');
  } catch (err) {
    toast(err.message);
  }
});

(async () => {
  try {
    await refreshMe();
    await loadGames();
    setInterval(() => loadGames().catch(() => {}), 3000);
  } catch (err) {
    toast(err.message);
  }
})();
