// Shou phone remote — sends control commands to the server and mirrors the
// kiosk state live. Everything is gated by a shared token carried in the URL.

const TOKEN =
  window.SHOU_TOKEN ||
  new URLSearchParams(location.search).get("k") ||
  "";

// When the remote runs inside the native Android shell it exposes a bridge with powers
// a web page can't have: Wake-on-LAN, lock-screen media controls, mDNS discovery, and
// system notifications. Everything here degrades gracefully to a plain browser (NATIVE = null).
const NATIVE =
  (typeof window !== "undefined" && window.ShouNative) ? window.ShouNative : null;

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
  epProgress: document.getElementById("ep-progress"),
  epBarFill: document.getElementById("ep-bar-fill"),
  epCur: document.getElementById("ep-cur"),
  epDur: document.getElementById("ep-dur"),
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

// Keep the phone screen awake while the remote is open.
//   1. Screen Wake Lock API — clean, but only works in a secure context (HTTPS/localhost).
//   2. Fallback for plain-http LAN access: a muted looping inline video. Playing media
//      keeps mobile screens awake even on insecure origins.
// The OS releases both when the tab is hidden, so re-arm on every return to visibility,
// and on the first tap in case the browser wants a user gesture to start playback.
let wakeLock = null;
let nosleepVideo = null;
function playNoSleep() {
  if (!nosleepVideo) {
    const v = document.createElement("video");
    v.muted = true; v.loop = true; v.autoplay = true;
    v.setAttribute("playsinline", ""); v.setAttribute("aria-hidden", "true");
    // Must be genuinely rendered in the viewport (not opacity:0 / off-screen) or
    // Chromium won't keep the screen awake for it — so a 2px near-invisible corner sliver.
    v.style.cssText = "position:fixed;left:0;bottom:0;width:2px;height:2px;opacity:0.01;border:0;pointer-events:none;z-index:-1;";
    for (const [src, type] of [["/static/nosleep.webm", "video/webm"], ["/static/nosleep.mp4", "video/mp4"]]) {
      const s = document.createElement("source"); s.src = src; s.type = type; v.appendChild(s);
    }
    // If the browser pauses it, restart while we're still the visible tab.
    v.addEventListener("pause", () => {
      if (document.visibilityState === "visible") v.play().catch(() => {});
    });
    document.body.appendChild(v);
    nosleepVideo = v;
  }
  nosleepVideo.play().catch(() => {});
}
async function keepAwake() {
  if (document.visibilityState !== "visible") return;
  if ("wakeLock" in navigator) {
    try { wakeLock = await navigator.wakeLock.request("screen"); return; }
    catch (e) { /* fall through to the video fallback */ }
  }
  playNoSleep();  // insecure origin or unsupported API
}
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") keepAwake();
});
document.addEventListener("click", keepAwake, { once: true });  // gesture, in case it's needed
keepAwake();

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

async function send(act, secs) {
  if (navigator.vibrate) navigator.vibrate(12);
  toast(LABELS[act] || act);
  try {
    let url = `/${act}?k=${encodeURIComponent(TOKEN)}`;
    if (secs) url += `&s=${encodeURIComponent(secs)}`;  // seek step (rew/fwd)
    await fetch(url, { method: "POST" });
  } catch (e) {
    toast("⚠ no connection");
  }
}

document.querySelectorAll("[data-act]").forEach((btn) => {
  btn.addEventListener("click", () => send(btn.dataset.act, btn.dataset.secs));
});

// Volume — the on-screen buttons and the native app's hardware volume rocker both
// land here and nudge the PC's mpv (?d=up|down|mute).
function volume(dir) {
  if (navigator.vibrate) navigator.vibrate(dir === "mute" ? 14 : 6);
  post("/volume?d=" + encodeURIComponent(dir));
}
document.querySelectorAll("[data-vol]").forEach((b) => {
  b.addEventListener("click", () => volume(b.dataset.vol));
});
// Called by the native shell when you press VOLUME_UP / VOLUME_DOWN on the phone.
window.ShouVolume = volume;

