const $ = selector => document.querySelector(selector);
const statuses = { TODO: 'К выполнению', IN_PROGRESS: 'В работе', DONE: 'Готово' };
const priorities = { HIGH: 'Высокий', MEDIUM: 'Средний', LOW: 'Низкий' };
const state = { auth: null, workspaces: [], workspace: null, projects: [], project: null, members: [], people: [], filters: [], page: 0, epoch: 0, detailEpoch: 0, archived: false, query: {} };
let refreshFlight = null, toastTimer, notificationTimer;

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (['value', 'checked', 'disabled', 'hidden', 'required'].includes(key)) node[key] = value;
    else if (value != null) node.setAttribute(key, value);
  }
  children.flat(Infinity).forEach(child => { if (child != null) node.append(child instanceof Node ? child : document.createTextNode(String(child))); });
  return node;
}
const button = (text, action, cls = '') => el('button', { type: 'button', class: cls, onclick: () => run(action) }, text);
const field = (label, input, help) => el('label', {}, label, input, help ? el('span', { class: 'field-help' }, help) : null);
const input = (name, value = '', attrs = {}) => el('input', { name, value: value ?? '', ...attrs });
const area = (name, value = '', max = 5000) => el('textarea', { name, maxlength: max, value: value ?? '' });
const select = (name, options, value = '') => { const s = el('select', { name }, options.map(([v, text]) => el('option', { value: v }, text))); s.value = value ?? ''; return s; };
const tag = v => `"${v.id}:${v.version}"`;
const wsPath = () => `/workspaces/${state.workspace.id}`;
const projectPath = () => `${wsPath()}/projects/${state.project.id}`;
const admin = () => state.members.some(m => m.userId === state.auth?.user.id && m.role === 'ADMIN');
const date = v => v ? new Intl.DateTimeFormat('ru', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(new Date(v)) : 'Без срока';
const timestamp = v => v ? new Intl.DateTimeFormat('ru', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(v)) : '';
const initials = name => (name || '?').trim().split(/\s+/).slice(0, 2).map(p => p[0]).join('').toUpperCase();
const avatar = name => el('span', { class: 'avatar', title: name || 'Не назначен', 'aria-label': name || 'Не назначен' }, initials(name));
const overdue = task => task.status !== 'DONE' && task.dueDate && task.dueDate < new Date().toISOString().slice(0, 10);
const personName = id => state.people.find(p => p.id === id)?.displayName || state.members.find(p => p.userId === id)?.displayName || 'Не назначен';

function message(error) {
  if (error.status === 412) return 'Эти данные уже изменились. Твоя форма сохранена на экране. Перечитай карточку перед повторным редактированием.';
  if (error.status === 428) return 'Не удалось определить версию данных. Открой карточку заново.';
  if (error.status === 401) return 'Сессия завершена или данные входа неверны. Войди заново.';
  if (error.status === 403) return 'Недостаточно прав для этого действия.';
  if (error.status === 404) return 'Объект недоступен: он удалён, изменился доступ или код приглашения недействителен.';
  if (error.status === 409) return 'Действие конфликтует с текущими данными. Проверь архив проекта, повторное имя или состав участников.';
  if (error.status === 429) return `Слишком много попыток. Повтори через ${error.retryAfter || 60} сек.`;
  if (error.status === 503) return 'Сервис временно недоступен. Для восстановления пароля должна быть настроена почта.';
  if (error.status === 400) return 'Проверь поля формы: длину текста, email и пароль (10 символов, не более 72 байт UTF-8).';
  return error.message || 'Не удалось выполнить действие.';
}
function toast(text) { clearTimeout(toastTimer); $('#toast').textContent = text; $('#toast').hidden = false; toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 6500); }
async function run(action) { try { await action(); } catch (error) { toast(message(error)); } }

