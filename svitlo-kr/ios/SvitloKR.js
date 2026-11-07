// Variables used by Scriptable.
// These must be at the very top of the file. Do not edit.
// icon-color: deep-blue; icon-glyph: bolt;

/*
 * Світло Кривий Ріг — віджет графіка відключень для iPhone (Scriptable).
 *
 * Як встановити:
 *   1. Встановіть безкоштовний застосунок «Scriptable» з App Store.
 *   2. Створіть новий скрипт, вставте цей файл повністю, назвіть «Світло КР».
 *   3. Запустіть скрипт → «Змінити адресу» → оберіть вулицю й будинок.
 *   4. Додайте на робочий стіл віджет Scriptable (малий або середній),
 *      утримуйте його → «Редагувати віджет» → Script: «Світло КР».
 *
 * Дані:
 *   • черга за адресою — з сайту ДТЕК Дніпровські електромережі (dtek-dnem.com.ua);
 *   • графік — з відкритого репозиторію github.com/Baskerville42/outage-data-ua,
 *     який щохвилини копіює графіки ДТЕК у JSON.
 */

const CITY_RE = /кривий\s*р[іi]г/i
const CITY_FALLBACK = "м. Кривий Ріг"
const DTEK_URL = "https://www.dtek-dnem.com.ua/ua/shutdowns"
const DATA_URL = "https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/dnipro.json"
const CACHE_MAX_AGE_MS = 10 * 60 * 1000
const SLOT_MS = 30 * 60 * 1000

const C = {
  bg: new Color("#1f2a44"),
  bgOff: new Color("#2a2438"),
  text: Color.white(),
  sub: new Color("#ffffff", 0.65),
  on: new Color("#4cd964"),
  off: new Color("#ff5a5f"),
  maybe: new Color("#ffb340"),
  unknown: new Color("#ffffff", 0.18),
}

// ───────────────────────────── storage ─────────────────────────────

const fm = FileManager.local()
const dir = fm.joinPath(fm.documentsDirectory(), "svitlo-kr")
if (!fm.fileExists(dir)) fm.createDirectory(dir, true)
const SETTINGS_FILE = fm.joinPath(dir, "settings.json")
const CACHE_FILE = fm.joinPath(dir, "dnipro.json")

function readJSON(path) {
  try {
    return fm.fileExists(path) ? JSON.parse(fm.readString(path)) : null
  } catch (e) {
    return null
  }
}
const loadSettings = () => readJSON(SETTINGS_FILE) || {}
const saveSettings = (s) => fm.writeString(SETTINGS_FILE, JSON.stringify(s))

async function loadData(force) {
  const cached = readJSON(CACHE_FILE)
  const age = fm.fileExists(CACHE_FILE) ? Date.now() - fm.modificationDate(CACHE_FILE).getTime() : Infinity
  if (!force && cached && age < CACHE_MAX_AGE_MS) return { data: cached, stale: false }
  try {
    const req = new Request(DATA_URL)
    req.timeoutInterval = 20
    const text = await req.loadString()
    const data = JSON.parse(text)
    if (!data.fact && !data.preset) throw new Error("bad data")
    fm.writeString(CACHE_FILE, text)
    return { data, stale: false }
  } catch (e) {
    if (cached) return { data: cached, stale: true }
    throw e
  }
}

// ───────────────────────────── schedule logic ─────────────────────────────

// Kyiv time helpers (the schedule is always in Europe/Kyiv).
const KYIV_FMT = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Europe/Kyiv", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
})
function kyivParts(ms) {
  const p = {}
  for (const x of KYIV_FMT.formatToParts(new Date(ms))) p[x.type] = +x.value
  return p
}
function kyivMidnight(ms) {
  const p = kyivParts(ms)
  const wallAsUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second)
  const offset = wallAsUtc - Math.floor(ms / 1000) * 1000
  return Date.UTC(p.year, p.month - 1, p.day) - offset
}
function hhmm(ms) {
  const p = kyivParts(ms)
  return String(p.hour).padStart(2, "0") + ":" + String(p.minute).padStart(2, "0")
}
// DTEK weekday: 1 = Monday … 7 = Sunday
function dtekWeekday(ms) {
  const p = kyivParts(ms)
  const d = new Date(Date.UTC(p.year, p.month - 1, p.day)).getUTCDay()
  return d === 0 ? 7 : d
}