// --- Throw to phone ---------------------------------------------------------
// Hand the episode playing on the PC to this phone and watch it here. The button
// only shows while something is playing and we're not already watching it here.
function throwToPhone() {
  if (navigator.vibrate) navigator.vibrate(18);
  post("/throw");
}
function updateThrowBtn(s) {
  const b = document.getElementById("throw-btn");
  if (!b) return;
  const playing = !!(s.playing && s.playing.duration);
  const casting = !!(s.cast && s.cast.active);
  b.classList.toggle("hidden", !playing || casting);
}

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

  syncNativePlayback(s);
  applyCast(s.cast);
  updateThrowBtn(s);
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

  renderEpProgress(s.playing);

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

// Mirror live playback down to the native shell so it can drive the lock-screen media
// controls / widget / Quick Settings tile, and fire a notification when a series finishes.
let lastFinishedKey = "";
function syncNativePlayback(s) {
  if (!NATIVE) return;
  const p = s.playing;
  const active = !!(p && p.duration);
  if (NATIVE.playback) {
    try {
      NATIVE.playback(JSON.stringify({
        active,
        playing: active ? !p.paused : false,  // real mpv play/pause, from the server
        title: active ? (p.title || "Shou") : "",
        subtitle: active ? ("Episode " + p.episode) : "",
        cover: active ? (p.cover || "") : "",
        position: active ? (p.position || 0) : 0,
        duration: active ? (p.duration || 0) : 0,
      }));
    } catch (e) {}
  }
  // The finale rating screen is the clean "you finished it" moment — notify once.
  if (s.view === "rating" && s.rating && NATIVE.notify) {
    const key = String(s.rating.title || "");
    if (key && key !== lastFinishedKey) {
      lastFinishedKey = key;
      try { NATIVE.notify("finished", s.rating.title, "You finished it — rate it on Shou?"); } catch (e) {}
    }
  } else if (s.view !== "rating") {
    lastFinishedKey = "";
  }
}

function setIndex(text) {
  if (el.index) el.index.textContent = text;
}
function setProgress(pct) {
  if (el.progress) el.progress.style.width = pct + "%";
}

function fmtTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const mm = h ? String(m).padStart(2, "0") : String(m);
  return (h ? h + ":" : "") + mm + ":" + String(s).padStart(2, "0");
}