async function request(path, { method = 'GET', data, headers = {}, auth = true } = {}) {
  const options = { method, headers: { ...headers }, credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(20000) };
  if (data !== undefined) { options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(data); }
  if (auth && state.auth) options.headers.Authorization = `Bearer ${state.auth.accessToken}`;
  let response;
  try { response = await fetch(`/api/v1${path}`, options); }
  catch { throw new Error('Нет ответа сервера. Проверь соединение. Изменение могло сохраниться — перечитай данные перед повтором.'); }
  if (!response.ok) {
    const error = new Error('Не удалось выполнить запрос.');
    error.status = response.status; error.retryAfter = response.headers.get('Retry-After'); throw error;
  }
  return response.status === 204 ? null : response.json();
}
function setAuth(auth) { state.auth = { ...auth, renewAt: Date.now() + auth.expiresIn * 1000 - 30000 }; }
async function renew() {
  if (!refreshFlight) {
    const original = state.auth;
    refreshFlight = request('/auth/refresh', { method: 'POST', auth: false, data: { token: original.refreshToken } })
      .then(auth => { if (state.auth === original) setAuth(auth); })
      .catch(error => { if (state.auth === original) signedOut(); throw error; })
      .finally(() => { refreshFlight = null; });
  }
  return refreshFlight;
}
async function api(path, options = {}) {
  if (!state.auth) throw Object.assign(new Error(), { status: 401 });
  if (Date.now() >= state.auth.renewAt) await renew();
  const token = state.auth?.accessToken;
  try { return await request(path, options); }
  catch (error) {
    if (error.status !== 401 || !state.auth) throw error;
    if (state.auth.accessToken === token) await renew();
    return request(path, options);
  }
}
async function all(path) {
  const items = []; let page = 0, result;
  do { result = await api(`${path}${path.includes('?') ? '&' : '?'}size=100&page=${page++}`); items.push(...result.content); } while (page < result.totalPages);
  return items;
}

function formSubmit(form, callback) {
  const error = el('p', { class: 'error', role: 'alert' }); form.append(error);
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (form.dataset.busy) return;
    form.dataset.busy = 'true'; error.textContent = '';
    const submit = form.querySelector('[type=submit]'); submit.disabled = true;
    try { await callback(Object.fromEntries(new FormData(form)), error); }
    catch (e) { error.textContent = message(e); }
    finally { delete form.dataset.busy; submit.disabled = false; }
  });
  return form;
}
function modal(title, content, target = $('#modal')) {
  target.replaceChildren(el('div', { class: 'dialog-head' }, el('h2', { id: target.id === 'modal' ? 'modal-title' : 'task-title' }, title), button('×', () => target.close(), 'icon-button')),
    content);
  target.querySelector('.icon-button').setAttribute('aria-label', 'Закрыть');
  if (!target.open) target.showModal();
  return target;
}
function actions(label) { return el('div', { class: 'form-actions' }, el('button', { type: 'submit', class: 'primary' }, label)); }
async function confirmAction(title, text, action) {
  const content = el('div', {}, el('p', { class: 'description' }, text), el('div', { class: 'form-actions' },
    button('Отмена', () => $('#modal').close()), button('Подтвердить', async () => { await action(); $('#modal').close(); }, 'primary')));
  modal(title, content);
}
function signedOut() {
  clearInterval(notificationTimer); state.auth = null; state.epoch++; state.detailEpoch++;
  state.workspace = null; state.project = null;
  $('#modal').close(); $('#task-dialog').close(); renderAuth();
}

