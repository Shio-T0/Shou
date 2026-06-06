// Shou kiosk — a pure live view driven by SocketIO "state" events.
// All control comes from the phone web-remote (HTTP POST -> server -> SocketIO).

const socket = io();

const el = {
  backdropA: document.getElementById("backdrop-a"),
  backdropB: document.getElementById("backdrop-b"),
  count: document.getElementById("count"),
  listlabel: document.getElementById("listlabel"),
  stage: document.getElementById("stage"),
  carousel: document.getElementById("carousel"),
  info: document.getElementById("info"),
  infoKicker: document.getElementById("info-kicker"),
  infoTitle: document.getElementById("info-title"),
  infoEpisode: document.getElementById("info-episode"),
  infoBar: document.getElementById("info-bar"),
  sequelView: document.getElementById("sequel-view"),
  seqFinished: document.getElementById("seq-finished"),
  seqTitle: document.getElementById("seq-title"),
  playingView: document.getElementById("playing-view"),
  playingMsg: document.getElementById("playing-msg"),
  statusView: document.getElementById("status-view"),
  statusSpinner: document.getElementById("status-spinner"),
  statusMsg: document.getElementById("status-msg"),
  resumeShelf: document.getElementById("resume-shelf"),
  resumeStrip: document.getElementById("resume-strip"),
  ratingView: document.getElementById("rating-view"),
  ratingBg: document.getElementById("rating-bg"),
  ratingTitle: document.getElementById("rating-title"),
  ratingStars: document.getElementById("rating-stars"),
  ratingScoreNum: document.getElementById("rating-score-num"),
  ratingScoreScale: document.getElementById("rating-score-scale"),
  searchView: document.getElementById("search-view"),
  searchQuery: document.getElementById("search-query"),
  searchPh: document.getElementById("search-ph"),
  searchStatus: document.getElementById("search-status"),
  searchFilters: document.getElementById("search-filters"),
  searchList: document.getElementById("search-list"),
  searchEmpty: document.getElementById("search-empty"),
  detailView: document.getElementById("detail-view"),
  detailBg: document.getElementById("detail-bg"),
  detailCover: document.getElementById("detail-cover"),
  detailStatus: document.getElementById("detail-status"),
  detailTitle: document.getElementById("detail-title"),
  detailMeta: document.getElementById("detail-meta"),
  detailGenres: document.getElementById("detail-genres"),
  detailDesc: document.getElementById("detail-desc"),
};

// Human labels + accent class for each AniList list status.
const STATUS_LABEL = {
  CURRENT: "Watching", PLANNING: "Planned", COMPLETED: "Completed",
  PAUSED: "Paused", DROPPED: "Dropped", REPEATING: "Rewatching",
};
const STATUS_CLASS = {
  CURRENT: "st-current", PLANNING: "st-planning", COMPLETED: "st-completed",
  PAUSED: "st-paused", DROPPED: "st-dropped", REPEATING: "st-current",
};

// How many continue-watching chips to show on the kiosk's left panel.
const RESUME_SHELF_MAX = 4;

const LIST_LABEL = { watching: "WATCHING", planned: "PLAN TO WATCH", search: "SEARCH NEW" };

let cardEls = [];
let renderedSig = "";

// How many neighbours to keep visible on each side of the centre card.
const VISIBLE = 3;

function buildCards(items) {
  el.carousel.innerHTML = "";
  cardEls = items.map((it, i) => {
    const card = document.createElement("div");
    card.className = "card intro";
    card.style.animationDelay = Math.min(i, 8) * 55 + "ms";

    if (it.cover) {
      const img = document.createElement("img");
      img.src = it.cover;
      img.alt = it.title;
      card.appendChild(img);
    } else {
      card.style.background = it.color || "#17161c";
    }

    const badge = document.createElement("div");
    if (it.caughtUp) {
      badge.className = "badge done";
      badge.textContent = "CAUGHT UP";
    } else {
      badge.className = "badge";
      badge.textContent = "EP " + ((it.progress || 0) + 1);
    }
    card.appendChild(badge);

    // Drop .intro once the cascade-in finishes — a *filling* animation keeps
    // overriding `transform` in the cascade, which would suppress the coverflow
    // glide transition on every later selection change.
    card.addEventListener("animationend", () => card.classList.remove("intro"), { once: true });

    el.carousel.appendChild(card);
    return card;
  });
}