const HALF = {
  yes: ["on", "on"], no: ["off", "off"], maybe: ["maybe", "maybe"],
  first: ["off", "on"], second: ["on", "off"],
  mfirst: ["maybe", "on"], msecond: ["on", "maybe"],
}

// Returns 48 half-hour states for one day, plus where they came from.
function daySlots(data, group, dayStart) {
  const fact = data.fact && data.fact.data && data.fact.data[String(Math.round(dayStart / 1000))]
  let src = null, source = "none"
  if (fact && fact[group]) { src = fact[group]; source = "fact" }
  else {
    const pre = data.preset && data.preset.data && data.preset.data[group]
    const wd = pre && pre[String(dtekWeekday(dayStart + 12 * 3600e3))]
    if (wd) { src = wd; source = "preset" }
  }
  const slots = []
  for (let h = 1; h <= 24; h++) {
    const v = src ? HALF[src[String(h)]] : null
    slots.push(...(v || ["unknown", "unknown"]))
  }
  return { start: dayStart, slots, source }
}

function buildDays(data, group, now) {
  const today = kyivMidnight(now)
  const tomorrow = kyivMidnight(today + 36 * 3600e3)
  const dayAfter = kyivMidnight(tomorrow + 36 * 3600e3)
  return [today, tomorrow, dayAfter].map((d) => daySlots(data, group, d))
}

// Merge consecutive equal slots into intervals [{state, start, end}].
function intervals(day) {
  const out = []
  day.slots.forEach((s, i) => {
    const start = day.start + i * SLOT_MS
    const last = out[out.length - 1]
    if (last && last.state === s) last.end = start + SLOT_MS
    else out.push({ state: s, start, end: start + SLOT_MS })
  })
  return out
}

function timeline(days) {
  const all = []
  for (const d of days) for (const iv of intervals(d)) {
    const last = all[all.length - 1]
    if (last && last.state === iv.state && last.end === iv.start) last.end = iv.end
    else all.push({ ...iv })
  }
  return all
}

// Main status used by widget: { state, title, subtitle, until }
function computeStatus(data, group, now) {
  const days = buildDays(data, group, now)
  const tl = timeline(days)
  const cur = tl.find((iv) => iv.start <= now && now < iv.end)
  const knownEnd = (iv) => {
    const next = tl[tl.indexOf(iv) + 1]
    return next && next.state !== "unknown" ? iv.end : null
  }
  const nextOf = (pred) => tl.find((iv) => iv.start > now && pred(iv.state))
  const today = kyivMidnight(now)
  // end of an interval: exact midnight after today reads as "24:00"
  const fmtUntil = (ms) => (ms === today + 48 * SLOT_MS ? "24:00" : fmtStart(ms))
  // start of an interval: anything after today is prefixed with "завтра"
  const fmtStart = (ms) => (kyivMidnight(ms) !== today ? "завтра " + hhmm(ms) : hhmm(ms))
  const res = { state: cur ? cur.state : "unknown", title: "", subtitle: "", until: null, days, source: days[0].source }

  if (!cur || cur.state === "unknown") {
    res.title = "Графік недоступний"
    res.subtitle = "Перевірте чергу"
    return res
  }
  const end = knownEnd(cur)
  res.until = end
  if (cur.state === "off") {
    res.title = end ? "Немає світла до " + fmtUntil(end) : "Немає світла"
    const n = nextOf((s) => s === "off")
    if (n) res.subtitle = "Далі: " + fmtStart(n.start) + "–" + fmtUntil(n.end)
  } else if (cur.state === "maybe") {
    res.title = end ? "Можливе відключення до " + fmtUntil(end) : "Можливе відключення"
  } else {
    const n = nextOf((s) => s === "off" || s === "maybe")
    if (!n || n.state === "unknown") {
      res.title = "Світло є"
      res.subtitle = "Відключень не заплановано"
    } else if (n.state === "off") {
      res.title = "Світло є до " + fmtStart(n.start)
      res.subtitle = "Відключення " + fmtStart(n.start) + "–" + fmtUntil(n.end)
      res.until = n.start
    } else {
      res.title = "Світло є"
      res.subtitle = "Можливе відкл. " + fmtStart(n.start).replace(/^(завтра )?/, (m) => m + "з ")
      res.until = n.start
    }
  }
  return res
}

