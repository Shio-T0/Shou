// Shou phone remote — sends control commands to the server and mirrors the
// kiosk state live. Everything is gated by a shared token carried in the URL.

const TOKEN =
  window.SHOU_TOKEN ||
  new URLSearchParams(location.search).get("k") ||
  "";

const el = {
  conn: document.getElementById("conn"),
  connText: document.getElementById("conn-text"),
  hero: document.getElementById("hero"),
  bg: document.getElementById("mirror-bg"),
  cover: document.getElementById("mirror-cover"),
  view: document.getElementById("mirror-view"),
  index: document.getElementById("mirror-index"),
  title: document.getElementById("mirror-title"),
  episode: document.getElementById("mirror-episode"),
  progress: document.getElementById("mirror-progress"),
  toast: document.getElementById("toast"),
  seg: document.getElementById("listseg"),
  segBtns: Array.from(document.querySelectorAll("#listseg .seg-btn")),
  resume: document.getElementById("resume"),
  resumeRail: document.getElementById("resume-rail"),
  deck: document.querySelector(".deck"),
  searchPanel: document.getElementById("search-panel"),
  spQuery: document.getElementById("sp-query"),
  spPh: document.getElementById("sp-ph"),
  spClear: document.getElementById("sp-clear"),
  spResults: document.getElementById("sp-results"),
  spKeyboard: document.getElementById("sp-keyboard"),
  spFilters: document.getElementById("sp-filters"),
  spGenres: document.getElementById("sp-genres"),
  spFiltersClear: document.getElementById("sp-filters-clear"),
  statusPanel: document.getElementById("status-panel"),
  stCover: document.getElementById("st-cover"),
  stTitle: document.getElementById("st-title"),
  stNow: document.getElementById("st-now"),
  stButtons: document.getElementById("st-buttons"),
  stSeasons: document.getElementById("st-seasons"),
  stSeasonPos: document.getElementById("st-season-pos"),
  stSeasonSub: document.getElementById("st-season-sub"),
};

const STATUS_LABEL = {
  CURRENT: "Watching", PLANNING: "Planned", COMPLETED: "Completed",
  PAUSED: "Paused", DROPPED: "Dropped", REPEATING: "Rewatching",
};
const STATUS_CLASS = {
  CURRENT: "st-current", PLANNING: "st-planning", COMPLETED: "st-completed",
  PAUSED: "st-paused", DROPPED: "st-dropped", REPEATING: "st-current",
};

// Action popups are disabled — the live mirror + haptics are feedback enough.
function toast(_msg) {}

// --- Commands ---------------------------------------------------------------
const LABELS = {
  open: "Opening…",
  left: "◀",
  right: "▶",
  select: "Select",
  back: "Back to UI",
  pause: "Play / Pause",
  prev: "Previous episode",
  next: "Next episode",
  rew: "« 30s",
  fwd: "30s »",
};

async function send(act) {
  if (navigator.vibrate) navigator.vibrate(12);
  toast(LABELS[act] || act);
  try {
    await fetch(`/${act}?k=${encodeURIComponent(TOKEN)}`, { method: "POST" });
  } catch (e) {
    toast("⚠ no connection");
  }
}

document.querySelectorAll("[data-act]").forEach((btn) => {
  btn.addEventListener("click", () => send(btn.dataset.act));
});

// List switcher (Watching / Planned) -> POST /list?to=<mode>
const LIST_LABEL = { watching: "Watching", planned: "Planned", search: "Search New" };
async function switchList(mode) {
  if (navigator.vibrate) navigator.vibrate(12);
  toast(LIST_LABEL[mode] || mode);
  try {
    await fetch(`/list?to=${mode}&k=${encodeURIComponent(TOKEN)}`, { method: "POST" });
  } catch (e) {
    toast("⚠ no connection");
  }
}
el.segBtns.forEach((btn) => {
  btn.addEventListener("click", () => switchList(btn.dataset.list));
});

el.spClear.addEventListener("click", typeClear);
document.querySelector("[data-filter-back]").addEventListener("click", () => setFilterMode(false));
el.spFiltersClear.addEventListener("click", clearGenres);

// Infinite scroll: ask the server for more results as the list nears its bottom.
// The endpoint is a no-op when there's nothing more or a load is already in flight.
let moreCooldown = 0;
function requestMore() {
  const now = Date.now();
  if (now - moreCooldown < 600) return;
  moreCooldown = now;
  post("/search/more");
}
el.spResults.addEventListener("scroll", () => {
  const e = el.spResults;
  if (e.scrollHeight - e.scrollTop - e.clientHeight < 260) requestMore();
}, { passive: true });