function renderAuth(mode = 'login') {
  const names = { login: 'С возвращением', register: 'Создай свою команду', forgot: 'Восстановление пароля', reset: 'Новый пароль' };
  const form = el('form');
  if (mode === 'register') form.append(field('Как тебя зовут', input('displayName', '', { required: true, maxlength: 120, autocomplete: 'name' })));
  if (mode !== 'reset') form.append(field('Email', input('email', '', { type: 'email', required: true, maxlength: 254, autocomplete: 'username', placeholder: 'you@company.ru' })));
  if (mode === 'reset') form.append(field('Код из письма', input('token', '', { required: true, maxlength: 128, autocomplete: 'one-time-code' })));
  if (mode !== 'forgot') form.append(field(mode === 'reset' ? 'Новый пароль' : 'Пароль', input('password', '', { type: 'password', required: true, minlength: 10, autocomplete: mode === 'login' ? 'current-password' : 'new-password' }), 'От 10 символов, до 72 байт UTF-8'));
  form.append(el('button', { type: 'submit', class: 'primary' }, { login: 'Войти в TeamFlow →', register: 'Создать аккаунт →', forgot: 'Отправить инструкцию', reset: 'Изменить пароль' }[mode]));
  formSubmit(form, async values => {
    if (mode === 'forgot') { await request('/auth/password/forgot', { method: 'POST', auth: false, data: values }); toast('Если аккаунт существует, инструкция придёт на почту.'); renderAuth('reset'); }
    else if (mode === 'reset') { await request('/auth/password/reset', { method: 'POST', auth: false, data: values }); toast('Пароль изменён. Войди заново.'); renderAuth(); }
    else { setAuth(await request(`/auth/${mode}`, { method: 'POST', auth: false, data: values })); await boot(); }
  });
  const auth = el('div', { class: 'auth-form' }, el('div', { class: 'eyebrow' }, 'ТВОЁ РАБОЧЕЕ ПРОСТРАНСТВО'), el('h2', {}, names[mode]),
    el('p', { class: 'muted' }, 'Проекты, задачи и люди — в одном месте.'),
    el('div', { class: 'auth-tabs' }, button('Вход', () => renderAuth('login'), mode === 'login' ? 'active' : ''), button('Регистрация', () => renderAuth('register'), mode === 'register' ? 'active' : '')),
    form, el('div', { class: 'auth-foot' }, button('Забыли пароль?', () => renderAuth('forgot'), 'quiet'), button('У меня есть код', () => renderAuth('reset'), 'quiet')));
  $('#app').replaceChildren(el('main', { class: 'auth-page', id: 'main' }, el('section', { class: 'auth-art' },
    el('div', { class: 'brand' }, el('img', { src: '/favicon.svg', alt: '' }), 'TeamFlow'),
    el('div', {}, el('h1', {}, 'Меньше хаоса.', el('br'), el('span', { class: 'accent' }, 'Больше общего результата.')),
      el('p', {}, 'Понятные задачи. Живые обсуждения. Команда, которая движется вместе.'), el('div', { class: 'flow-art', 'aria-hidden': 'true' }, el('span'), el('span'), el('span'))),
    el('p', { class: 'muted' }, 'От первой идеи до последнего «готово».')), el('section', { class: 'auth-body' }, auth)));
}