// Exported for tests in Node; no-op inside Scriptable.
if (typeof module !== "undefined" && module.exports && typeof Script === "undefined") {
  module.exports = { computeStatus, buildDays, intervals, kyivMidnight, hhmm, dtekWeekday }
}

// ───────────────────────────── DTEK address lookup ─────────────────────────────

// JS executed inside the DTEK page. It uses the site's own jQuery/CSRF setup,
// so the request looks exactly like the one made by the address form.
function dtekAjaxJS(params) {
  return `
(function(){
  var params = ${JSON.stringify(params)};
  function done(x){ completion(x) }
  function csrf(){
    var m = document.querySelector('meta[name="csrf-token"]');
    var p = document.querySelector('meta[name="csrf-param"]');
    return { token: m ? m.content : null, param: p ? p.content : null };
  }
  function ajaxUrl(){
    var m = document.querySelector('meta[name="ajaxUrl"]');
    return (m && m.content) || '/ua/ajax';
  }
  var tries = 0;
  (function wait(){
    if (!window.jQuery && !document.querySelector('meta[name="csrf-token"]')) {
      if (++tries > 40) return done({ error: 'page' });
      return setTimeout(wait, 500);
    }
    var c = csrf();
    var body = new URLSearchParams();
    Object.keys(params).forEach(function(k){ body.append(k, params[k]); });
    if (c.param && c.token && !body.has(c.param)) body.append(c.param, c.token);
    var headers = { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'X-Requested-With': 'XMLHttpRequest',
                    'Accept': 'application/json, text/javascript, */*; q=0.01' };
    if (c.token) headers['X-CSRF-Token'] = c.token;
    fetch(ajaxUrl(), { method: 'POST', credentials: 'include', headers: headers, body: body.toString() })
      .then(function(r){ return r.text(); })
      .then(function(t){ try { done({ ok: JSON.parse(t) }); } catch(e) { done({ error: 'json', text: t.slice(0, 300) }); } })
      .catch(function(e){ done({ error: String(e) }); });
  })();
})();`
}

async function dtekCall(wv, params) {
  const r = await wv.evaluateJavaScript(dtekAjaxJS(params), true)
  if (r && r.ok) return r.ok
  throw new Error(r && r.error ? r.error : "unknown")
}

async function openDtek() {
  const wv = new WebView()
  await wv.loadURL(DTEK_URL)
  // Some phones get an anti-bot check first. Wait until the real page is loaded.
  for (let i = 0; i < 20; i++) {
    const ok = await wv.evaluateJavaScript("!!document.querySelector('meta[name=\"csrf-token\"]') || !!window.DisconSchedule", false)
    if (ok) return wv
    await new Promise((r) => Timer.schedule(1000, false, r))
  }
  // Let the user pass the check manually, then continue.
  const a = new Alert()
  a.title = "Сайт ДТЕК просить перевірку"
  a.message = "Зараз відкриється сторінка ДТЕК. Якщо там є перевірка «я не робот» — пройдіть її і закрийте сторінку."
  a.addAction("Добре")
  await a.present()
  await wv.present(true)
  return wv
}

function findCityKey(map) {
  return Object.keys(map).find((k) => CITY_RE.test(k)) || null
}