// Live playback bar for the current episode (position/duration come from the
// server's mpv watcher, ~1/second). Hidden whenever nothing is playing.
function renderEpProgress(playing) {
  const dur = playing && playing.duration;
  const has = !!(dur && dur > 0);
  el.epProgress.classList.toggle("hidden", !has);
  if (!has) return;
  const pos = Math.max(0, Math.min(playing.position || 0, dur));
  el.epBarFill.style.width = (pos / dur) * 100 + "%";
  el.epCur.textContent = fmtTime(pos);
  el.epDur.textContent = fmtTime(dur);
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

// --- Remotes : store / name / switch between Shou servers ------------------
// One phone can drive several Shou PCs. Each saved remote keeps only its key;
// the address is self-healing — on every successful load we refresh it to the
// host that just worked (and remember the portable <name>.local), so changing
// networks doesn't mean re-adding it. The set follows you across origins via a
// short-lived URL fragment, since localStorage is per-origin.
const RMT_KEY = "shou.remotes.v1";

function b64encode(str) { return btoa(unescape(encodeURIComponent(str))); }
function b64decode(b)   { return decodeURIComponent(escape(atob(b))); }
function rmtUid()       { return "r" + Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }

function rmtLoad() {
  try { const a = JSON.parse(localStorage.getItem(RMT_KEY)); return Array.isArray(a) ? a : []; }
  catch (e) { return []; }
}
function rmtSave(list) {
  try { localStorage.setItem(RMT_KEY, JSON.stringify(list)); } catch (e) {}
  rmtSyncNative();
}

// Push the whole saved-servers set down to the native shell so Wake-on-LAN, per-server
// shortcuts, the widget and the tile all know about every PC and its MAC.
function rmtSyncNative() {
  if (NATIVE && NATIVE.syncRemotes) {
    try { NATIVE.syncRemotes(localStorage.getItem(RMT_KEY) || "[]"); } catch (e) {}
  }
}

// Union an incoming set (carried in the URL fragment) into our own, keyed by token.
function rmtMerge(incoming) {
  if (!Array.isArray(incoming)) return;
  const list = rmtLoad();
  const byKey = new Map(list.map((r) => [r.key, r]));
  for (const inc of incoming) {
    if (!inc || !inc.key) continue;
    const ex = byKey.get(inc.key);
    if (ex) {                              // keep our (fresher) address, fill any gaps
      if (!ex.name && inc.name) ex.name = inc.name;
      if (!ex.hostname && inc.hostname) ex.hostname = inc.hostname;
      if (!ex.host && inc.host) ex.host = inc.host;
      if (!ex.port && inc.port) ex.port = inc.port;
      if (!ex.mac && inc.mac) ex.mac = inc.mac;
    } else {
      const r = { id: inc.id || rmtUid(), name: inc.name || "", key: inc.key,
                  host: inc.host || "", hostname: inc.hostname || "", port: inc.port || "4100",
                  mac: inc.mac || "" };
      list.push(r); byKey.set(r.key, r);
    }
  }
  rmtSave(list);
}

function rmtIngestHash() {
  const m = location.hash.match(/(?:^#|&)shou=([^&]+)/);
  if (!m) return;
  try { rmtMerge(JSON.parse(b64decode(decodeURIComponent(m[1])))); } catch (e) {}
  history.replaceState(null, "", location.pathname + location.search);  // don't let it linger
}

// Record the server that's serving this page: self-heal its address, or auto-add
// it the first time. Returns the active remote.
function rmtRegisterCurrent() {
  if (!TOKEN) return null;
  const s = window.SHOU_SERVER || {};
  const reached = location.hostname;                       // the address that just worked
  const port = (location.port || s.port || "4100") + "";
  const list = rmtLoad();
  let cur = list.find((r) => r.key === TOKEN);
  if (!cur) {
    cur = { id: rmtUid(), name: s.name || reached || "Shou", key: TOKEN,
            host: reached, hostname: s.host || "", port, mac: "" };
    list.push(cur);
  } else {
    if (reached) cur.host = reached;
    if (s.host) cur.hostname = s.host;                     // portable <name>.local
    if (s.port) cur.port = s.port + "";
    if (!cur.name) cur.name = s.name || reached;
  }
  rmtSave(list);
  // Tell the native shell which PC this WebView is driving, so background features
  // (media controls, widget, Wake-on-LAN) target the same one.
  if (NATIVE && NATIVE.setActive) {
    try { NATIVE.setActive(cur.key, reached || cur.host || "", (cur.port || "4100") + "", cur.name || ""); }
    catch (e) {}
  }
  return cur;
}

function rmtUpdateBrand(cur) {
  const node = document.getElementById("brand-remote");
  if (node && cur && cur.name) node.textContent = cur.name;
}

// Reachability + identity check against a candidate host (CORS-open /whoami).
function rmtProbe(host, port) {
  return new Promise((res) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; res(v); } };
    const t = setTimeout(() => finish(false), 2600);
    fetch(`http://${host}:${port}/whoami`, { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then((j) => { clearTimeout(t); finish(!!(j && j.app === "shou")); })
      .catch(() => { clearTimeout(t); finish(false); });
  });
}

function rmtNote(msg, isErr) {
  const e = document.getElementById("rmt-error");
  if (!e) return;
  e.textContent = msg || "";
  e.classList.toggle("hidden", !msg);
  e.classList.toggle("is-error", !!isErr);
}

// Find the remote on the current network and hop to it, carrying the whole set
// across the origin boundary in the fragment.
async function rmtSwitch(remote) {
  if (remote.key === TOKEN) { rmtClose(); return; }       // already here
  if (navigator.vibrate) navigator.vibrate(12);
  const port = (remote.port || "4100") + "";
  const cands = [];
  if (remote.host) cands.push(remote.host);
  if (remote.hostname && !cands.includes(remote.hostname)) cands.push(remote.hostname);
  if (!cands.length) { rmtNote("This remote has no address — edit it to add one.", true); return; }

  rmtNote("Finding “" + (remote.name || "Shou") + "” on your network…", false);
  let reach = null;
  for (const h of cands) { if (await rmtProbe(h, port)) { reach = h; break; } }
  if (!reach) {
    rmtNote("Couldn't reach “" + (remote.name || "Shou") + "”. Is its PC on, on this network, and running Shou?", true);
    return;
  }
  remote.host = reach;                                     // remember what worked
  const list = rmtLoad();
  const i = list.findIndex((r) => r.key === remote.key);
  if (i >= 0) { list[i].host = reach; rmtSave(list); }
  const bundle = encodeURIComponent(b64encode(JSON.stringify(rmtLoad())));
  location.href = `http://${reach}:${port}/remote?k=${encodeURIComponent(remote.key)}#shou=${bundle}`;
}

function rmtDelete(remote) {
  if (navigator.vibrate) navigator.vibrate(10);
  rmtSave(rmtLoad().filter((r) => r.id !== remote.id));
  rmtRender();
}

let rmtEditing = null;  // remote being edited, or null when adding
function rmtOpenForm(remote) {
  rmtEditing = remote || null;
  document.getElementById("rmt-form-title").textContent = remote ? "Edit remote" : "Add a remote";
  document.getElementById("rmt-f-name").value = (remote && remote.name) || "";
  document.getElementById("rmt-f-host").value = (remote && (remote.host || remote.hostname)) || "";
  document.getElementById("rmt-f-port").value = (remote && remote.port) || "4100";
  document.getElementById("rmt-f-key").value = (remote && remote.key) || "";
  document.getElementById("rmt-f-mac").value = (remote && remote.mac) || "";
  // The MAC / Wake-on-LAN field only does anything inside the native app.
  const macField = document.getElementById("rmt-field-mac");
  if (macField) macField.classList.toggle("hidden", !NATIVE);
  document.getElementById("rmt-form").classList.remove("hidden");
  document.getElementById("rmt-list").classList.add("dim");
  rmtNote("", false);
  document.getElementById("rmt-f-name").focus();
}
function rmtCloseForm() {
  rmtEditing = null;
  document.getElementById("rmt-form").classList.add("hidden");
  document.getElementById("rmt-list").classList.remove("dim");
}
function rmtFormSubmit() {
  const name = document.getElementById("rmt-f-name").value.trim();
  const host = document.getElementById("rmt-f-host").value.trim();
  const port = document.getElementById("rmt-f-port").value.trim() || "4100";
  const key  = document.getElementById("rmt-f-key").value.trim();
  const mac  = document.getElementById("rmt-f-mac").value.trim();
  if (!host || !key) { rmtNote("Host and key are both required.", true); return; }
  const list = rmtLoad();
  let saved;
  if (rmtEditing) {
    const i = list.findIndex((r) => r.id === rmtEditing.id);
    if (i >= 0) { list[i] = { ...list[i], name: name || list[i].name, host, port, key, mac }; saved = list[i]; }
  } else {
    const ex = list.find((r) => r.key === key);           // de-dup by token
    if (ex) { ex.name = name || ex.name; ex.host = host; ex.port = port; ex.mac = mac || ex.mac; saved = ex; }
    else { saved = { id: rmtUid(), name: name || host, key, host, hostname: "", port, mac }; list.push(saved); }
  }
  rmtSave(list);
  rmtCloseForm();
  rmtRender();
  if (saved && saved.key !== TOKEN) rmtSwitch(saved);      // "Save & connect"
}

function rmtRender() {
  const wrap = document.getElementById("rmt-list");
  if (!wrap) return;
  const list = rmtLoad();
  wrap.innerHTML = "";
  if (!list.length) {
    wrap.innerHTML = `<div class="rmt-empty">No remotes saved yet.<br>Tap ＋ to add one.</div>`;
    return;
  }
  list.forEach((r) => {
    const active = r.key === TOKEN;
    const addr = (r.host || r.hostname || "?") + ":" + (r.port || "4100");
    const card = document.createElement("div");
    card.className = "rmt-card" + (active ? " active" : "");
    card.innerHTML = `
      <button type="button" class="rmt-pick">
        <span class="rmt-glyph">朱</span>
        <span class="rmt-info">
          <span class="rmt-name">${escapeHtml(r.name || "Shou")}</span>
          <span class="rmt-host">${escapeHtml(addr)}</span>
        </span>
        ${active
          ? `<span class="rmt-badge">live</span>`
          : `<svg class="rmt-go" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg>`}
      </button>
      <div class="rmt-card-actions">
        ${NATIVE && r.mac ? `<button type="button" class="rmt-mini rmt-wake" data-wake>Wake PC</button>` : ``}
        <button type="button" class="rmt-mini" data-edit>Rename</button>
        <button type="button" class="rmt-mini rmt-del" data-del>Delete</button>
      </div>`;
    card.querySelector(".rmt-pick").addEventListener("click", () => rmtSwitch(r));
    card.querySelector("[data-edit]").addEventListener("click", () => rmtOpenForm(r));
    const wake = card.querySelector("[data-wake]");
    if (wake) {
      wake.addEventListener("click", (ev) => {
        ev.stopPropagation();
        if (navigator.vibrate) navigator.vibrate(12);
        let ok = false;
        try { ok = NATIVE.wake(r.key); } catch (e) {}
        wake.textContent = ok ? "Waking…" : "No MAC";
        setTimeout(() => { if (wake.isConnected) wake.textContent = "Wake PC"; }, 2200);
      });
    }
    const del = card.querySelector("[data-del]");
    del.addEventListener("click", () => {
      if (del.dataset.armed) { rmtDelete(r); return; }
      del.dataset.armed = "1"; del.textContent = "Confirm?";
      setTimeout(() => { if (del.isConnected) { del.dataset.armed = ""; del.textContent = "Delete"; } }, 2600);
    });
    wrap.appendChild(card);
  });
}

function rmtOpen() {
  if (navigator.vibrate) navigator.vibrate(8);
  rmtNote("", false); rmtCloseForm(); rmtRender();
  const p = document.getElementById("remotes-panel");
  p.classList.remove("hidden"); p.setAttribute("aria-hidden", "false");
  document.body.classList.add("rmt-open");
}
function rmtClose() {
  const p = document.getElementById("remotes-panel");
  p.classList.add("hidden"); p.setAttribute("aria-hidden", "true");
  document.body.classList.remove("rmt-open");
}

// mDNS discovery (native only): the shell scans for _shou._tcp and calls this back with
// the servers it resolved. Tap one to pre-fill the add form (you still paste the key).
function rmtRenderFound(found, done) {
  const wrap = document.getElementById("rmt-found");
  if (!wrap) return;
  const label = document.getElementById("rmt-scan-label");
  if (label) {
    label.textContent = done
      ? (found.length ? "Scan again" : "Nothing found — scan again")
      : "Scanning…";
  }
  wrap.innerHTML = "";
  found.forEach((f) => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "rmt-found-card";
    card.innerHTML =
      `<svg class="rmt-found-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/></svg>` +
      `<span class="rmt-found-meta"><span class="rmt-found-name">${escapeHtml(f.name || "Shou")}</span>` +
      `<span class="rmt-found-addr">${escapeHtml((f.host || "") + ":" + (f.port || 4100))}</span></span>` +
      `<span class="rmt-found-add">Use</span>`;
    card.addEventListener("click", () => {
      rmtOpenForm(null);
      document.getElementById("rmt-f-name").value = f.name || "";
      document.getElementById("rmt-f-host").value = f.host || "";
      document.getElementById("rmt-f-port").value = (f.port || 4100) + "";
      document.getElementById("rmt-f-key").focus();
    });
    wrap.appendChild(card);
  });
}
window.shouOnScan = function (jsonStr, done) {
  let arr = [];
  try { arr = JSON.parse(jsonStr) || []; } catch (e) {}
  rmtRenderFound(arr, done);
};

(function rmtInit() {
  const brand = document.getElementById("brand-btn");
  if (brand) brand.addEventListener("click", rmtOpen);
  const wire = (id, fn) => { const n = document.getElementById(id); if (n) n.addEventListener("click", fn); };
  wire("rmt-close", rmtClose);
  wire("rmt-add", () => rmtOpenForm(null));
  wire("rmt-cancel", rmtCloseForm);
  const form = document.getElementById("rmt-form");
  if (form) form.addEventListener("submit", (e) => { e.preventDefault(); rmtFormSubmit(); });

  // Native-only "find PCs on this network" button.
  const scanBtn = document.getElementById("rmt-scan");
  if (scanBtn && NATIVE && NATIVE.scan) {
    scanBtn.classList.remove("hidden");
    scanBtn.addEventListener("click", () => {
      if (navigator.vibrate) navigator.vibrate(8);
      const label = document.getElementById("rmt-scan-label");
      if (label) label.textContent = "Scanning…";
      const found = document.getElementById("rmt-found");
      if (found) found.innerHTML = "";
      try { NATIVE.scan(); } catch (e) {}
    });
  }

  rmtIngestHash();
  rmtUpdateBrand(rmtRegisterCurrent());
  rmtSyncNative();  // make sure the shell has the full set on first load
})();

// --- Cast player : watch the "thrown" episode here on the phone ------------
// The server re-resolves a phone-playable stream and proxies it (same-origin, with the
// CDN Referer it needs). We play it in a full-screen overlay, seeked to where the PC
// was. HLS goes through hls.js where supported, else the <video> plays it natively.
let castSig = "";       // current source signature, so we only (re)load on change
let castHls = null;
let castOpen = false;
const castEl = (id) => document.getElementById(id);

function applyCast(cast) {
  if (!castEl("cast")) return;
  if (!cast || !cast.active) { closeCast(); return; }
  openCast();
  const spin = castEl("cast-spin");
  const err = castEl("cast-error");
  castEl("cast-title").textContent =
    (cast.title || "Shou") + (cast.episode ? "  ·  EP " + cast.episode : "");

  if (cast.error) { showCastError(cast.error); castSig = ""; return; }
  err.classList.add("hidden");
  if (cast.resolving || !cast.src) { spin.classList.remove("hidden"); return; }
  spin.classList.add("hidden");

  const sig = cast.kind + "|" + cast.src;
  if (sig !== castSig) { castSig = sig; setupCastVideo(cast); }
}

function setupCastVideo(cast) {
  teardownCastVideo();
  const video = castEl("cast-video");
  const seekPlay = () => {
    try { if (cast.position > 0) video.currentTime = cast.position; } catch (e) {}
    video.play().catch(() => {});
  };
  if (cast.sub) {
    const tr = document.createElement("track");
    tr.kind = "subtitles"; tr.label = "Subtitles"; tr.srclang = "en";
    tr.default = true; tr.src = cast.sub;
    video.appendChild(tr);
  }
  if (cast.kind === "hls" && window.Hls && window.Hls.isSupported()) {
    const hls = new Hls({ enableWorker: true });
    castHls = hls;
    hls.on(Hls.Events.MANIFEST_PARSED, seekPlay);
    hls.on(Hls.Events.ERROR, (e, data) => {
      if (data && data.fatal) showCastError("This source wouldn't play on the phone.");
    });
    hls.loadSource(cast.src);
    hls.attachMedia(video);
  } else {
    video.src = cast.src;  // native HLS (iOS) or a direct file
    video.addEventListener("loadedmetadata", seekPlay, { once: true });
    video.addEventListener("error",
      () => showCastError("This source wouldn't play on the phone."), { once: true });
    video.load();
  }
}

function teardownCastVideo() {
  const video = castEl("cast-video");
  if (castHls) { try { castHls.destroy(); } catch (e) {} castHls = null; }
  if (video) {
    try { video.pause(); } catch (e) {}
    video.removeAttribute("src");
    video.querySelectorAll("track").forEach((t) => t.remove());
    try { video.load(); } catch (e) {}
  }
}

function showCastError(msg) {
  const err = castEl("cast-error");
  castEl("cast-spin").classList.add("hidden");
  err.querySelector(".cast-error-msg").textContent = msg;
  err.classList.remove("hidden");
  teardownCastVideo();
}

function openCast() {
  if (castOpen) return;
  castOpen = true;
  const o = castEl("cast");
  o.classList.remove("hidden"); o.setAttribute("aria-hidden", "false");
  document.body.classList.add("cast-on");
  if (navigator.vibrate) navigator.vibrate(22);
}

function closeCast() {
  castOpen = false;
  castSig = "";
  teardownCastVideo();
  const o = castEl("cast");
  if (o) { o.classList.add("hidden"); o.setAttribute("aria-hidden", "true"); }
  if (castEl("cast-spin")) castEl("cast-spin").classList.remove("hidden");
  if (castEl("cast-error")) castEl("cast-error").classList.add("hidden");
  document.body.classList.remove("cast-on");
}

// Throw it back to the PC: clear the cast (the server un-pauses mpv) and close here.
function throwBack() {
  if (navigator.vibrate) navigator.vibrate(12);
  post("/cast/clear");
  closeCast();
}

(function castInit() {
  const wire = (id, fn) => { const n = document.getElementById(id); if (n) n.addEventListener("click", fn); };
  wire("throw-btn", throwToPhone);
  wire("cast-close", throwBack);
  wire("cast-error-close", throwBack);
})();

// --- PWA service worker (registers only in a secure context; no-ops on http) -
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}
