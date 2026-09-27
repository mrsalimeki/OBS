#!/usr/bin/env node
// Obscura Web — run the Obscura CDP server from the phone's own browser.
//
//   node server.mjs [uiPort]
//
// - Serves a full interactive UI (Arabic) at http://127.0.0.1:<uiPort>
// - Proxies CDP to `obscura serve` on 127.0.0.1:9222 (SSE out, POST in).
//   The browser never talks to obscura directly: obscura's control plane
//   rejects browser Origin headers, so all traffic is relayed here,
//   server-side, exactly like the upstream tools/live-view.mjs.
// - If the obscura server is not running, tries to start it automatically:
//   `obscura` on PATH, then `proot-distro login ubuntu -- /root/obscura`,
//   then `proot-distro login ubuntu -- /usr/local/bin/obscura`.
//
// Requires Node 21+ (native fetch + WebSocket). On Termux:  pkg install nodejs
// Loopback only (no token) — the phone use case. For remote+token, use the APK.

import http from "node:http";
import { spawn } from "node:child_process";

const uiPort = Number(process.argv[2] ?? 8080);
const cdpHost = "127.0.0.1";
const cdpPort = 9222;
const FAST_MS = 250;   // capture cadence right after a visible change
const IDLE_MS = 1500;  // capture cadence while nothing changes
const MIN_FRAME_BYTES = 100;

// ---------- auto-start obscura if needed ----------

function probe() {
  return fetch(`http://${cdpHost}:${cdpPort}/json/version`)
    .then((r) => (r.ok ? true : false))
    .catch(() => false);
}

function trySpawn(args) {
  try {
    const child = spawn(args[0], args.slice(1), { stdio: "ignore", detached: true });
    child.unref();
    return true;
  } catch {
    return false;
  }
}

async function ensureServe() {
  if (await probe()) return;
  const candidates = [
    ["obscura", "serve", "--port", String(cdpPort)],
    ["proot-distro", "login", "ubuntu", "--", "/root/obscura", "serve", "--port", String(cdpPort)],
    ["proot-distro", "login", "ubuntu", "--", "/usr/local/bin/obscura", "serve", "--port", String(cdpPort)],
  ];
  for (const c of candidates) {
    console.log(`starting: ${c.join(" ")}`);
    if (!trySpawn(c)) continue;
    for (let i = 0; i < 25; i++) {
      await new Promise((r) => setTimeout(r, 1000));
      if (await probe()) return;
    }
  }
  console.error("could not reach obscura serve — start it manually in Termux.");
}

// ---------- obscura WebSocket + relay ----------

let obsWs = null;
let obsClosed = false;
const sseClients = new Set();

function obsConnect() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${cdpHost}:${cdpPort}/devtools/browser`);
    ws.addEventListener("open", () => resolve(ws), { once: true });
    ws.addEventListener("error", () => reject(new Error("cdp connect failed")), { once: true });
    setTimeout(() => reject(new Error("cdp connect timeout")), 10000);
  });
}

function obsSend(text) {
  if (obsWs && obsWs.readyState === WebSocket.OPEN) obsWs.send(text);
}

function broadcast(line) {
  for (const res of sseClients) {
    try {
      if (res.writable) res.write(`data:${line}\n\n`);
    } catch {
      sseClients.delete(res);
    }
  }
}

function watchObs() {
  (async () => {
    while (true) {
      try {
        obsClosed = false;
        obsWs = await obsConnect();
        console.log("connected to obscura CDP");
        obsWs.addEventListener("message", (ev) => broadcast(String(ev.data)));
        obsWs.addEventListener("close", () => {
          obsWs = null;
          if (!obsClosed) console.log("cdp closed; retrying in 2s");
        });
      } catch (e) {
        console.log(`cdp not available (${e.message}); retrying in 2s`);
      }
      if (obsClosed) break;
      await new Promise((r) => setTimeout(r, 2000));
    }
  })();
}

// ---------- HTTP server ----------

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && (req.url === "/" || req.url.startsWith("/?"))) {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(page);
    } else if (req.url === "/favicon.ico") {
      res.writeHead(204);
      res.end();
    } else if (req.url === "/proxy/json/version") {
      const r = await fetch(`http://${cdpHost}:${cdpPort}/json/version`);
      res.writeHead(r.ok ? 200 : 502, { "Content-Type": "application/json" });
      res.end(await r.text());
    } else if (req.url === "/proxy/json/list") {
      const r = await fetch(`http://${cdpHost}:${cdpPort}/json/list`);
      res.writeHead(r.ok ? 200 : 502, { "Content-Type": "application/json" });
      res.end(await r.text());
    } else if (req.method === "GET" && req.url === "/stream") {
      res.writeHead(200, {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-store",
        Connection: "keep-alive",
      });
      res.socket?.setNoDelay?.(true);
      sseClients.add(res);
      req.on("close", () => sseClients.delete(res));
    } else if (req.method === "POST" && req.url === "/send") {
      let body = "";
      req.on("data", (c) => (body += c));
      req.on("end", () => {
        if (!obsWs || obsWs.readyState !== WebSocket.OPEN) {
          res.writeHead(503, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: "cdp not connected" }));
          return;
        }
        obsWs.send(body);
        res.writeHead(202, { "Content-Type": "application/json" });
        res.end("{}");
      });
    } else {
      res.writeHead(404);
      res.end("not found");
    }
  } catch (e) {
    res.writeHead(500, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: String(e.message ?? e) }));
  }
});