async function getStreets(wv) {
  // 1) data already present on the page
  const onPage = await wv.evaluateJavaScript(
    "(function(){try{var s=window.DisconSchedule&&DisconSchedule.streets;return s?JSON.stringify(s):null}catch(e){return null}})()", false)
  let map = onPage ? JSON.parse(onPage) : null
  // 2) explicit ajax request
  if (!map || (Array.isArray(map) && !map.length) || (!Array.isArray(map) && !findCityKey(map))) {
    const r = await dtekCall(wv, { method: "getStreets" })
    map = r.streets || r.data || r
  }
  if (Array.isArray(map)) return { city: CITY_FALLBACK, streets: map }
  const city = findCityKey(map)
  if (!city) throw new Error("Кривий Ріг не знайдено в довіднику ДТЕК")
  return { city, streets: map[city] }
}

async function getHouses(wv, city, street) {
  const r = await dtekCall(wv, {
    method: "getHomeNum",
    "data[0][name]": "city", "data[0][value]": city,
    "data[1][name]": "street", "data[1][value]": street,
  })
  return r.data || {}
}

const norm = (s) => String(s).toLowerCase().replace(/[’'`ʼ]/g, "").replace(/\s+/g, " ").trim()

async function pickFromList(title, items) {
  const t = new UITable()
  t.showSeparators = true
  let picked = null
  const head = new UITableRow()
  head.isHeader = true
  head.addText(title)
  t.addRow(head)
  for (const it of items) {
    const row = new UITableRow()
    row.addText(it)
    row.dismissOnSelect = true
    row.onSelect = () => { picked = it }
    t.addRow(row)
  }
  await t.present(false)
  return picked
}

async function askText(title, message, placeholder, value) {
  const a = new Alert()
  a.title = title
  a.message = message || ""
  a.addTextField(placeholder || "", value || "")
  a.addAction("Далі")
  a.addCancelAction("Скасувати")
  const i = await a.present()
  return i === -1 ? null : a.textFieldValue(0).trim()
}

async function chooseAddress() {
  let wv, cityData
  try {
    wv = await openDtek()
    cityData = await getStreets(wv)
  } catch (e) {
    return await failLookup("Не вдалося отримати список вулиць: " + e.message)
  }
  while (true) {
    const q = await askText("Вулиця", "Введіть частину назви вулиці (напр. «Ушинського»)", "Назва вулиці")
    if (q === null) return null
    const nq = norm(q)
    const found = cityData.streets.filter((s) => norm(s).includes(nq)).slice(0, 80)
    if (!found.length) {
      const a = new Alert()
      a.title = "Нічого не знайдено"
      a.message = "Спробуйте коротший запит або стару/нову назву вулиці."
      a.addAction("Ще раз")
      await a.present()
      continue
    }
    const street = found.length === 1 ? found[0] : await pickFromList("Оберіть вулицю", found)
    if (!street) continue
    let houses
    try {
      houses = await getHouses(wv, cityData.city, street)
    } catch (e) {
      return await failLookup("ДТЕК не відповів: " + e.message)
    }
    const nums = Object.keys(houses)
    if (!nums.length) return await failLookup("Для цієї вулиці ДТЕК не повернув будинків.")
    const h = await askText("Будинок", street, "Номер будинку")
    if (h === null) continue
    const nh = norm(h)
    let match = nums.filter((n) => norm(n) === nh)
    if (!match.length) match = nums.filter((n) => norm(n).startsWith(nh))
    const house = match.length === 1 ? match[0] : await pickFromList("Оберіть будинок", match.length ? match : nums)
    if (!house) continue
    const groups = (houses[house] && houses[house].sub_type_reason) || []
    if (!groups.length) return await failLookup("Для цього будинку черга не вказана (можливо, він не відключається за графіком).")
    const s = loadSettings()
    s.street = street
    s.house = house
    s.group = groups[0]
    s.groupName = groupLabel(groups[0])
    saveSettings(s)
    return s
  }
}

async function failLookup(msg) {
  const a = new Alert()
  a.title = "Не вдалося визначити чергу"
  a.message = msg + "\n\nМожна обрати чергу вручну (її видно на сайті ДТЕК або в рахунку)."
  a.addAction("Обрати чергу вручну")
  a.addCancelAction("Скасувати")
  return (await a.present()) === 0 ? chooseGroupManually() : null
}

function groupLabel(g) {
  const m = /^GPV(\d+)\.(\d+)$/.exec(g || "")
  return m ? "Черга " + m[1] + "." + m[2] : g || "—"
}

async function chooseGroupManually() {
  let groups = []
  try {
    const { data } = await loadData(false)
    const today = data.fact && data.fact.data && Object.values(data.fact.data)[0]
    groups = Object.keys((today) || (data.preset && data.preset.data) || {})
  } catch (e) {}
  groups = groups.filter((g) => /^GPV\d\.\d$/.test(g)).sort()
  if (!groups.length) groups = ["1.1","1.2","2.1","2.2","3.1","3.2","4.1","4.2","5.1","5.2","6.1","6.2"].map((x) => "GPV" + x)
  const label = await pickFromList("Оберіть чергу", groups.map(groupLabel))
  if (!label) return null
  const g = groups[groups.map(groupLabel).indexOf(label)]
  const s = loadSettings()
  s.group = g
  s.groupName = label
  if (!s.street) {
    const addr = await askText("Підпис", "Адреса для віджета (необов'язково)", "вул. …, 1", "")
    if (addr) { s.street = addr; s.house = "" }
  }
  saveSettings(s)
  return s
}

// ───────────────────────────── drawing ─────────────────────────────

function stateColor(s) {
  return s === "on" ? C.on : s === "off" ? C.off : s === "maybe" ? C.maybe : C.unknown
}

// A 24h bar for one day with a "now" marker.
function dayBar(day, width, height, now) {
  const dc = new DrawContext()
  dc.size = new Size(width, height)
  dc.opaque = false
  dc.respectScreenScale = true
  const w = width / 48
  day.slots.forEach((s, i) => {
    dc.setFillColor(stateColor(s))
    const r = new Rect(i * w, 0, w + 0.5, height)
    dc.fillRect(r)
  })
  if (now >= day.start && now < day.start + 48 * SLOT_MS) {
    const x = ((now - day.start) / (48 * SLOT_MS)) * width
    dc.setFillColor(Color.white())
    dc.fillRect(new Rect(x - 1, 0, 2, height))
  }
  return dc.getImage()
}

function addressLine(s) {
  return [s.street, s.house].filter(Boolean).join(", ") || s.groupName || ""
}

function buildWidget(status, settings, family, stale) {
  const w = new ListWidget()
  const off = status.state === "off"
  const g = new LinearGradient()
  g.colors = off ? [new Color("#3a2a44"), new Color("#24203a")] : [new Color("#26345a"), new Color("#1b2440")]
  g.locations = [0, 1]
  w.backgroundGradient = g
  w.setPadding(14, 14, 12, 14)
  if (status.until) w.refreshAfterDate = new Date(Math.min(status.until + 30e3, Date.now() + 15 * 60e3))
  else w.refreshAfterDate = new Date(Date.now() + 15 * 60e3)
  w.url = URLScheme.forRunningScript()

  const top = w.addStack()
  top.centerAlignContent()
  const sym = SFSymbol.named(off ? "bolt.slash.circle" : status.state === "maybe" ? "exclamationmark.circle" : "bolt.circle")
  sym.applyFont(Font.systemFont(26))
  const img = top.addImage(sym.image)
  img.imageSize = new Size(30, 30)
  img.tintColor = off ? C.off : status.state === "maybe" ? C.maybe : status.state === "on" ? C.on : C.sub
  top.addSpacer()
  const tag = top.addText(settings.groupName ? settings.groupName.replace("Черга ", "") : "")
  tag.font = Font.boldSystemFont(13)
  tag.textColor = C.sub

  w.addSpacer()

  const title = w.addText(status.title)
  title.font = Font.boldSystemFont(family === "small" ? 19 : 21)
  title.textColor = C.text
  title.minimumScaleFactor = 0.6
  title.lineLimit = 2

  if (family !== "small" && status.subtitle) {
    const sub = w.addText(status.subtitle)
    sub.font = Font.mediumSystemFont(13)
    sub.textColor = C.sub
    sub.lineLimit = 1
  }

  if (family === "medium" || family === "large") {
    w.addSpacer(6)
    const bar = w.addImage(dayBar(status.days[0], 300, 8, Date.now()))
    bar.imageSize = new Size(300, 8)
    bar.cornerRadius = 4
    const lbl = w.addStack()
    for (const t of ["00", "06", "12", "18", "24"]) {
      const x = lbl.addText(t)
      x.font = Font.systemFont(9)
      x.textColor = C.sub
      if (t !== "24") lbl.addSpacer()
    }
  }

  w.addSpacer(4)
  const addr = w.addText(addressLine(settings) + (stale ? " • офлайн" : ""))
  addr.font = Font.systemFont(12)
  addr.textColor = C.sub
  addr.lineLimit = 1
  addr.minimumScaleFactor = 0.7
  return w
}

function buildAccessory(status, family) {
  const w = new ListWidget()
  if (status.until) w.refreshAfterDate = new Date(Math.min(status.until + 30e3, Date.now() + 15 * 60e3))
  if (family === "accessoryInline") {
    w.addText((status.state === "off" ? "⚡️✕ " : "⚡️ ") + status.title)
    return w
  }
  if (family === "accessoryCircular") {
    const sym = SFSymbol.named(status.state === "off" ? "bolt.slash.fill" : "bolt.fill")
    const i = w.addImage(sym.image)
    i.imageSize = new Size(18, 18)
    i.centerAlignImage()
    const t = w.addText(status.until ? hhmm(status.until) : "—")
    t.font = Font.boldSystemFont(12)
    t.centerAlignText()
    return w
  }
  const t = w.addText(status.title)
  t.font = Font.boldSystemFont(14)
  t.minimumScaleFactor = 0.7
  t.lineLimit = 2
  if (status.subtitle) {
    const s = w.addText(status.subtitle)
    s.font = Font.systemFont(12)
    s.lineLimit = 1
  }
  return w
}

function messageWidget(text) {
  const w = new ListWidget()
  w.backgroundColor = C.bg
  w.url = URLScheme.forRunningScript()
  const t = w.addText(text)
  t.font = Font.boldSystemFont(15)
  t.textColor = C.text
  return w
}

// ───────────────────────────── in-app schedule view ─────────────────────────────

const STATE_TEXT = { on: "Світло є", off: "Світла немає", maybe: "Можливе відключення", unknown: "Немає даних" }

function scheduleHTML(status, settings, data, stale) {
  const names = ["Сьогодні", "Завтра"]
  const days = status.days.slice(0, 2).map((d, i) => {
    const cells = d.slots.map((s) => `<i class="${s}"></i>`).join("")
    const list = intervals(d).filter((iv) => iv.state !== "on")
      .map((iv) => `<li class="${iv.state}"><b>${hhmm(iv.start)}–${iv.end === d.start + 48 * SLOT_MS ? "24:00" : hhmm(iv.end)}</b> ${STATE_TEXT[iv.state]}</li>`).join("")
    const note = d.source === "fact" ? "" : d.source === "preset" ? "<p class=note>Точний графік ще не опубліковано — показано можливі відключення.</p>" : "<p class=note>Немає даних.</p>"
    return `<section><h2>${names[i]}</h2><div class=bar>${cells}</div>
      <div class=ticks><span>00</span><span>06</span><span>12</span><span>18</span><span>24</span></div>
      ${note}<ul>${list || (d.source === "fact" ? "<li class=on>Відключень немає</li>" : "")}</ul></section>`
  }).join("")
  const upd = (data.fact && data.fact.update) || ""
  return `<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{color-scheme:dark}body{font:16px -apple-system,system-ui;background:#141a2e;color:#fff;margin:0;padding:20px}
.card{background:${status.state === "off" ? "#3a2a44" : "#26345a"};border-radius:22px;padding:20px;margin-bottom:22px}
.card h1{font-size:26px;margin:6px 0}.muted{opacity:.65;font-size:14px}
h2{font-size:18px;margin:0 0 10px}.bar{display:flex;height:22px;border-radius:7px;overflow:hidden}
.bar i{flex:1}.on{--c:#4cd964}.off{--c:#ff5a5f}.maybe{--c:#ffb340}.unknown{--c:#444a5e}.bar i{background:var(--c)}
.ticks{display:flex;justify-content:space-between;font-size:11px;opacity:.6;margin-top:4px}
ul{list-style:none;padding:0;margin:12px 0 0}li{padding:9px 0;border-bottom:1px solid #ffffff14}
li:before{content:"";display:inline-block;width:10px;height:10px;border-radius:5px;background:var(--c);margin-right:10px}
section{margin-bottom:26px}.note{font-size:13px;opacity:.65}
</style>
<div class=card><div class=muted>${settings.groupName || ""}</div><h1>${status.title}</h1>
<div class=muted>${status.subtitle || ""}</div><div class=muted style="margin-top:10px">${addressLine(settings)}</div></div>
${days}
<p class=note>ДТЕК оновив: ${upd}${stale ? " (немає інтернету, показано збережені дані)" : ""}</p>`
}

// ───────────────────────────── main ─────────────────────────────

async function main() {
  let settings = loadSettings()

  if (config.runsInWidget || config.runsInAccessoryWidget) {
    if (!settings.group) return Script.setWidget(messageWidget("Відкрийте скрипт і оберіть адресу"))
    try {
      const { data, stale } = await loadData(false)
      const status = computeStatus(data, settings.group, Date.now())
      const fam = config.widgetFamily || "small"
      Script.setWidget(fam.startsWith("accessory") ? buildAccessory(status, fam) : buildWidget(status, settings, fam, stale))
    } catch (e) {
      Script.setWidget(messageWidget("Немає даних. Перевірте інтернет."))
    }
    return Script.complete()
  }

  if (!settings.group) {
    settings = (await chooseAddress()) || loadSettings()
    if (!settings.group) return Script.complete()
  }

  while (true) {
    const a = new Alert()
    a.title = "Світло • Кривий Ріг"
    a.message = [addressLine(settings), settings.groupName].filter(Boolean).join("\n")
    a.addAction("Показати графік")
    a.addAction("Змінити адресу")
    a.addAction("Обрати чергу вручну")
    a.addAction("Перегляд віджета")
    a.addCancelAction("Закрити")
    const i = await a.present()
    if (i === -1) break
    if (i === 0) {
      try {
        const { data, stale } = await loadData(true)
        const status = computeStatus(data, settings.group, Date.now())
        const wv = new WebView()
        await wv.loadHTML(scheduleHTML(status, settings, data, stale))
        await wv.present(true)
      } catch (e) {
        const er = new Alert()
        er.title = "Немає даних"
        er.message = "Перевірте інтернет і спробуйте ще раз."
        er.addAction("OK")
        await er.present()
      }
    } else if (i === 1) {
      settings = (await chooseAddress()) || settings
    } else if (i === 2) {
      settings = (await chooseGroupManually()) || settings
    } else if (i === 3) {
      const { data, stale } = await loadData(false)
      const status = computeStatus(data, settings.group, Date.now())
      await buildWidget(status, settings, "medium", stale).presentMedium()
    }
  }
  Script.complete()
}

if (typeof Script !== "undefined") await main()