async function boot() {
  state.workspaces = await all('/workspaces');
  state.workspace = state.workspaces.find(w => w.id === state.workspace?.id) || state.workspaces[0] || null;
  if (state.workspace) await loadWorkspace(); else { state.projects = []; state.project = null; state.members = []; renderShell(); }
  clearInterval(notificationTimer);
  notificationTimer = setInterval(() => { if (state.auth && !document.hidden) updateUnread().catch(() => {}); }, 15000);
  await updateUnread();
}
async function loadWorkspace() {
  const epoch = ++state.epoch, workspaceId = state.workspace.id;
  state.detailEpoch++; $('#task-dialog').close();
  const [members, projects] = await Promise.all([all(`/workspaces/${workspaceId}/members`), all(`/workspaces/${workspaceId}/projects?archived=${state.archived}`)]);
  if (epoch !== state.epoch) return;
  state.members = members; state.projects = projects;
  state.project = projects.find(p => p.id === state.project?.id) || projects[0] || null;
  state.page = 0; state.query = {}; state.selectedFilter = ''; renderShell();
  if (state.project) await loadProject();
}
async function loadProject() {
  const epoch = ++state.epoch, path = projectPath();
  state.detailEpoch++; $('#task-dialog').close();
  $('#content').replaceChildren(el('div', { class: 'empty', role: 'status' }, 'Загружаем проект…'));
  const [project, people, filters] = await Promise.all([api(path), all(`${path}/members`), api(`${path}/filters`)]);
  if (epoch !== state.epoch) return;
  state.project = project; state.people = people; state.filters = filters;
  renderProject(); await loadBoard();
}
function renderShell() {
  const workspacePicker = select('workspace', state.workspaces.map(w => [w.id, w.name]), state.workspace?.id);
  workspacePicker.setAttribute('aria-label', 'Рабочее пространство');
  workspacePicker.addEventListener('change', () => run(async () => { state.workspace = state.workspaces.find(w => w.id === workspacePicker.value); state.project = null; state.archived = false; await loadWorkspace(); }));
  const sidebar = el('aside', { class: 'sidebar' }, el('div', { class: 'brand' }, el('img', { src: '/favicon.svg', alt: '' }), 'TeamFlow'),
    el('div', {}, field('РАБОЧЕЕ ПРОСТРАНСТВО', workspacePicker), el('div', { class: 'workspace-tools' }, button('+ Создать', () => createWorkspace(), 'small'), button('Вступить', () => acceptInvite(), 'small'))),
    el('nav', { 'aria-label': 'Проекты' }, el('div', { class: 'row between' }, el('span', { class: 'eyebrow' }, state.archived ? 'Архив проектов' : 'Мои проекты'), admin() ? button('+', () => createProject(), 'icon-button') : null),
      el('div', { class: 'project-list' }, state.projects.map(p => button(el('span', { class: 'row' }, el('span', { class: 'project-dot' }), el('span', { class: 'project-name' }, p.name)), async () => {
        state.project = p; state.page = 0; state.query = {}; state.selectedFilter = ''; renderShell(); await loadProject();
      }, `project-button ${p.id === state.project?.id ? 'active' : ''}`))),
      state.workspace ? button(state.archived ? '← Активные проекты' : 'Архив проектов', async () => { state.archived = !state.archived; state.project = null; await loadWorkspace(); }, 'small') : null),
    el('div', { class: 'sidebar-bottom row' }, avatar(state.auth.user.displayName), el('div', { class: 'grow' }, el('div', { class: 'user-name' }, state.auth.user.displayName), el('div', { class: 'muted' }, 'Всё начинается с команды'))));
  const header = el('header', { class: 'topbar' }, el('div', { class: 'breadcrumb row' }, el('span', { class: 'workspace-title' }, state.workspace?.name || 'Рабочие пространства'), ' / ', el('strong', {}, 'Доска задач')),
    el('div', { class: 'row' }, button('Уведомления', () => showNotifications(), 'small'), el('span', { id: 'unread', class: 'pill', 'aria-label': 'Непрочитанные уведомления' }, '0'),
      button('Выйти', async () => { try { await api('/auth/logout', { method: 'POST' }); } finally { signedOut(); } }, 'quiet small')));
  $('#app').replaceChildren(el('div', { class: 'shell' }, sidebar, el('main', { class: 'main', id: 'main' }, header, el('section', { id: 'content', class: 'content' }))));
  if (!state.project) $('#content').append(el('div', { class: 'empty' }, el('div', { class: 'empty-symbol', 'aria-hidden': 'true' }, '▤'),
    el('h2', {}, !state.workspace ? 'Здесь начинается работа команды' : state.archived ? 'В архиве пока пусто' : 'Первый проект — первый шаг'),
    el('p', {}, !state.workspace ? 'Создай рабочее пространство или введи код приглашения от администратора.' : admin() ? 'Объедини задачи в проект. Потом пригласи коллег и начни работу на доске.' : 'Попроси администратора добавить тебя в проект команды.'),
    !state.workspace ? button('Создать пространство', createWorkspace, 'primary') : admin() && !state.archived ? button('Создать проект', createProject, 'primary') : null));
  updateUnread().catch(() => {});
}