function layout(items, cursor) {
  const spacing = 18; // vw between adjacent cards
  cardEls.forEach((card, i) => {
    const offset = i - cursor;
    const abs = Math.abs(offset);
    card.classList.remove("active", "dim", "hiddenCard");

    if (abs > VISIBLE) {
      card.classList.add("hiddenCard");
      card.style.transform = `translateX(${offset * spacing}vw) scale(0.4)`;
      return;
    }

    const scale = offset === 0 ? 1.2 : Math.max(0.64, 0.9 - abs * 0.08);
    const translate = offset * spacing;
    const rotate = offset === 0 ? 0 : (offset > 0 ? -24 : 24);
    const z = -abs * 44;
    card.style.transform =
      `translateX(${translate}vw) translateZ(${z}px) rotateY(${rotate}deg) scale(${scale})`;
    card.style.zIndex = String(100 - abs);

    if (offset === 0) card.classList.add("active");
    else card.classList.add("dim");
  });
}

function setTitle(text) {
  if (el.infoTitle.textContent === text) return;
  el.infoTitle.textContent = text;
  el.infoTitle.classList.remove("swap");
  void el.infoTitle.offsetWidth; // reflow to restart the animation
  el.infoTitle.classList.add("swap");
}

// Crossfade between two stacked backdrop layers (background-image can't transition).
let frontBd = el.backdropA;
let backBd = el.backdropB;
let lastBg = null;
function setBackdrop(banner, cover, color) {
  const img = banner ? `url(${banner})` : cover ? `url(${cover})` : "none";
  const key = img + "|" + (color || "");
  if (key === lastBg) return;
  lastBg = key;
  backBd.style.backgroundColor = color || "#17161c";
  backBd.style.backgroundImage = img;
  backBd.classList.add("is-on");
  frontBd.classList.remove("is-on");
  const tmp = frontBd;
  frontBd = backBd;
  backBd = tmp;
}

function updateInfo(items, cursor) {
  const it = items[cursor];
  if (!it) return;
  el.info.classList.toggle("caughtup", !!it.caughtUp);
  el.infoKicker.textContent = it.caughtUp
    ? "CAUGHT UP"
    : (currentList === "planned" ? "PLAN TO WATCH" : "NOW WATCHING");
  setTitle(it.title);
  el.infoEpisode.textContent = it.episodeText;
  const denom = it.available || it.total;
  const pct = denom ? Math.min(100, Math.round((it.progress / denom) * 100)) : 0;
  el.infoBar.style.width = pct + "%";
  setBackdrop(it.banner, it.cover, it.color);
}

// --- Continue-watching shelf (display only) --------------------------------
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

let resumeSig = "";
function renderResume(history, view) {
  history = history || [];
  const show = view === "grid" && history.length > 0;
  if (!show) {
    el.resumeShelf.classList.add("hidden");
    return;
  }
  const slice = history.slice(0, RESUME_SHELF_MAX);
  const sig = slice
    .map((e) => `${e.media_id}:${e.episode}:${Math.round(e.position || 0)}`)
    .join("|");
  if (sig !== resumeSig) {
    resumeSig = sig;
    el.resumeStrip.innerHTML = "";
    slice.forEach((e) => {
      const pct = e.duration
        ? Math.min(100, Math.round((e.position / e.duration) * 100))
        : Math.round(e.percent || 0);
      const coverStyle = e.cover
        ? `background-image:url(${e.cover})`
        : `background-color:${e.color || "#1f2233"}`;
      const chip = document.createElement("div");
      chip.className = "rchip";
      chip.innerHTML = `
        <div class="rchip-cover" style="${coverStyle}"></div>
        <div class="rchip-meta">
          <div class="rchip-title">${escapeHtml(e.title)}</div>
          <div class="rchip-ep">EP ${escapeHtml(e.episode)} · ${fmtTime(e.position)}</div>
          <div class="rchip-bar"><span style="width:${pct}%"></span></div>
        </div>`;
      el.resumeStrip.appendChild(chip);
    });
  }
  el.resumeShelf.classList.remove("hidden");
}

