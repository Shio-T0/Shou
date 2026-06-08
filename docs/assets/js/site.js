/* ============================================================================
   Shou — landing site behaviour
   Everything degrades gracefully: no GSAP / no Lenis / reduced-motion all work.
   ========================================================================== */
(() => {
  "use strict";
  const $  = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const hasGSAP = () => typeof window.gsap !== "undefined";

  /* ── Demo data ──────────────────────────────────────────────────────── */
  // Stylized covers (no third-party art shipped) — kanji + gradient + title.
  const LISTS = {
    watching: [
      { t: "Frieren",            jp: "葬送",   g: "linear-gradient(135deg,#6ea8ff,#3a4fb0)" },
      { t: "Vinland Saga",       jp: "海戦",   g: "linear-gradient(135deg,#3fae82,#16413a)" },
      { t: "Apothecary Diaries", jp: "薬屋",   g: "linear-gradient(135deg,#ff8a5b,#a3322a)" },
      { t: "Monogatari",         jp: "物語",   g: "linear-gradient(135deg,#ff6a9a,#5a2247)" },
      { t: "Cowboy Bebop",       jp: "宇宙",   g: "linear-gradient(135deg,#c9a86a,#3a2a18)" },
    ],
    planned: [
      { t: "Mushishi",           jp: "蟲師",   g: "linear-gradient(135deg,#8fbf8a,#26402a)" },
      { t: "Texhnolyze",         jp: "崩壊",   g: "linear-gradient(135deg,#9aa0aa,#2a2d36)" },
      { t: "Ping Pong",          jp: "卓球",   g: "linear-gradient(135deg,#ff5a44,#7a1f16)" },
      { t: "Sonny Boy",          jp: "漂流",   g: "linear-gradient(135deg,#6ad6c8,#1b3f3a)" },
      { t: "Kaiba",              jp: "記憶",   g: "linear-gradient(135deg,#ff9ad1,#5a2a52)" },
    ],
  };

  const FEATURES = [
    { jp: "三次元", t: "3D coverflow browse", d: "Your Currently-Watching and Plan-to-Watch lists as a cinematic coverflow on the big screen — spun from your thumb.", i: "layers" },
    { jp: "一押", t: "One-tap play", d: "The next unwatched episode, fullscreen, instantly. No file picking, no menus, no negotiating.", i: "play" },
    { jp: "続き", t: "Continue watching", d: "Resume a few seconds before you stopped — every paused show, one tap away, with a forget button.", i: "rewind" },
    { jp: "評価", t: "Cinematic finale rating", d: "Finish the last episode and a rating page blooms: animated stars, a hanko stamp, a small chime.", i: "star" },
    { jp: "探索", t: "Search all of AniList", d: "Genre and tag filters, top-rated lists, season hopping, set a status — all from the couch.", i: "search" },
    { jp: "投影", t: "Throw to phone", d: "Send the playing episode to your phone mid-watch, resume to the frame, and throw it back to the PC.", i: "cast" },
    { jp: "追上", t: "Caught up?", d: "Reached the end of what’s aired? Shou recommends the sequel or plays the latest episode.", i: "up" },
    { jp: "同期", t: "Auto-mark watched", d: "Optionally tick episodes on AniList as you go and flip a finished show to Completed. Hands-free bookkeeping.", i: "check" },
    { jp: "多台", t: "Multi-server switcher", d: "Name your machines; the remote self-heals each address by <name>.local and re-finds them on any network.", i: "server" },
  ];

  const ICONS = {
    layers:'<polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/>',
    play:'<polygon points="6 4 20 12 6 20 6 4"/>',
    rewind:'<polyline points="11 19 2 12 11 5 11 19"/><polyline points="22 19 13 12 22 5 22 19"/>',
    star:'<polygon points="12 2 15 9 22 9.3 16.5 14 18.5 21 12 17 5.5 21 7.5 14 2 9.3 9 9 12 2"/>',
    search:'<circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.5" y2="16.5"/>',
    cast:'<rect x="2" y="4" width="20" height="13" rx="2"/><polyline points="8 21 16 21"/><line x1="12" y1="17" x2="12" y2="21"/>',
    up:'<line x1="12" y1="19" x2="12" y2="5"/><polyline points="6 11 12 5 18 11"/>',
    check:'<polyline points="20 6 9 17 4 12"/>',
    server:'<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><line x1="7" y1="7.5" x2="7.01" y2="7.5"/><line x1="7" y1="16.5" x2="7.01" y2="16.5"/>',
  };

  /* ── Nav scrolled state ─────────────────────────────────────────────── */
  const nav = $("#nav");
  const onScrollNav = () => nav.classList.toggle("scrolled", window.scrollY > 12);
  onScrollNav(); addEventListener("scroll", onScrollNav, { passive: true });

  /* ── Boot flourish ──────────────────────────────────────────────────── */
  requestAnimationFrame(() => document.body.classList.replace("boot", "booted"));

  /* ── Cursor aura ────────────────────────────────────────────────────── */
  const ca = $(".cursor-aura");
  if (ca && !reduced && matchMedia("(pointer:fine)").matches) {
    addEventListener("pointermove", (e) => {
      document.documentElement.style.setProperty("--mx", e.clientX + "px");
      document.documentElement.style.setProperty("--my", e.clientY + "px");
      ca.style.opacity = "1";
    }, { passive: true });
  }

  /* ── Backdrop: ember / film-dust particle field ─────────────────────── */
  function initEmbers() {
    if (reduced) return;                         // honour reduced-motion
    const cv = $(".bg-embers");
    if (!cv) return;
    const ctx = cv.getContext("2d");
    let dpr = 1, W = 0, H = 0, parts = [], raf = 0, running = false;

    const resize = () => {
      dpr = Math.min(devicePixelRatio || 1, 2);
      W = cv.clientWidth; H = cv.clientHeight;
      cv.width = W * dpr; cv.height = H * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      // sparse, area-scaled, capped — light on mobile, never silly on ultrawide
      const n = Math.max(14, Math.min(60, Math.round((W * H) / 26000)));
      parts = Array.from({ length: n }, () => spawn(true));
    };
    const spawn = (anywhere) => ({
      x: Math.random() * W,
      y: anywhere ? Math.random() * H : H + 8,
      r: 0.6 + Math.random() * 1.8,
      vy: 0.15 + Math.random() * 0.5,          // slow rise
      drift: (Math.random() - 0.5) * 0.4,
      sway: Math.random() * Math.PI * 2,
      tw: 0.4 + Math.random() * 0.6,           // twinkle base alpha
    });
    const tick = () => {
      ctx.clearRect(0, 0, W, H);
      for (const p of parts) {
        p.y -= p.vy;
        p.sway += 0.01;
        p.x += p.drift + Math.sin(p.sway) * 0.3;
        if (p.y < -8) Object.assign(p, spawn(false));
        const a = p.tw * (0.55 + 0.45 * Math.sin(p.sway * 1.7));
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        ctx.fillStyle = "rgba(255,106,77," + a.toFixed(3) + ")";
        ctx.shadowColor = "rgba(255,74,50,.9)";
        ctx.shadowBlur = 6;
        ctx.fill();
      }
      raf = requestAnimationFrame(tick);
    };
    const start = () => { if (!running) { running = true; tick(); } };
    const stop  = () => { running = false; cancelAnimationFrame(raf); };

    resize();
    addEventListener("resize", resize, { passive: true });
    document.addEventListener("visibilitychange", () =>
      document.hidden ? stop() : start());
    start();
  }
  initEmbers();

  /* ── Hero: the living remote ────────────────────────────────────────── */
  const cf = $("#coverflow");
  const state = { list: "watching", index: 0, playing: false, cast: false };

  function buildCoverflow() {
    cf.innerHTML = "";
    LISTS[state.list].forEach((c, i) => {
      const el = document.createElement("div");
      el.className = "cf-cover";
      el.style.background = c.g;
      el.dataset.i = i;
      el.innerHTML = `<span class="cf-kanji">${c.jp}</span><span class="cf-name">${c.t}</span>`;
      cf.appendChild(el);
    });
    layoutCoverflow();
  }
  function layoutCoverflow() {
    const items = LISTS[state.list];
    $$(".cf-cover", cf).forEach((el, i) => {
      let off = i - state.index;
      const n = items.length;
      if (off > n / 2) off -= n; if (off < -n / 2) off += n;     // wrap to shortest path
      const abs = Math.abs(off);
      const x = off * 46, rot = off * -32, z = -abs * 120, sc = 1 - abs * 0.12;
      el.style.transform = `translateX(${x}%) translateZ(${z}px) rotateY(${rot}deg) scale(${sc})`;
      el.style.opacity = abs > 2 ? 0 : 1 - abs * 0.18;
      el.style.zIndex = String(100 - abs);
      el.style.filter = abs ? `brightness(${1 - abs * 0.22})` : "none";
    });
    syncRemote();
  }
  function syncRemote() {
    const c = LISTS[state.list][state.index];
    const art = $("#ph-art"); art.style.background = c.g;
    $("#ph-title").textContent = c.t;
    if (state.playing) {
      $("#np-label").textContent = `now playing ▸ ${c.t}`;
    } else if (!state.cast) {
      $("#np-label").textContent = "idle — pick something on the remote";
    }
  }
  function step(dir) {
    const n = LISTS[state.list].length;
    state.index = (state.index + dir + n) % n;
    layoutCoverflow();
  }
  function setList(list, i) {
    state.list = list; state.index = 0;
    $("#ph-seg").dataset.i = String(i);
    $$(".seg-btn").forEach((b, bi) => b.classList.toggle("active", bi === i));
    buildCoverflow();
  }
  function togglePlay() {
    state.playing = !state.playing;
    $("#bigscreen").classList.toggle("playing", state.playing);
    $("#t-play").textContent = state.playing ? "❚❚" : "▶";
    const bar = $("#ph-progress");
    bar.style.width = state.playing ? "62%" : "34%";
    syncRemote();
  }

  // throw demo: fly the centre cover from the screen into the phone
  function throwDemo() {
    if (state.cast) return;
    const c = LISTS[state.list][state.index];
    const fly = $("#fly-tile"), dio = $("#diorama");
    const centre = $$(".cf-cover", cf).find((el) => Number(el.dataset.i) === state.index);
    if (!centre) return;
    const dr = dio.getBoundingClientRect();
    const sr = centre.getBoundingClientRect();
    const pr = $("#phone-cast").getBoundingClientRect();
    fly.style.background = c.g;
    fly.style.opacity = "1";
    const animate = (el, frames, opts) =>
      el.animate(frames, { duration: 720, easing: "cubic-bezier(.5,0,.2,1)", fill: "forwards", ...opts });

    const startX = sr.left - dr.left + sr.width / 2 - 30;
    const startY = sr.top - dr.top + sr.height / 2 - 45;
    const endX = pr.left - dr.left + pr.width / 2 - 30;
    const endY = pr.top - dr.top + pr.height / 2 - 45;
    fly.style.transform = `translate(${startX}px,${startY}px)`;
    const a = animate(fly, [
      { transform: `translate(${startX}px,${startY}px) scale(1) rotate(0deg)`, offset: 0 },
      { transform: `translate(${(startX + endX) / 2}px,${Math.min(startY, endY) - 60}px) scale(.9) rotate(-12deg)`, offset: .5 },
      { transform: `translate(${endX}px,${endY}px) scale(.5) rotate(4deg)`, offset: 1 },
    ]);
    a.onfinish = () => {
      fly.style.opacity = "0";
      $("#cast-art").style.background = c.g;
      $("#cast-art").style.backgroundSize = "cover";
      $("#cast-title").textContent = c.t;
      $("#phone").classList.add("casting");
      state.cast = true;
      $("#np-label").textContent = "▸▸ now watching on your phone";
      $("#bigscreen").classList.remove("playing");
    };
  }
  function throwBack() {
    $("#phone").classList.remove("casting");
    state.cast = false;
    state.playing = false;
    $("#t-play").textContent = "▶";
    $("#bigscreen").classList.remove("playing");
    $("#np-label").textContent = "‹‹ resumed on this screen";
    setTimeout(syncRemote, 1400);
  }

  // wire hero controls
  $("#t-prev").onclick = () => step(-1);
  $("#t-next").onclick = () => step(1);
  $("#t-play").onclick = togglePlay;
  $("#t-throw").onclick = throwDemo;
  $("#cast-back").onclick = throwBack;
  $$(".seg-btn").forEach((b, i) => (b.onclick = () => setList(b.dataset.list, i)));
  // drag the big screen to spin
  (() => {
    let down = false, sx = 0, acc = 0;
    const stage = $("#bigscreen");
    const onDown = (x) => { down = true; sx = x; acc = 0; };
    const onMove = (x) => {
      if (!down) return;
      acc += x - sx; sx = x;
      if (Math.abs(acc) > 42) { step(acc > 0 ? -1 : 1); acc = 0; }
    };
    stage.addEventListener("pointerdown", (e) => onDown(e.clientX));
    addEventListener("pointermove", (e) => onMove(e.clientX));
    addEventListener("pointerup", () => (down = false));
  })();
  buildCoverflow();

  /* ── Features grid ──────────────────────────────────────────────────── */
  const grid = $("#feature-grid");
  FEATURES.forEach((f) => {
    const el = document.createElement("article");
    el.className = "fcard reveal-card";
    el.innerHTML =
      `<span class="fjp">${f.jp}</span>` +
      `<span class="ficon"><svg viewBox="0 0 24 24">${ICONS[f.i] || ""}</svg></span>` +
      `<h3>${f.t}</h3><p>${f.d}</p>`;
    grid.appendChild(el);
  });

  /* ── How-it-works diagram (inline SVG) ──────────────────────────────── */
  $("#diagram").innerHTML = `
  <svg viewBox="0 0 920 360" role="img" aria-label="Architecture: phone and Android app drive a Flask + SocketIO server, which talks to AniList and plays via mpv on a browser kiosk.">
    <defs>
      <linearGradient id="dgFill" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#17161c"/><stop offset="1" stop-color="#100f13"/>
      </linearGradient>
    </defs>
    <!-- wires : each routes node-edge → node-edge (elbows share x=360) -->
    <path class="dg-wire" d="M250 110 H360 V180 H430"/><path class="dg-flow" d="M250 110 H360 V180 H430"/>
    <path class="dg-wire" d="M250 250 H360 V180 H430"/><path class="dg-flow" d="M250 250 H360 V180 H430"/>
    <path class="dg-wire" d="M690 180 H790 V120"/><path class="dg-flow" d="M690 180 H790 V120"/>
    <path class="dg-wire" d="M560 210 V260 H300 V300"/><path class="dg-flow" d="M560 210 V260 H300 V300"/>
    <!-- nodes -->
    <g><rect class="dg-node acc" x="40" y="80" width="210" height="60" rx="14"/>
      <text class="dg-label" x="64" y="106">Phone — web remote</text>
      <text class="dg-sub"   x="64" y="125">PWA · the control surface</text></g>
    <g><rect class="dg-node" x="40" y="220" width="210" height="60" rx="14"/>
      <text class="dg-label" x="64" y="246">Android app</text>
      <text class="dg-sub"   x="64" y="265">WebView + native reflexes</text></g>
    <g><rect class="dg-node acc" x="430" y="150" width="260" height="60" rx="14"/>
      <text class="dg-label" x="456" y="176">Flask + SocketIO</text>
      <text class="dg-sub"   x="456" y="195">single source of truth · live</text></g>
    <g><rect class="dg-node" x="700" y="60" width="180" height="60" rx="14"/>
      <text class="dg-label" x="724" y="86">AniList</text>
      <text class="dg-sub"   x="724" y="105">GraphQL · your lists</text></g>
    <g><rect class="dg-node" x="210" y="300" width="180" height="46" rx="12"/>
      <text class="dg-label" x="234" y="329">Kiosk → mpv</text></g>
  </svg>`;

  /* ── Manifesto + reveals via IntersectionObserver ───────────────────── */
  const io = new IntersectionObserver((entries) => {
    entries.forEach((e) => {
      if (!e.isIntersecting) return;
      e.target.classList.add("in", "lit");
      io.unobserve(e.target);
    });
  }, { threshold: 0.18 });

  $$(".reveal-lines span").forEach((s, i) => { s.style.transitionDelay = i * 90 + "ms"; io.observe(s); });
  $$(".shot, .reveal").forEach((el) => io.observe(el));
  // fade the feature cards in, staggered by column
  const cardIO = new IntersectionObserver((entries) => {
    entries.forEach((e) => { if (e.isIntersecting) { e.target.classList.add("in"); cardIO.unobserve(e.target); } });
  }, { threshold: 0.15 });
  $$(".fcard").forEach((c, i) => { c.style.transitionDelay = (i % 3) * 80 + "ms"; cardIO.observe(c); });

  /* ── Copy button ────────────────────────────────────────────────────── */
  const copyBtn = $("#term-copy");
  copyBtn.onclick = async () => {
    try {
      await navigator.clipboard.writeText(copyBtn.dataset.copy);
      copyBtn.textContent = "copied ✓"; copyBtn.classList.add("done");
      setTimeout(() => { copyBtn.textContent = "copy"; copyBtn.classList.remove("done"); }, 1800);
    } catch { copyBtn.textContent = "ctrl+c"; }
  };

  /* ── Live release info from GitHub ──────────────────────────────────── */
  fetch("https://api.github.com/repos/Shio-T0/Shou/releases/latest")
    .then((r) => (r.ok ? r.json() : Promise.reject()))
    .then((rel) => {
      const tag = rel.tag_name || "v1.3.0";
      const apk = (rel.assets || []).find((a) => a.name.endsWith(".apk"));
      $("#ver-pill").textContent = tag;
      $("#apk-pill").textContent = tag;
      if (apk) {
        $("#dl-apk").href = apk.browser_download_url;
        $("#dl-apk-name").textContent = apk.name;
      }
    })
    .catch(() => {/* static fallbacks already in the markup */});

  /* ── GSAP: pinned throw sequence + smooth scroll ────────────────────── */
  function initMotion() {
    if (reduced) return;                 // honour reduced-motion: static layout
    // Lenis smooth scroll
    if (typeof window.Lenis !== "undefined") {
      const lenis = new window.Lenis({ duration: 1.05, smoothWheel: true });
      document.documentElement.classList.add("lenis");
      const raf = (t) => { lenis.raf(t); requestAnimationFrame(raf); };
      requestAnimationFrame(raf);
      if (hasGSAP() && window.ScrollTrigger) lenis.on("scroll", window.ScrollTrigger.update);
    }
    if (!hasGSAP() || !window.ScrollTrigger) return;
    const { gsap } = window; gsap.registerPlugin(window.ScrollTrigger);

    // backdrop parallax — drift the aurora group up and the sprockets down as
    // the page scrolls, giving the "Projection Room" depth. Tiny, scrubbed.
    const docH = () => Math.max(1, document.documentElement.scrollHeight - innerHeight);
    if ($(".bg-aurora")) {
      gsap.to(".bg-aurora", {
        yPercent: -12, ease: "none",
        scrollTrigger: { start: 0, end: docH, scrub: true, invalidateOnRefresh: true },
      });
    }
    if ($(".bg-sprockets")) {
      gsap.to(".bg-sprockets", {
        yPercent: 8, ease: "none",
        scrollTrigger: { start: 0, end: docH, scrub: true, invalidateOnRefresh: true },
      });
    }

    // pinned throw-to-phone scrubbed timeline.
    // Place the tile over the screen (its left/top become the x:0/y:0 origin),
    // then animate the measured delta to the phone centre. Re-measured on refresh.
    const tile = $("#ts-tile");
    if (tile && innerWidth > 900) {
      const place = () => {
        const sR  = $("#throw-stage").getBoundingClientRect();
        const scr = $("#ts-screen").getBoundingClientRect();
        const ph  = $("#ts-phone").getBoundingClientRect();
        const tw  = Math.max(72, scr.width * 0.34), th = tw * 1.5;
        tile.style.width = tw + "px";
        const sx = scr.left - sR.left + scr.width  / 2 - tw / 2;
        const sy = scr.top  - sR.top  + scr.height / 2 - th / 2;
        const ex = ph.left  - sR.left + ph.width   / 2 - tw / 2;
        const ey = ph.top   - sR.top  + ph.height  / 2 - th / 2;
        tile.style.left = sx + "px"; tile.style.top = sy + "px";
        return { dx: ex - sx, dy: ey - sy, arc: -scr.height * 0.16 };
      };
      let d = place();
      gsap.set(tile, { x: 0, y: 0, scale: 1, rotation: 0, transformOrigin: "50% 50%" });
      const tl = gsap.timeline({
        scrollTrigger: {
          trigger: "#throw", start: "top top", end: "+=160%",
          scrub: 0.6, pin: "#throw-pin", invalidateOnRefresh: true,
          onRefresh: () => { d = place(); },
          onUpdate: (self) => {
            const p = self.progress;
            const stepN = p < .12 ? 0 : p < .55 ? 1 : p < .9 ? 2 : 3;
            $$("#throw .throw-steps li").forEach((li) =>
              li.classList.toggle("on", Number(li.dataset.step) === stepN));
          },
        },
      });
      tl.to(tile, { x: () => d.dx * 0.5, y: () => d.arc, scale: .96, rotation: -10, ease: "power1.in" })
        .to(tile, { x: () => d.dx, y: () => d.dy, scale: .60, rotation: 5, ease: "power2.inOut" })
        .to(tile, { scale: .9, rotation: 0, ease: "power3.out" });
      gsap.fromTo("#ts-phone", { boxShadow: "0 40px 70px -28px #000" },
        { boxShadow: "0 40px 90px -20px rgba(255,74,50,.5)", scrollTrigger: { trigger: "#throw", start: "top top", end: "+=160%", scrub: 1 } });
    }

    // gentle parallax on screenshots
    $$(".shot-row .pc img").forEach((img) => {
      gsap.fromTo(img, { y: 26 }, {
        y: -26, ease: "none",
        scrollTrigger: { trigger: img, start: "top bottom", end: "bottom top", scrub: true },
      });
    });
    window.ScrollTrigger.refresh();
  }

  if (document.readyState === "complete") initMotion();
  else addEventListener("load", initMotion);
})();