server.listen(uiPort, "127.0.0.1", () => {
  console.log(`obscura web: http://127.0.0.1:${uiPort}  (cdp: ${cdpHost}:${cdpPort})`);
  console.log("open that URL in your phone's browser.");
});

ensureServe();
watchObs();
process.on("SIGINT", () => {
  obsClosed = true;
  try { obsWs?.close(); } catch {}
  process.exit(0);
});

// ---------- the UI ----------

const page = `<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>Obscura Web</title>
<style>
:root{--bg:#0E1116;--surface:#151A22;--sv:#232B37;--text:#E6E9EF;--mut:#C3CBD9;--acc:#8B5CF6;--green:#34D399;--red:#F87171}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,Roboto,sans-serif;height:100vh;display:flex;flex-direction:column;overflow:hidden}
.row{display:flex;align-items:center;gap:6px;padding:6px 8px}
button{background:var(--sv);color:var(--text);border:0;border-radius:8px;padding:8px 10px;font-size:14px;cursor:pointer}
button:active{background:#2E3948}
.acc{background:var(--acc);color:#fff}
input[type=text]{flex:1;background:var(--surface);border:1px solid #39414F;color:var(--text);border-radius:8px;padding:9px 10px;font-size:14px;min-width:0}
#frameWrap{flex:1;position:relative;background:#000;min-height:0}
#frame{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;touch-action:none}
#title{position:absolute;top:6px;right:8px;background:var(--sv);color:var(--mut);font-size:11px;padding:3px 8px;border-radius:6px;max-width:60%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#typeBtn{position:absolute;bottom:10px;left:10px;width:46px;height:46px;border-radius:50%;background:rgba(35,43,55,.85);border:1px solid #39414F;font-size:20px;padding:0}
#status{font-size:11px;color:var(--mut);padding:4px 10px 6px}
#status .dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--red);margin-left:4px;vertical-align:middle}
#status.on .dot{background:var(--green)}
#consoleBox{background:var(--surface);border-top:1px solid #232B37;max-height:0;overflow-y:auto;transition:max-height .2s;font-family:monospace;font-size:11px;color:var(--mut);direction:ltr;text-align:left}
#consoleBox.open{max-height:34vh}
#consoleBox pre{margin:0;padding:8px;white-space:pre-wrap;word-break:break-all}
#spin{width:18px;height:18px;border:2px solid #39414F;border-top-color:var(--acc);border-radius:50%;display:none;animation:sp 1s linear infinite}
#spin.on{display:block}
@keyframes sp{to{transform:rotate(360deg)}}
.menu{position:absolute;background:var(--sv);border-radius:10px;overflow:hidden;z-index:9;box-shadow:0 6px 24px #000a}
.menu div{padding:10px 16px;font-size:14px}
.menu div:active{background:#2E3948}
</style>
</head>
<body>
<div class="row">
  <button id="tabsBtn">التبويبات</button>
  <span id="spin"></span>
  <input type="text" id="url" placeholder="https://…" dir="ltr">
  <button class="acc" id="go">فتح</button>
</div>
<div class="row">
  <button id="back">⬅</button>
  <button id="fwd">➡</button>
  <button id="reload">⟳</button>
  <button id="vp">ملء الشاشة</button>
  <span style="flex:1"></span>
  <button id="png">📷</button>
  <button id="pdf">📄</button>
  <button id="conBtn">وحدة التحكم</button>
</div>
<div id="frameWrap">
  <img id="frame" alt="">
  <div id="title"></div>
  <button id="typeBtn" title="اكتب في الصفحة">✏️</button>
</div>
<div id="status"><span class="dot"></span><span id="statusText">جارٍ الاتصال…</span></div>
<div id="consoleBox"><pre id="consolePre"></pre></div>
<script>
"use strict";
const $ = (id) => document.getElementById(id);
const frame = $("frame"), urlIn = $("url"), titleEl = $("title"),
      spin = $("spin"), statusEl = $("status"), statusText = $("statusText"),
      consoleBox = $("consoleBox"), consolePre = $("consolePre");

// ---------- CDP client over the local relay (SSE in, POST out) ----------
let wsUp = false;
let es = null;
let msgId = 0;
const pending = new Map();

function cdp(method, params = {}, sessionId = null, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    if (!wsUp) return reject(new Error("not connected"));
    const id = ++msgId;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(method + " timeout")); }, timeoutMs);
    pending.set(id, { timer, resolve, reject });
    const msg = { id, method };
    if (Object.keys(params).length) msg.params = params;
    if (sessionId) msg.sessionId = sessionId;
    fetch("/send", { method: "POST", body: JSON.stringify(msg) })
      .catch((e) => { clearTimeout(timer); pending.delete(id); reject(e); });
  });
}

function onStreamData(line) {
  let m;
  try { m = JSON.parse(line); } catch { return; }
  if (m.id && pending.has(m.id)) {
    const p = pending.get(m.id);
    pending.delete(m.id);
    clearTimeout(p.timer);
    m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result || {});
    return;
  }
  if (!m.method) return;
  onEvent(m);
}

function connectStream() {
  if (es) es.close();
  es = new EventSource("/stream");
  es.onopen = () => {
    wsUp = true;
    statusEl.classList.add("on");
    statusText.textContent = "متصل";
  };
  es.onerror = () => {
    wsUp = false;
    statusEl.classList.remove("on");
    statusText.textContent = "غير متصل — هل يعمل الخادم؟ (node server.mjs)";
    pending.forEach((p) => { clearTimeout(p.timer); p.reject(new Error("disconnected")); });
    pending.clear();
    es.close();
    setTimeout(connectStream, 2500);
  };
  es.onmessage = (e) => onStreamData(e.data);
}
connectStream();

// ---------- session / target management ----------
let sessionId = null, targetId = null;

async function listPages() {
  const r = await fetch("/proxy/json/list");
  if (!r.ok) throw new Error("json/list failed");
  const arr = await r.json();
  return arr.filter((t) => t.type === "page");
}

function pick(pages) {
  return pages.find((p) => p.url && p.url !== "about:blank" && !p.url.startsWith("data:")) || pages[0];
}

async function ensureAttached() {
  if (sessionId) return;
  let pages = [];
  try { pages = await listPages(); } catch {}
  let t = pick(pages);
  if (!t) {
    const res = await cdp("Target.createTarget", { url: "about:blank" });
    t = { id: res.targetId };
  }
  targetId = t.id;
  try {
    const res = await cdp("Target.attachToTarget", { targetId, flatten: true });
    sessionId = res.sessionId || (targetId + "-session");
  } catch {
    sessionId = targetId + "-session";
  }
  try { await cdp("Page.enable", {}, sessionId); } catch {}
  try { await cdp("Runtime.enable", {}, sessionId); } catch {}
}

function resetSession() { sessionId = null; targetId = null; }

// ---------- page events ----------
let urlFocused = false;
urlIn.addEventListener("focus", () => (urlFocused = true));
urlIn.addEventListener("blur", () => (urlFocused = false));

function logLine(t) {
  const lines = consolePre.textContent.split("\\n");
  lines.push(t);
  consolePre.textContent = lines.slice(-300).join("\\n");
  consoleBox.scrollTop = consoleBox.scrollHeight;
}

function onEvent(m) {
  const p = m.params || {};
  switch (m.method) {
    case "Page.frameNavigated": {
      const f = p.frame || {};
      if (!f.parentId && f.url) {
        if (!urlFocused) urlIn.value = f.url;
        spin.classList.add("on");
      }
      break;
    }
    case "Page.loadEventFired": spin.classList.remove("on"); break;
    case "Page.titleUpdated": if (p.title) titleEl.textContent = p.title; break;
    case "Runtime.consoleAPICalled": {
      const txt = (p.args || []).map((a) => (a.value !== undefined && a.value !== null ? String(a.value) : (a.description || a.type || ""))).join(" ");
      if (txt.includes("Forbidden URL scheme")) break;
      logLine("[" + (p.type || "log") + "] " + txt);
      break;
    }
    case "Runtime.exceptionThrown": {
      const d = p.exceptionDetails || {};
      logLine("[error] " + (d.text || "") + " " + ((d.exception || {}).description || ""));
      break;
    }
  }
}

// ---------- capture loop (fast while changing, idle backoff, boost on touch) ----------
let lastShot = "", boostUntil = 0;
async function captureLoop() {
  let idle = FAST_MS;
  while (true) {
    const boosted = Date.now() < boostUntil;
    try {
      if (!wsUp) { await sleep(800); continue; }
      await ensureAttached();
      const shot = await cdp("Page.captureScreenshot", { format: "jpeg", quality: 70 }, sessionId, 10000);
      const d = shot.data;
      if (d && d.length >= MIN_FRAME_BYTES) {
        if (d !== lastShot) {
          lastShot = d;
          frame.src = "data:image/jpeg;base64," + d;
          idle = FAST_MS;
        } else {
          idle = Math.min(IDLE_MS, Math.round(idle * 1.5));
        }
      }
      await sleep(boosted ? 40 : idle);
    } catch (e) {
      const msg = String(e.message || "");
      if (msg.includes("No target") || msg.toLowerCase().includes("session")) resetSession();
      await sleep(1200);
    }
  }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
captureLoop();

// ---------- navigation ----------
function normalize(raw) {
  const t = raw.trim();
  if (!t) return "about:blank";
  if (/^https?:\\/\\//.test(t) || t.startsWith("about:")) return t;
  if (t.includes(".") && !t.includes(" ")) return "https://" + t;
  return "https://www.google.com/search?q=" + encodeURIComponent(t);
}
async function nav() {
  const u = normalize(urlIn.value);
  urlIn.value = u;
  spin.classList.add("on");
  try { await ensureAttached(); await cdp("Page.navigate", { url: u }, sessionId); }
  catch (e) { logLine("! " + e.message); }
}
$("go").onclick = () => nav();
urlIn.addEventListener("keydown", (e) => { if (e.key === "Enter") nav(); });
$("back").onclick = () => simple("Page.goBack");
$("fwd").onclick = () => simple("Page.goForward");
$("reload").onclick = () => simple("Page.reload");
async function simple(method) {
  spin.classList.add("on");
  try { await ensureAttached(); await cdp(method, {}, sessionId); } catch {}
}

// ---------- viewport ----------
let vpMobile = true, vpDpr = 2;
const vpBtn = $("vp");
function setViewport(w, h, dpr, mobile, label) {
  vpMobile = mobile; vpDpr = dpr; vpBtn.textContent = label;
  (async () => {
    try {
      await ensureAttached();
      await cdp("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: dpr, mobile }, sessionId);
      await cdp("Page.reload", {}, sessionId);
    } catch {}
  })();
}
function fitViewport() {
  const el = $("frameWrap");
  const w = 800;
  const h = Math.round(w / Math.min(5, Math.max(0.2, el.clientWidth / Math.max(1, el.clientHeight))));
  setViewport(w, h, 2, true, "ملء الشاشة");
}
vpBtn.onclick = (e) => {
  const menu = document.createElement("div");
  menu.className = "menu";
  menu.style.right = "8px";
  menu.style.bottom = "54px";
  const items = [
    ["هاتف", () => setViewport(390, 844, 3, true, "هاتف")],
    ["كمبيوتر", () => setViewport(1280, 800, 1, false, "كمبيوتر")],
    ["ملء الشاشة", fitViewport],
  ];
  for (const [label, fn] of items) {
    const d = document.createElement("div");
    d.textContent = label;
    d.onclick = () => { menu.remove(); fn(); };
    menu.appendChild(d);
  }
  document.body.appendChild(menu);
  setTimeout(() => document.addEventListener("click", function rm() { menu.remove(); document.removeEventListener("click", rm); }), 50);
};

// ---------- touch on the frame -> page input ----------
let downX = 0, downY = 0, downT = 0, isMoving = false, path = [];
function toCss(x, y) {
  const natW = frame.naturalWidth, natH = frame.naturalHeight;
  const wrap = $("frameWrap").getBoundingClientRect();
  if (!natW || !natH || !wrap.width || !wrap.height) return null;
  const s = Math.min(wrap.width / natW, wrap.height / natH);
  const offX = (wrap.width - natW * s) / 2, offY = (wrap.height - natH * s) / 2;
  return [ (x - offX) / s / vpDpr, (y - offY) / s / vpDpr ];
}
function touchPoints(pts) { return pts.map(([x, y]) => ({ x, y })); }
async function pageTap(x, y) {
  try {
    if (vpMobile) {
      await cdp("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: touchPoints([[x, y]]) }, sessionId, 5000);
      await cdp("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] }, sessionId, 5000);
    } else {
      await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", x, y }, sessionId, 5000);
      await cdp("Input.dispatchMouseEvent", { type: "mousePressed", x, y, button: "left", clickCount: 1 }, sessionId, 5000);
      await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x, y, button: "left", clickCount: 1 }, sessionId, 5000);
    }
  } catch {}
}
async function pageSwipe(path) {
  if (path.length < 2) return;
  try {
    if (vpMobile) {
      await cdp("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: touchPoints([path[0]]) }, sessionId, 5000);
      for (let i = 1; i < path.length; i++) {
        await cdp("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: touchPoints([path[i]]) }, sessionId, 5000);
        await sleep(12);
      }
      await cdp("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] }, sessionId, 5000);
    } else {
      const dy = Math.max(-1200, Math.min(1200, Math.round((path[0][1] - path[path.length - 1][1]) / 8)));
      await cdp("Input.dispatchMouseEvent", { type: "mouseWheel", x: path[0][0], y: path[0][1], deltaX: 0, deltaY: dy }, sessionId, 5000);
    }
  } catch {}
}
frame.addEventListener("pointerdown", (e) => {
  e.preventDefault();
  boostUntil = Date.now() + 2000;
  downX = e.clientX; downY = e.clientY; downT = Date.now();
  isMoving = false; path = [[downX, downY]];
  frame.setPointerCapture(e.pointerId);
});
frame.addEventListener("pointermove", (e) => {
  if (!path.length) return;
  if (!isMoving && (Math.abs(e.clientX - downX) > 12 || Math.abs(e.clientY - downY) > 12)) isMoving = true;
  if (isMoving) {
    const last = path[path.length - 1];
    if (Math.abs(e.clientX - last[0]) > 2 || Math.abs(e.clientY - last[1]) > 2) path.push([e.clientX, e.clientY]);
  }
});
frame.addEventListener("pointerup", (e) => {
  if (!path.length) return;
  const css = (x, y) => toCss(x, y);
  if (isMoving) {
    path.push([e.clientX, e.clientY]);
    const cp = path.map(css).filter(Boolean);
    if (cp.length >= 2) pageSwipe(cp);
  } else if (Date.now() - downT < 600) {
    const p = css(e.clientX, e.clientY);
    if (p) pageTap(p[0], p[1]);
  }
  path = [];
});

// ---------- type text ----------
$("typeBtn").onclick = () => {
  const t = prompt("انقر أولًا على حقل النص في الصفحة، ثم اكتب هنا:\\n(سيُضغط Enter في النهاية)");
  if (t === null) return;
  (async () => {
    try {
      await ensureAttached();
      await cdp("Input.insertText", { text: t }, sessionId, 5000);
      await sleep(120);
      await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Enter", code: "Enter", windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13, keyCode: 13 }, sessionId, 5000);
      await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Enter", code: "Enter", windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 }, sessionId, 5000);
    } catch (e) { logLine("! " + e.message); }
  })();
};

// ---------- export ----------
$("png").onclick = () => dl("Page.captureScreenshot", { format: "png" }, "obscura-screenshot.png", "image/png");
$("pdf").onclick = () => dl("Page.printToPDF", { printBackground: true }, "obscura-page.pdf", "application/pdf", 60000);
async function dl(method, params, name, mime, timeout = 30000) {
  try {
    const res = await cdp(method, params, sessionId, timeout);
    const bytes = Uint8Array.from(atob(res.data), (c) => c.charCodeAt(0));
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([bytes], { type: mime }));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  } catch (e) { logLine("! " + e.message); }
}

// ---------- tabs (v1: new tab) ----------
$("tabsBtn").onclick = () => {
  (async () => {
    try {
      const res = await cdp("Target.createTarget", { url: "about:blank" });
      targetId = res.targetId; sessionId = null;
      await ensureAttached();
      logLine("+ new tab: " + targetId);
    } catch (e) { logLine("! " + e.message); }
  })();
};

// ---------- console drawer ----------
$("conBtn").onclick = () => consoleBox.classList.toggle("open");

// initial viewport: fit the screen
setTimeout(fitViewport, 1500);
</script>
</body>
</html>`;