function showOnly(view) {
  el.stage.classList.toggle("hidden", view !== "grid");
  el.sequelView.classList.toggle("hidden", view !== "sequel");
  el.playingView.classList.toggle("hidden", view !== "playing");
  el.ratingView.classList.toggle("hidden", view !== "rating");
  el.searchView.classList.toggle("hidden", view !== "search");
  el.detailView.classList.toggle("hidden", view !== "detail");
  const isStatus = view === "loading" || view === "empty" || view === "error";
  el.statusView.classList.toggle("hidden", !isStatus);
}

// --- Search new -------------------------------------------------------------
function metaLine(d) {
  const bits = [];
  if (d.format) bits.push(d.format);
  if (d.year) bits.push(d.year);
  if (d.episodes) bits.push(d.episodes + (d.episodes === 1 ? " ep" : " eps"));
  if (d.duration) bits.push(d.duration + "m");
  if (d.studio) bits.push(d.studio);
  if (d.score) bits.push("★ " + d.score);
  return bits.join("  ·  ");
}

let searchResultsSig = "";
let searchCursor = -1;
let searchGenresSig = "";
function renderSearch(search) {
  const q = (search && search.query) || "";
  el.searchQuery.textContent = q;
  el.searchPh.classList.toggle("hidden", q.length > 0);

  const results = (search && search.results) || [];
  const busy = !!(search && search.busy);
  const genres = (search && search.genres) || [];

  // Active genre filters as chips; with no typed query this is a browse, so label it.
  const genSig = (q ? "q" : "browse") + "|" + genres.join(",");
  if (genSig !== searchGenresSig) {
    searchGenresSig = genSig;
    el.searchFilters.innerHTML = "";
    if (!q) {
      const lead = document.createElement("span");
      lead.className = "search-filters-lead";
      lead.textContent = genres.length ? "Top rated in" : "Top rated on AniList";
      el.searchFilters.appendChild(lead);
    } else if (genres.length) {
      const lead = document.createElement("span");
      lead.className = "search-filters-lead";
      lead.textContent = "Filtered by";
      el.searchFilters.appendChild(lead);
    }
    genres.forEach((g, i) => {
      const chip = document.createElement("span");
      chip.className = "search-chip";
      chip.style.animationDelay = i * 45 + "ms";
      chip.textContent = g;
      el.searchFilters.appendChild(chip);
    });
    el.searchFilters.classList.toggle("hidden", q && !genres.length);
  }

  el.searchStatus.textContent = busy
    ? "searching…"
    : results.length
    ? results.length + " result" + (results.length === 1 ? "" : "s")
    : "";
  el.searchEmpty.classList.toggle("hidden", busy || results.length > 0);

  const sig = results.map((r) => r.id + ":" + (r.listStatus || "")).join(",");
  if (sig !== searchResultsSig) {
    searchResultsSig = sig;
    searchCursor = -1;
    el.searchList.innerHTML = "";
    results.forEach((r, i) => {
      const row = document.createElement("div");
      row.className = "srow intro";
      row.style.animationDelay = Math.min(i, 12) * 38 + "ms";
      row.addEventListener("animationend", () => row.classList.remove("intro"), { once: true });
      const coverStyle = r.cover
        ? `background-image:url(${r.cover})`
        : `background-color:${r.color || "#1f2233"}`;
      const sub = metaLine(r);
      const st = r.listStatus;
      const pill = st
        ? `<span class="srow-status ${STATUS_CLASS[st] || ""}">${STATUS_LABEL[st] || st}</span>`
        : `<span class="srow-status st-none">Not in list</span>`;
      row.innerHTML = `
        <span class="srow-rail" aria-hidden="true"></span>
        <span class="srow-cover" style="${coverStyle}"></span>
        <span class="srow-main">
          <span class="srow-title">${escapeHtml(r.title)}</span>
          <span class="srow-sub">${escapeHtml(sub)}</span>
        </span>
        ${pill}`;
      el.searchList.appendChild(row);
    });
  }

  const cursor = (search && search.cursor) || 0;
  if (cursor !== searchCursor) {
    const rows = el.searchList.children;
    if (searchCursor >= 0 && rows[searchCursor]) rows[searchCursor].classList.remove("active");
    if (rows[cursor]) {
      rows[cursor].classList.add("active");
      rows[cursor].scrollIntoView({ block: "center", behavior: "smooth" });
    }
    searchCursor = cursor;
  }
}