function renderProject() {
  const project = state.project;
  const content = $('#content');
  content.replaceChildren(el('div', { class: 'row between project-heading' }, el('div', { class: 'grow' }, el('p', { class: 'eyebrow' }, 'ПРОЕКТ / КОМАНДНАЯ РАБОТА'), el('h1', {}, project.name), el('p', { class: 'description' }, project.description || 'Одна доска. Общая цель.')),
    el('div', { class: 'row heading-actions' }, button('Участники', showTeam), admin() ? button(project.archived ? 'Восстановить' : 'В архив', () => archiveProject(), 'quiet') : null,
      !project.archived ? button('+ Задача', () => createTask(), 'primary') : null)));
  if (project.archived) content.append(el('p', { class: 'notice' }, 'Проект в архиве. Задачи и обсуждения доступны для чтения.'));
  const q = state.query;
  const search = input('q', q.q, { type: 'search', maxlength: 200, placeholder: 'Найти задачу…' });
  const filters = el('form', { class: 'toolbar' }, field('ПОИСК ПО ПРОЕКТУ', search), field('СТАТУС', select('status', [['', 'Любой'], ...Object.entries(statuses)], q.status)),
    field('ПРИОРИТЕТ', select('priority', [['', 'Любой'], ...Object.entries(priorities)], q.priority)),
    field('ИСПОЛНИТЕЛЬ', select('assigneeId', [['', 'Все'], ...state.people.map(p => [p.id, p.displayName]),
      ...(q.assigneeId && !state.people.some(p => p.id === q.assigneeId) ? [[q.assigneeId, 'Участник больше не в проекте']] : [])], q.assigneeId)),
    el('label', { class: 'check' }, input('overdue', 'true', { type: 'checkbox', checked: q.overdue === 'true' }), 'Просроченные'),
    el('button', { type: 'submit', class: 'small' }, 'Найти'), button('Сбросить', async () => { state.query = {}; state.selectedFilter = ''; state.page = 0; renderProject(); await loadBoard(); }, 'quiet small'));
  filters.firstChild.classList.add('search');
  filters.addEventListener('submit', e => { e.preventDefault(); state.query = Object.fromEntries(new FormData(filters)); state.selectedFilter = ''; $('select[name="saved-filter"]').value = ''; state.page = 0; run(loadBoard); });
  content.append(filters);
  const picker = select('saved-filter', [['', 'Мои сохранённые фильтры'], ...state.filters.map(f => [f.id, f.name])], state.selectedFilter);
  picker.setAttribute('aria-label', 'Сохранённые фильтры');
  picker.addEventListener('change', () => run(async () => {
    const f = state.filters.find(f => f.id === picker.value); if (!f) return;
    state.selectedFilter = f.id;
    state.query = { q: f.q || '', status: f.status || '', priority: f.priority || '', assigneeId: f.assigneeId || '', overdue: String(f.overdue) };
    state.page = 0; renderProject(); await loadBoard();
  }));
  content.append(el('div', { class: 'saved-bar' }, el('span', { class: 'muted', id: 'task-count' }, 'Обновляем задачи…'), picker, button('Сохранить фильтр', () => saveFilter(), 'quiet small'),
    button('Удалить фильтр', async () => { const f = state.filters.find(f => f.id === picker.value); if (!f) { toast('Сначала выбери сохранённый фильтр.'); return; }
      await api(`${projectPath()}/filters/${f.id}`, { method: 'DELETE', headers: { 'If-Match': tag(f) } }); state.filters = await api(`${projectPath()}/filters`); renderProject(); await loadBoard(); }, 'quiet small')),
    el('div', { id: 'board', class: 'board', 'aria-live': 'polite' }), el('div', { id: 'pager', class: 'pager' }));
}
async function loadBoard() {
  const epoch = ++state.epoch, path = projectPath();
  const params = new URLSearchParams({ page: state.page, size: 30 });
  Object.entries(state.query).forEach(([k, v]) => { if (v && v !== 'false') params.set(k, v); });
  $('#board').setAttribute('aria-busy', 'true');
  try {
    const tasks = await api(`${path}/tasks?${params}`);
    if (epoch !== state.epoch) return;
    if (tasks.totalPages > 0 && state.page >= tasks.totalPages) { state.page = tasks.totalPages - 1; return loadBoard(); }
    $('#task-count').textContent = `Найдено: ${tasks.totalElements} · На странице: ${tasks.content.length}`;
    $('#board').replaceChildren(...Object.entries(statuses).map(([status, title]) => {
      const items = tasks.content.filter(t => t.status === status);
      return el('section', { class: 'column', 'aria-label': title }, el('div', { class: 'column-header' }, el('span', { class: `status-dot ${status}` }), title, el('span', { class: 'count' }, items.length)),
        items.length ? items.map(taskCard) : el('div', { class: 'column-empty' }, 'На этой странице задач нет'));
    }));
    $('#pager').replaceChildren(el('span', {}, `Страница ${state.page + 1} из ${Math.max(1, tasks.totalPages)} · 30 задач на странице`),
      el('div', { class: 'row' }, el('button', { disabled: state.page === 0, onclick: () => run(async () => { state.page--; await loadBoard(); }) }, '← Назад'),
        el('button', { disabled: state.page + 1 >= tasks.totalPages, onclick: () => run(async () => { state.page++; await loadBoard(); }) }, 'Дальше →')));
  } catch (error) {
    if (epoch === state.epoch && $('#task-count')) $('#task-count').textContent = 'Не удалось обновить задачи. Показаны прежние данные.';
    throw error;
  } finally { if (epoch === state.epoch) $('#board')?.removeAttribute('aria-busy'); }
}
function taskCard(task) {
  const assigned = personName(task.assigneeId);
  return button(el('div', {}, el('div', { class: 'row between' }, el('span', { class: `priority ${task.priority}` }, priorities[task.priority]), el('span', { class: 'muted' }, `#${task.id.slice(0, 6)}`)),
    el('h3', {}, task.title), task.description ? el('p', {}, task.description) : null,
    el('div', { class: 'row between task-foot' }, el('span', { class: overdue(task) ? 'overdue' : '' }, `${overdue(task) ? '◷ ' : ''}${date(task.dueDate)}`), task.assigneeId ? avatar(assigned) : el('span', {}, 'Не назначен'))),
    () => openTask(task.id), 'task-card');
}
function taskForm(task = {}) {
  return el('form', {}, field('Название', input('title', task.title, { required: true, maxlength: 200, placeholder: 'Что нужно сделать?' })),
    field('Описание', area('description', task.description)), el('div', { class: 'form-grid' }, field('Статус', select('status', Object.entries(statuses), task.status || 'TODO')),
      field('Приоритет', select('priority', Object.entries(priorities), task.priority || 'MEDIUM'))), field('Срок выполнения', input('dueDate', task.dueDate, { type: 'date' }), 'Просрочка считается по дате UTC; завершённые задачи не считаются просроченными.'), actions(task.id ? 'Сохранить изменения' : 'Создать задачу'));
}
function taskData(values) { return { ...values, dueDate: values.dueDate || null }; }
function createTask() {
  const path = projectPath(), form = taskForm(); let key = crypto.randomUUID();
  form.addEventListener('input', () => { key = crypto.randomUUID(); });
  formSubmit(form, async values => { const task = await api(`${path}/tasks`, { method: 'POST', data: taskData(values), headers: { 'Idempotency-Key': key } }); $('#modal').close(); toast('Задача создана'); await loadBoard(); await openTask(task.id); });
  modal('Новая задача', form);
}
async function openTask(id) {
  const epoch = ++state.detailEpoch, path = `${projectPath()}/tasks/${id}`;
  modal('Карточка задачи', el('p', { role: 'status' }, 'Загружаем…'), $('#task-dialog'));
  const [task, comments, history] = await Promise.all([api(path), all(`${path}/comments`), all(`${path}/history`)]);
  if (epoch !== state.detailEpoch || !$('#task-dialog').open) return;
  const content = el('div', {}, el('p', { class: 'eyebrow' }, `ЗАДАЧА #${task.id.slice(0, 6)}`));
  const form = taskForm(task);
  if (state.project.archived) form.querySelectorAll('input,textarea,select,button').forEach(n => { n.disabled = true; });
  formSubmit(form, async values => { const updated = await api(path, { method: 'PUT', data: taskData(values), headers: { 'If-Match': tag(task) } }); toast('Изменения сохранены'); await loadBoard(); await openTask(updated.id); });
  content.append(form, button('Перечитать карточку', () => confirmAction('Перечитать карточку?', 'Несохранённый текст формы будет заменён актуальными данными.', () => openTask(id)), 'quiet small'));
  const assignment = el('form', { class: 'section-title' }, field('Исполнитель', select('userId', [['', 'Не назначен'], ...state.people.map(p => [p.id, p.displayName])], task.assigneeId)), actions('Назначить'));
  if (state.project.archived) assignment.querySelectorAll('select,button').forEach(n => { n.disabled = true; });
  formSubmit(assignment, async values => { await api(`${path}/assignee`, { method: 'PUT', data: { userId: values.userId || null }, headers: { 'If-Match': tag(task) } }); toast('Исполнитель обновлён'); await loadBoard(); await openTask(id); });
  content.append(assignment, el('h3', { class: 'section-title' }, `Обсуждение · ${comments.length}`));
  comments.forEach(comment => content.append(el('article', { class: 'comment' }, el('div', { class: 'row' }, avatar(comment.authorName), el('strong', { class: 'grow' }, comment.authorName), el('span', { class: 'muted' }, timestamp(comment.createdAt))), el('p', {}, comment.body))));
  if (!comments.length) content.append(el('p', { class: 'muted' }, 'Пока без комментариев. Начни обсуждение.'));
  if (!state.project.archived) {
    const commentForm = el('form', {}, field('Комментарий', area('body')), actions('Отправить'));
    commentForm.querySelector('textarea').required = true;
    let key = crypto.randomUUID(); commentForm.addEventListener('input', () => { key = crypto.randomUUID(); });
    formSubmit(commentForm, async values => { await api(`${path}/comments`, { method: 'POST', data: values, headers: { 'Idempotency-Key': key } }); await openTask(id); }); content.append(commentForm);
  }
  content.append(el('h3', { class: 'section-title' }, 'История статусов'), ...history.map(event => el('div', { class: 'history-item' },
    `${event.actorName || 'Система'} · ${event.fromStatus ? statuses[event.fromStatus] + ' → ' : ''}${statuses[event.toStatus]} · ${timestamp(event.changedAt)}`)));
  if (!state.project.archived) content.append(button('Удалить задачу', () => confirmAction('Удалить задачу?', 'Задача, комментарии и история будут удалены.', async () => { await api(path, { method: 'DELETE', headers: { 'If-Match': tag(task) } }); $('#task-dialog').close(); await loadBoard(); }), 'danger quiet'));
  modal(task.title, content, $('#task-dialog'));
}