// --- Continue watching ------------------------------------------------------
function escapeHtml(s) {
  const d = document.createElement("div");
  d.textContent = s == null ? "" : String(s);
  return d.innerHTML;
}
function fmtTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const mm = String(m).padStart(h ? 2 : 1, "0");
  const ss = String(s).padStart(2, "0");
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

async function resume(mediaId, ep, title) {
  if (navigator.vibrate) navigator.vibrate(12);
  toast("Resuming " + (title || ""));
  try {
    await fetch(
      `/resume?media_id=${encodeURIComponent(mediaId)}&episode=${encodeURIComponent(ep)}&k=${encodeURIComponent(TOKEN)}`,
      { method: "POST" }
    );
  } catch (e) {
    toast("⚠ no connection");
  }
}

async function forgetResume(mediaId, ep, title) {
  if (navigator.vibrate) navigator.vibrate(8);
  toast("Removed " + (title || ""));
  try {
    await fetch(
      `/forget?media_id=${encodeURIComponent(mediaId)}&episode=${encodeURIComponent(ep)}&k=${encodeURIComponent(TOKEN)}`,
      { method: "POST" }
    );
  } catch (e) {
    toast("⚠ no connection");
  }
}

let resumeSig = "";
function renderResume(history) {
  history = history || [];
  const sig = history
    .map((e) => `${e.media_id}:${e.episode}:${Math.round(e.position || 0)}`)
    .join("|");
  if (sig === resumeSig) return;
  resumeSig = sig;

  if (!history.length) {
    el.resume.classList.add("hidden");
    el.resumeRail.innerHTML = "";
    return;
  }
  el.resumeRail.innerHTML = "";
  history.forEach((e) => {
    const pct = e.duration
      ? Math.min(100, Math.round((e.position / e.duration) * 100))
      : Math.round(e.percent || 0);
    const coverStyle = e.cover
      ? `background-image:url(${e.cover})`
      : `background-color:${e.color || "#1f2233"}`;
    const card = document.createElement("div");
    card.className = "rcard";
    card.innerHTML = `
      <span class="rcard-cover" style="${coverStyle}">
        <span class="rcard-play"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg></span>
      </span>
      <span class="rcard-meta">
        <span class="rcard-title">${escapeHtml(e.title)}</span>
        <span class="rcard-ep">EP ${escapeHtml(e.episode)} · ${fmtTime(e.position)}</span>
      </span>
      <span class="rcard-bar"><span style="width:${pct}%"></span></span>
      <button type="button" class="rcard-del" aria-label="Remove from Continue Watching">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>
      </button>`;
    card.addEventListener("click", () => resume(e.media_id, e.episode, e.title));
    card.querySelector(".rcard-del").addEventListener("click", (ev) => {
      ev.stopPropagation();
      forgetResume(e.media_id, e.episode, e.title);
    });
    el.resumeRail.appendChild(card);
  });
  el.resume.classList.remove("hidden");
}

// --- Search new -------------------------------------------------------------
async function post(path) {
  const sep = path.includes("?") ? "&" : "?";
  try {
    await fetch(path + sep + "k=" + encodeURIComponent(TOKEN), { method: "POST" });
  } catch (e) {
    toast("⚠ no connection");
  }
}
function typeKey(c) { if (navigator.vibrate) navigator.vibrate(5); post("/search/key?c=" + encodeURIComponent(c)); }
function typeBack() { if (navigator.vibrate) navigator.vibrate(6); post("/search/back"); }
function typeClear() { if (navigator.vibrate) navigator.vibrate(10); post("/search/clear"); }
function toggleGenre(g) { if (navigator.vibrate) navigator.vibrate(8); post("/search/genre?g=" + encodeURIComponent(g)); }
function clearGenres() { if (navigator.vibrate) navigator.vibrate(10); post("/search/genres/clear"); }

// Local-only toggle between the keyboard and the genre filters (same panel slot).
let filterMode = false;
function setFilterMode(on) {
  filterMode = on;
  if (navigator.vibrate) navigator.vibrate(8);
  el.spKeyboard.classList.toggle("hidden", on);
  el.spFilters.classList.toggle("hidden", !on);
}
function pickResult(i) { if (navigator.vibrate) navigator.vibrate(12); post("/search/pick?i=" + i); }
function setStatus(to) {
  if (navigator.vibrate) navigator.vibrate(14);
  toast(to === "REMOVE" ? "Removing…" : "→ " + (STATUS_LABEL[to] || to));
  post("/status?to=" + encodeURIComponent(to));
}