let detailId = "";
let detailJustSet = "";
function renderDetail(d) {
  if (!d) return;
  const fresh = String(d.id) !== detailId;
  if (fresh) {
    detailId = String(d.id);
    detailJustSet = "";
    el.detailView.classList.remove("is-fresh");
    void el.detailView.offsetWidth; // restart the entrance animation
    el.detailView.classList.add("is-fresh");
    el.detailBg.style.backgroundColor = d.color || "#17161c";
    el.detailBg.style.backgroundImage = d.banner
      ? `url(${d.banner})`
      : d.cover
      ? `url(${d.cover})`
      : "none";
    el.detailCover.src = d.cover || "";
    el.detailCover.style.opacity = d.cover ? "1" : "0";
    el.detailTitle.textContent = d.title || "—";
  }

  el.detailMeta.textContent = d.loading ? "Loading…" : metaLine(d);

  el.detailGenres.innerHTML = "";
  (d.genres || []).forEach((g) => {
    const chip = document.createElement("span");
    chip.className = "genre-chip";
    chip.textContent = g;
    el.detailGenres.appendChild(chip);
  });

  el.detailDesc.textContent = d.description || "";

  const st = d.listStatus;
  el.detailStatus.classList.toggle("hidden", false);
  el.detailStatus.className =
    "detail-status-pill " + (st ? STATUS_CLASS[st] || "" : "st-none");
  el.detailStatus.textContent = st ? STATUS_LABEL[st] || st : "Not in your lists";

  // Confirmation flourish whenever a status was just written.
  if (d.justSet && d.justSet !== detailJustSet) {
    detailJustSet = d.justSet;
    el.detailStatus.classList.remove("flash");
    void el.detailStatus.offsetWidth;
    el.detailStatus.classList.add("flash");
  }
}

// --- Series-complete rating -------------------------------------------------
const STAR_PATH =
  "M12 2.4l2.97 6.02 6.64.97-4.8 4.68 1.13 6.61L12 17.6l-5.94 3.12 1.13-6.61" +
  "-4.8-4.68 6.64-.97z";
let ratingPromptId = "";

function starSvg(cls) {
  return `<svg class="${cls}" viewBox="0 0 24 24" fill="currentColor"><path d="${STAR_PATH}"/></svg>`;
}
function buildStars() {
  el.ratingStars.innerHTML = "";
  for (let i = 0; i < 5; i++) {
    const star = document.createElement("div");
    star.className = "star";
    star.style.animationDelay = (0.6 + i * 0.09).toFixed(2) + "s";
    star.innerHTML =
      starSvg("s-base") + `<div class="s-clip" style="width:0%">${starSvg("s-fill")}</div>`;
    el.ratingStars.appendChild(star);
  }
}
function setRatingBg(r) {
  el.ratingBg.style.backgroundColor = r.color || "#17161c";
  el.ratingBg.style.backgroundImage = r.banner
    ? `url(${r.banner})`
    : r.cover
    ? `url(${r.cover})`
    : "none";
}
function fmtScore(r) {
  if (r.format === "POINT_10_DECIMAL") return (Math.round(r.score * 10) / 10).toFixed(1);
  return String(r.score);
}
function renderRating(r) {
  if (!r) return;
  const id = r.media_id + "|" + r.format;
  if (id !== ratingPromptId) {
    // Fresh prompt — (re)build so every entrance animation replays from the top.
    ratingPromptId = id;
    el.ratingView.classList.remove("is-done");
    el.ratingTitle.textContent = r.title || "";
    setRatingBg(r);
    buildStars();
  }
  el.ratingScoreNum.textContent = fmtScore(r);
  el.ratingScoreScale.textContent = "/ " + r.max;
  el.ratingScoreNum.classList.remove("bump");
  void el.ratingScoreNum.offsetWidth;
  el.ratingScoreNum.classList.add("bump");

  const clips = el.ratingStars.querySelectorAll(".s-clip");
  const stars = r.stars || 0;
  requestAnimationFrame(() => {
    clips.forEach((c, i) => {
      const frac = Math.max(0, Math.min(1, stars - i));
      c.style.width = frac * 100 + "%";
    });
  });
  el.ratingView.classList.toggle("is-done", !!r.done);
}