function namedForm(description = true) {
  return el('form', {}, field('Название', input('name', '', { required: true, maxlength: 120 })), description ? field('Описание', area('description', '', 2000)) : null, actions('Создать'));
}
function createWorkspace() {
  const form = namedForm(); formSubmit(form, async values => { state.workspace = await api('/workspaces', { method: 'POST', data: values }); state.project = null; $('#modal').close(); await boot(); }); modal('Новое пространство', form);
}
function createProject() {
  const form = namedForm(), path = wsPath();
  formSubmit(form, async values => { state.project = await api(`${path}/projects`, { method: 'POST', data: values }); state.archived = false; $('#modal').close(); await loadWorkspace(); }); modal('Новый проект', form);
}
function saveFilter() {
  const form = el('form', {}, field('Название фильтра', input('name', '', { required: true, maxlength: 80, placeholder: 'Например, срочное для меня' })), el('p', { class: 'form-note' }, 'Сохраняются применённые условия поиска. Фильтр виден только тебе.'), actions('Сохранить'));
  const q = { ...state.query }, path = projectPath();
  formSubmit(form, async values => { await api(`${path}/filters`, { method: 'POST', data: { name: values.name, q: q.q || null, status: q.status || null, priority: q.priority || null, assigneeId: q.assigneeId || null, overdue: q.overdue === 'true' } });
    state.filters = await api(`${path}/filters`); $('#modal').close(); renderProject(); await loadBoard(); }); modal('Сохранить фильтр', form);
}
function archiveProject() {
  const p = state.project, path = projectPath();
  confirmAction(p.archived ? 'Восстановить проект?' : 'Архивировать проект?', p.archived ? 'Команда снова сможет изменять задачи.' : 'Проект останется доступен для чтения. Его можно восстановить.', async () => {
    await api(`${path}/archive`, { method: 'PUT', data: { archived: !p.archived }, headers: { 'If-Match': tag(p) } }); state.project = null; await loadWorkspace();
  });
}
function acceptInvite() {
  const form = el('form', {}, field('Код приглашения', input('token', '', { required: true, maxlength: 128 })), el('p', { class: 'form-note' }, 'Войди с тем email, на который администратор создал приглашение.'), actions('Присоединиться'));
  formSubmit(form, async values => { await api('/invitations/accept', { method: 'POST', data: values }); $('#modal').close(); await boot(); toast('Ты в команде'); }); modal('Вступить в команду', form);
}
async function showTeam() {
  [state.members, state.people] = await Promise.all([all(`${wsPath()}/members`), all(`${projectPath()}/members`)]);
  const content = el('div', {}, el('p', { class: 'muted' }, 'Участники текущего проекта'));
  state.people.forEach(p => content.append(el('div', { class: 'row member-row' }, avatar(p.displayName), el('div', { class: 'grow' }, el('strong', {}, p.displayName), el('div', { class: 'muted' }, p.email)),
    admin() && !state.project.archived ? button('Убрать', async () => { await api(`${projectPath()}/members/${p.id}`, { method: 'DELETE' }); state.people = await all(`${projectPath()}/members`); await showTeam(); await loadBoard(); }, 'quiet small') : null)));
  if (admin()) {
    const available = state.members.filter(m => !state.people.some(p => p.id === m.userId));
    if (available.length && !state.project.archived) {
      const add = el('form', { class: 'section-title' }, field('Добавить из команды', select('userId', available.map(m => [m.userId, m.displayName]))), actions('Добавить в проект'));
      formSubmit(add, async values => { await api(`${projectPath()}/members`, { method: 'POST', data: values }); state.people = await all(`${projectPath()}/members`); await showTeam(); }); content.append(add);
    }
    const invitation = el('form', { class: 'section-title' }, el('h3', {}, 'Пригласить в рабочее пространство'), field('Email', input('email', '', { type: 'email', required: true, maxlength: 254 })),
      field('Роль в команде', select('role', [['MEMBER', 'Участник'], ['ADMIN', 'Администратор']])), actions('Создать приглашение'));
    formSubmit(invitation, async values => { const created = await api(`${wsPath()}/invitations`, { method: 'POST', data: values });
      modal('Приглашение готово', el('div', {}, el('p', { class: 'description' }, 'Передай код получателю. Он действует 7 дней. После подтверждения добавь коллегу в нужный проект.'), el('p', { class: 'code' }, created.token),
        button('Скопировать код', async () => { await navigator.clipboard.writeText(created.token); toast('Код скопирован'); }),
        button('Отозвать приглашение', async () => { await api(`${wsPath()}/invitations/${created.invitation.id}`, { method: 'DELETE' }); $('#modal').close(); toast('Приглашение отозвано'); }, 'quiet danger')));
    }); content.append(invitation);
  }
  modal('Люди проекта', content);
}
async function updateUnread() {
  if (!state.auth) return;
  const count = await api('/notifications/unread-count');
  if ($('#unread')) $('#unread').textContent = count.count;
}
async function showNotifications(page = 0) {
  const result = await api(`/notifications?page=${page}&size=20`);
  const content = el('div', {}, el('p', { class: 'notifications-summary' }, 'Назначения и новые комментарии в твоих проектах.'),
    button('Прочитать все', async () => { await api('/notifications/read-all', { method: 'PUT' }); await updateUnread(); await showNotifications(page); }, 'quiet small'));
  if (!result.content.length) content.append(el('div', { class: 'empty' }, el('h3', {}, 'Всё спокойно'), el('p', {}, 'Новые события появятся здесь.')));
  result.content.forEach(n => content.append(button(el('div', {}, el('span', { class: 'eyebrow' }, n.kind === 'ASSIGNED' ? 'ТЕБЕ НАЗНАЧЕНА ЗАДАЧА' : 'НОВЫЙ КОММЕНТАРИЙ'), el('strong', {}, n.taskTitle), el('p', {}, `${n.actorName} · ${timestamp(n.createdAt)}`)), async () => {
    await api(`/notifications/${n.id}/read`, { method: 'PUT' }); $('#modal').close();
    state.workspaces = await all('/workspaces'); state.workspace = state.workspaces.find(w => w.id === n.workspaceId);
    if (!state.workspace) throw Object.assign(new Error(), { status: 404 });
    const p = await api(`/workspaces/${n.workspaceId}/projects/${n.projectId}`); state.archived = p.archived; state.project = p;
    await loadWorkspace(); await openTask(n.taskId); await updateUnread();
  }, `notification ${n.readAt ? '' : 'unread'}`)));
  content.append(el('div', { class: 'pager' }, el('button', { disabled: page === 0, onclick: () => run(() => showNotifications(page - 1)) }, '←'), el('span', {}, `${page + 1} / ${Math.max(1, result.totalPages)}`),
    el('button', { disabled: page + 1 >= result.totalPages, onclick: () => run(() => showNotifications(page + 1)) }, '→')));
  modal('Уведомления', content);
}
$('#task-dialog').addEventListener('close', () => { state.detailEpoch++; });
renderAuth();