const KB_ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
function buildKeyboard() {
  if (el.spKeyboard.dataset.built) return;
  el.spKeyboard.dataset.built = "1";
  KB_ROWS.forEach((row, ri) => {
    const r = document.createElement("div");
    r.className = "kb-row";
    [...row].forEach((ch) => {
      const k = document.createElement("button");
      k.type = "button";
      k.className = "kb-key";
      k.textContent = ch;
      k.addEventListener("click", () => typeKey(ch));
      r.appendChild(k);
    });
    if (ri === 2) {
      const back = document.createElement("button");
      back.type = "button";
      back.className = "kb-key kb-back";
      back.setAttribute("aria-label", "Backspace");
      back.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 5H8l-6 7 6 7h13a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1z"/><path d="M16 9l-5 6M11 9l5 6"/></svg>`;
      back.addEventListener("click", typeBack);
      r.appendChild(back);
    }
    el.spKeyboard.appendChild(r);
  });
  const r = document.createElement("div");
  r.className = "kb-row";
  const filter = document.createElement("button");
  filter.type = "button";
  filter.className = "kb-key kb-filter";
  filter.id = "kb-filter";
  filter.setAttribute("aria-label", "Filter by genre");
  filter.innerHTML =
    `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 5h18l-7 8v6l-4-2v-4z"/></svg>` +
    `<span class="kb-filter-count"></span>`;
  filter.addEventListener("click", () => setFilterMode(true));
  r.appendChild(filter);
  const space = document.createElement("button");
  space.type = "button";
  space.className = "kb-key kb-space";
  space.textContent = "space";
  space.addEventListener("click", () => typeKey(" "));
  r.appendChild(space);
  el.spKeyboard.appendChild(r);
}

function buildFilters(genres, tags) {
  if (el.spGenres.dataset.built || !genres || !genres.length) return;
  el.spGenres.dataset.built = "1";
  const section = (label, items) => {
    const head = document.createElement("div");
    head.className = "gsec-head";
    head.textContent = label;
    el.spGenres.appendChild(head);
    const sec = document.createElement("div");
    sec.className = "gsec";
    items.forEach((g) => {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "gchip";
      chip.dataset.genre = g;
      chip.textContent = g;
      chip.addEventListener("click", () => toggleGenre(g));
      sec.appendChild(chip);
    });
    el.spGenres.appendChild(sec);
  };
  section("Genres", genres);
  if (tags && tags.length) section("Tags", tags);
}

let spRows = [];        // [{id, status, el}] currently in the DOM, in order
let spCursor = -1;
let prevSearching = false;
function spcardPill(r) {
  const st = r.listStatus;
  return st
    ? `<span class="spcard-status ${STATUS_CLASS[st] || ""}">${STATUS_LABEL[st] || st}</span>`
    : `<span class="spcard-status st-none">＋</span>`;
}
function spcardInner(r) {
  const coverStyle = r.cover
    ? `background-image:url(${r.cover})`
    : `background-color:${r.color || "#1f2233"}`;
  const bits = [r.format, r.year, r.episodes ? r.episodes + " eps" : ""]
    .filter(Boolean).join(" · ");
  return `
    <span class="spcard-cover" style="${coverStyle}"></span>
    <span class="spcard-main">
      <span class="spcard-title">${escapeHtml(r.title)}</span>
      <span class="spcard-sub">${escapeHtml(bits)}</span>
    </span>
    ${spcardPill(r)}`;
}
function renderSearchPanel(search) {
  const q = (search && search.query) || "";
  el.spQuery.textContent = q;
  el.spPh.classList.toggle("hidden", q.length > 0);
  el.spClear.classList.toggle("hidden", q.length === 0);

  // Genre filters: build the grid once, then reflect the server's active set.
  const genres = (search && search.genres) || [];
  buildFilters(search && search.genreList, search && search.tagList);
  for (const chip of el.spGenres.querySelectorAll(".gchip")) {
    chip.classList.toggle("active", genres.includes(chip.dataset.genre));
  }
  const fbtn = document.getElementById("kb-filter");
  if (fbtn) {
    fbtn.querySelector(".kb-filter-count").textContent = genres.length || "";
    fbtn.classList.toggle("has-filters", genres.length > 0);
  }
  el.spFiltersClear.classList.toggle("hidden", genres.length === 0);

  // Incremental render: append newly-loaded results, keep existing rows (so finger-scroll
  // position survives). Full rebuild only when the list's prefix changed (new query/filter).
  const results = (search && search.results) || [];
  let prefixOk = results.length >= spRows.length;
  for (let i = 0; prefixOk && i < spRows.length; i++) {
    if (!results[i] || results[i].id !== spRows[i].id) prefixOk = false;
  }
  if (!prefixOk) {
    el.spResults.innerHTML = "";
    spRows = [];
    spCursor = -1;
    el.spResults.scrollTop = 0;
  }
  for (let i = 0; i < spRows.length; i++) {  // refresh pills that changed in place
    if (spRows[i].status !== (results[i].listStatus || null)) {
      spRows[i].el.querySelector(".spcard-status").outerHTML = spcardPill(results[i]);
      spRows[i].status = results[i].listStatus || null;
    }
  }
  for (let i = spRows.length; i < results.length; i++) {
    const r = results[i];
    const row = document.createElement("button");
    row.type = "button";
    row.className = "spcard";
    row.innerHTML = spcardInner(r);
    row.addEventListener("click", () => pickResult(i));
    el.spResults.appendChild(row);
    spRows.push({ id: r.id, status: r.listStatus || null, el: row });
  }

  const cursor = (search && search.cursor) || 0;
  if (cursor !== spCursor) {
    const rows = el.spResults.children;
    if (spCursor >= 0 && rows[spCursor]) rows[spCursor].classList.remove("active");
    if (rows[cursor]) {
      rows[cursor].classList.add("active");
      rows[cursor].scrollIntoView({ block: "nearest" });
    }
    spCursor = cursor;
  }
}