let currentList = "watching";
let currentView = "loading";
let searchStatuses = [];

socket.on("state", (s) => {
  showOnly(s.view);
  currentView = s.view;
  renderResume(s.history, s.view);
  if (s.search && s.search.statuses) searchStatuses = s.search.statuses;
  if (s.list) {
    currentList = s.list;
    el.listlabel.textContent = LIST_LABEL[s.list] || s.list;
  }

  if (s.view === "search") {
    renderSearch(s.search);
    el.count.textContent = "";
  } else if (s.view === "detail") {
    renderDetail(s.search && s.search.detail);
    el.count.textContent = "";
  } else if (s.view === "grid") {
    const sig = s.items.map((i) => i.id).join(",");
    if (sig !== renderedSig) {
      buildCards(s.items);
      renderedSig = sig;
    }
    el.count.textContent = s.items.length ? `${s.cursor + 1} / ${s.items.length}` : "";
    layout(s.items, s.cursor);
    updateInfo(s.items, s.cursor);
  } else if (s.view === "sequel" && s.sequel) {
    el.seqFinished.textContent = s.sequel.finished;
    el.seqTitle.textContent = s.sequel.sequel_title;
  } else if (s.view === "playing") {
    el.playingMsg.textContent = s.message || "Launching…";
  } else if (s.view === "rating") {
    renderRating(s.rating);
    el.count.textContent = "";
  } else {
    // loading / empty / error
    el.statusSpinner.style.display = s.view === "loading" ? "block" : "none";
    el.statusMsg.textContent =
      s.message || (s.view === "empty" ? "Nothing to watch." : "Loading…");
    setBackdrop("", "", "#0e0d11");
    el.count.textContent = "";
  }
});

// --- Physical keyboard (search mode) ----------------------------------------
// The kiosk normally takes no input, but in "search new" the computer's keyboard
// drives the same shared query the phone keyboard does — the server is the single
// source of truth, so both screens stay in sync.
function api(path) {
  fetch(path, { method: "POST" }).catch(() => {});
}

document.addEventListener("keydown", (e) => {
  if (e.metaKey || e.ctrlKey || e.altKey) return;

  if (currentView === "search") {
    if (e.key === "Backspace") { e.preventDefault(); api("/search/back"); }
    else if (e.key === "Enter") { e.preventDefault(); api("/select"); }
    else if (e.key === "Escape") { e.preventDefault(); api("/back"); }
    else if (e.key === "ArrowUp") { e.preventDefault(); api("/left"); }
    else if (e.key === "ArrowDown") { e.preventDefault(); api("/right"); }
    else if (e.key.length === 1 && e.key >= " ") {
      e.preventDefault();
      api("/search/key?c=" + encodeURIComponent(e.key));
    }
  } else if (currentView === "detail") {
    if (e.key === "Escape" || e.key === "Backspace") { e.preventDefault(); api("/back"); }
    else if (/^[1-5]$/.test(e.key) && searchStatuses[+e.key - 1]) {
      e.preventDefault();
      api("/status?to=" + searchStatuses[+e.key - 1][0]);
    } else if (e.key === "0") {
      e.preventDefault();
      api("/status?to=REMOVE");
    }
  }
});