let stStatusesSig = "";
let stDetailId = "";
function renderStatusPanel(search) {
  const d = search && search.detail;
  if (!d) return;
  if (String(d.id) !== stDetailId) {
    stDetailId = String(d.id);
    el.stCover.src = d.cover || "";
    el.stCover.style.opacity = d.cover ? "1" : "0";
    el.stTitle.textContent = d.title || "";
  }
  const st = d.listStatus;
  el.stNow.textContent = st ? "Currently: " + (STATUS_LABEL[st] || st) : "Not in your lists";
  el.stNow.className = "st-now " + (st ? STATUS_CLASS[st] || "" : "st-none");

  // Season switcher (▲ / ▼) — only when the franchise has more than one entry.
  const seasons = (search && search.seasons) || [];
  const sIdx = (search && search.seasonIdx) || 0;
  el.stSeasons.classList.toggle("hidden", seasons.length < 2);
  if (seasons.length >= 2) {
    const cur = seasons[sIdx] || {};
    el.stSeasonPos.textContent = "Season " + (sIdx + 1) + " of " + seasons.length;
    el.stSeasonSub.textContent =
      [cur.format, cur.year].filter(Boolean).join(" · ") || "Switch season";
  }

  const statuses = (search && search.statuses) || [];
  const canWrite = !search || search.canWrite;
  const sig = statuses.map((s) => s[0]).join(",") + "|" + (st || "") + "|" + canWrite;
  if (sig === stStatusesSig) return;
  stStatusesSig = sig;
  el.stButtons.innerHTML = "";
  statuses.forEach(([value, label]) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "st-btn " + (STATUS_CLASS[value] || "") + (value === st ? " current" : "");
    b.textContent = label;
    b.disabled = !canWrite;
    b.addEventListener("click", () => setStatus(value));
    el.stButtons.appendChild(b);
  });
  const rm = document.createElement("button");
  rm.type = "button";
  rm.className = "st-btn st-remove";
  rm.textContent = "Remove from lists";
  rm.disabled = !canWrite || !st;
  rm.addEventListener("click", () => setStatus("REMOVE"));
  el.stButtons.appendChild(rm);
  if (!canWrite) {
    const note = document.createElement("div");
    note.className = "st-note";
    note.textContent = "Set ANILIST_TOKEN in shou.conf to change status.";
    el.stButtons.appendChild(note);
  }
}

// --- Live mirror via SocketIO ----------------------------------------------
const VIEW_LABEL = {
  grid: "Browsing",
  sequel: "Sequel found",
  playing: "Now playing",
  rating: "Rate it",
  search: "Search",
  detail: "Details",
  loading: "Loading",
  empty: "Nothing to watch",
  error: "Error",
};

// Compact star string (★ / ⯪ / ☆) for the phone's rating mirror.
function starString(stars) {
  let out = "";
  for (let i = 0; i < 5; i++) {
    const frac = stars - i;
    out += frac >= 0.75 ? "★" : frac >= 0.25 ? "⯪" : "☆";
  }
  return out;
}
function fmtRatingScore(r) {
  if (r.format === "POINT_10_DECIMAL") return (Math.round(r.score * 10) / 10).toFixed(1);
  return String(r.score);
}

const socket = io({ query: { k: TOKEN } });

socket.on("connect", () => {
  el.conn.classList.add("live");
  el.conn.classList.remove("down");
  el.connText.textContent = "live";
});
socket.on("disconnect", () => {
  el.conn.classList.remove("live");
  el.conn.classList.add("down");
  el.connText.textContent = "offline";
});

socket.on("state", (s) => {
  el.view.textContent = VIEW_LABEL[s.view] || s.view;

  renderResume(s.history);

  if (s.list) {
    el.segBtns.forEach((b) => b.classList.toggle("active", b.dataset.list === s.list));
    el.seg.classList.remove("is-watching", "is-planned", "is-search");
    el.seg.classList.add("is-" + s.list);
  }

  // Search/detail take over the whole remote: hide the mirror hero + control deck,
  // show the search keyboard or the status config instead.
  const searching = s.view === "search";
  const detailing = s.view === "detail";
  el.searchPanel.classList.toggle("hidden", !searching);
  el.statusPanel.classList.toggle("hidden", !detailing);
  el.hero.classList.toggle("hidden", searching || detailing);
  el.deck.classList.toggle("hidden", searching || detailing);
  // Authoritative each tick: hidden in search/detail, else shown when there's history.
  // (renderResume short-circuits on unchanged history, so it can't re-show this itself.)
  const hasResume = !!(s.history && s.history.length);
  el.resume.classList.toggle("hidden", searching || detailing || !hasResume);
  // With Continue Watching present, collapse the hero to its image height so the page
  // doesn't need to scroll.
  document.body.classList.toggle("has-resume", hasResume);
  if (searching) {
    buildKeyboard();
    if (!prevSearching) filterMode = false;  // fresh entry always starts on the keyboard
    el.spKeyboard.classList.toggle("hidden", filterMode);
    el.spFilters.classList.toggle("hidden", !filterMode);
    renderSearchPanel(s.search);
  }
  prevSearching = searching;
  if (detailing) renderStatusPanel(s.search);
  if (searching || detailing) return;  // nothing below applies to these views

  setIndex("");
  setProgress(0);
  el.hero.classList.remove("is-caughtup");

  if (s.view === "grid" && s.items && s.items.length) {
    const it = s.items[s.cursor] || s.items[0];
    el.title.textContent = it.title;
    el.episode.textContent = it.episodeText + (it.caughtUp ? "  ·  caught up" : "");
    setCover(it.cover, it.banner, it.color);
    setIndex(s.cursor + 1 + " / " + s.items.length);
    const denom = it.available || it.total;
    setProgress(denom ? Math.min(100, Math.round((it.progress / denom) * 100)) : 0);
    el.hero.classList.toggle("is-caughtup", !!it.caughtUp);
  } else if (s.view === "sequel" && s.sequel) {
    el.title.textContent = s.sequel.sequel_title;
    el.episode.textContent = "Finished " + s.sequel.finished + " — Select to watch";
  } else if (s.view === "playing") {
    el.title.textContent = s.playing ? s.playing.title : "Playing";
    el.episode.textContent = s.playing ? "Episode " + s.playing.episode : s.message || "";
    setProgress(100);
  } else if (s.view === "rating" && s.rating) {
    const r = s.rating;
    el.title.textContent = r.title;
    el.episode.textContent = r.done
      ? "Rated ✓ — " + fmtRatingScore(r) + " / " + r.max
      : starString(r.stars) + "   " + fmtRatingScore(r) + " / " + r.max;
    setCover(r.cover, r.banner, r.color);
    setIndex("◀ ▶ adjust · ● confirm");
    setProgress(Math.round(((r.stars || 0) / 5) * 100));
  } else {
    el.title.textContent = s.message || VIEW_LABEL[s.view] || "Shou";
    el.episode.textContent = "";
    setCover("", "", "#100f13");
  }
});

function setIndex(text) {
  if (el.index) el.index.textContent = text;
}
function setProgress(pct) {
  if (el.progress) el.progress.style.width = pct + "%";
}

let lastCover = null;
function setCover(cover, banner, color) {
  if (cover !== lastCover) {
    el.cover.src = cover || "";
    lastCover = cover;
  }
  el.bg.style.backgroundColor = color || "#17161c";
  el.bg.style.backgroundImage = banner
    ? `url(${banner})`
    : cover
    ? `url(${cover})`
    : "none";
}

// --- PWA service worker (registers only in a secure context; no-ops on http) -
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}
