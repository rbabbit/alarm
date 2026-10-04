import { AlarmAudio } from "./audio.mjs";
import { DAY_CODES, DAY_LABELS, JS_DAY_CODES, createAlarm, formatDays, formatTime24, previewOccurrences, validateAlarm } from "../src/core/alarm-model.mjs";

const ALARMS_KEY = "timer-app.alarms.v2";
const HISTORY_KEY = "timer-app.history.v1";
const QUICK_TIMERS_KEY = "timer-app.quick-timers.v1";
const COUNTERS_KEY = "timer-app.counters.v1";
const WEATHER_KEY = "timer-app.weather.v3";
const OPEN_METEO_ENDPOINT = "https://api.open-meteo.com/v1/forecast";
const MAX_QUICK_TIMERS = 10;
const MAX_CUSTOM_MINUTES = 30 * 24 * 60;
const MAX_HISTORY = 100;
const alarmAudio = new AlarmAudio();

let alarms = loadJson(ALARMS_KEY, []).map((alarm) => createAlarm({ ...alarm, alarmType: "auto" }));
let history = loadJson(HISTORY_KEY, []).slice(-MAX_HISTORY);
saveJson(HISTORY_KEY, history);
let quickTimers = loadJson(QUICK_TIMERS_KEY, []).map((timer) => ({ ...timer, audioStarted: false }));
const storedCounters = loadJson(COUNTERS_KEY, null);
let counters = Array.isArray(storedCounters)
  ? storedCounters.map(normalizeCounter).filter(Boolean).slice(0, 20)
  : [createCounter("Counter 1")];
saveJson(COUNTERS_KEY, counters);
let weatherState = loadJson(WEATHER_KEY, { latitude: null, longitude: null, timezone: "", current: null, hourly: null, daily: null, receivedAt: null });
let currentView = "list";
let editingId = null;
let draft = null;
let ringing = null;
let ringStopTimer = null;
const nextEvents = new Map();
const snoozeEvents = new Map();
const quickAudio = new Map();

const screens = {
  list: document.querySelector("#list-screen"),
  edit: document.querySelector("#edit-screen"),
  quick: document.querySelector("#quick-screen"),
  counter: document.querySelector("#counter-screen"),
  info: document.querySelector("#info-screen")
};

function loadJson(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
}

function saveJson(key, value) { localStorage.setItem(key, JSON.stringify(value)); }

function createCounter(name) {
  return { id: `counter-${crypto.randomUUID()}`, name, value: 0, step: 1, updatedAt: Date.now() };
}

function normalizeCounter(counter) {
  if (!counter || typeof counter !== "object") return null;
  const value = Number.isFinite(Number(counter.value)) ? Math.trunc(Number(counter.value)) : 0;
  const step = Number.isFinite(Number(counter.step)) ? Math.max(1, Math.trunc(Number(counter.step))) : 1;
  return {
    id: String(counter.id || `counter-${crypto.randomUUID()}`),
    name: String(counter.name || "Counter").trim() || "Counter",
    value,
    step,
    updatedAt: Number(counter.updatedAt) || Date.now()
  };
}

function saveCounters() {
  saveJson(COUNTERS_KEY, counters);
}

function nativeBridge() {
  return window.AndroidAlarmBridge && typeof window.AndroidAlarmBridge.getState === "function"
    ? window.AndroidAlarmBridge
    : null;
}

function isNativeAndroid() {
  return nativeBridge() !== null;
}

function nativeControlsBridge() {
  return isNativeAndroid() && window.AndroidNativeControls
    && typeof window.AndroidNativeControls.syncNativeControls === "function"
    ? window.AndroidNativeControls
    : null;
}

function nativeControlKey(element) {
  if (element.dataset.nativeKey) return element.dataset.nativeKey;
  let key = "";
  if (element.dataset.view) key = `nav:${element.dataset.view}`;
  else if (element.dataset.timeToggle !== undefined) key = `time:${element.closest("[data-time-picker]")?.dataset.timePicker || element.id}`;
  else if (element.dataset.frequency) key = `frequency:${element.dataset.frequency}`;
  else if (element.dataset.day) key = `repeat-day:${element.dataset.day}`;
  else if (element.dataset.inlineDay) key = `inline-day:${element.closest("[data-edit]")?.dataset.edit || ""}:${element.dataset.inlineDay}`;
  else if (element.dataset.advanced) key = `advanced:${element.dataset.advanced}`;
  else if (element.dataset.quickAction) key = `quick-action:${element.dataset.quickId}:${element.dataset.quickAction}`;
  else if (element.dataset.counterAction) key = `counter:${element.closest("[data-counter-id]")?.dataset.counterId || ""}:${element.dataset.counterAction}`;
  else if (element.id) key = `id:${element.id}`;
  if (!key) return "";
  element.dataset.nativeKey = key;
  return key;
}

function nativeControlDescriptor(element) {
  const rect = element.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return null;
  const key = nativeControlKey(element);
  if (!key || element.matches(".time-option")) return null;
  const isSwitch = element.matches('input[type="checkbox"][role="switch"]');
  const isTime = element.matches("[data-time-toggle], input[type=\"time\"]");
  const type = isSwitch ? "switch" : isTime ? "time" : element.matches("select") ? "select" : element.matches("input[type=range]") ? "range" : element.matches("input") ? "input" : "button";
  const picker = element.closest("[data-time-picker]");
  let value = element.value ?? "";
  if (element.dataset.timeToggle !== undefined && picker) {
    const [id, field] = picker.dataset.timePicker.split("|");
    value = alarms.find((alarm) => alarm.id === id)?.[field] || value;
  }
  return {
    key,
    type,
    left: rect.left,
    top: rect.top,
    width: rect.width,
    height: rect.height,
    value,
    text: element.matches("input, select")
      ? (element.getAttribute("aria-label") || element.id || "")
      : element.querySelector("span")?.textContent?.trim() || element.textContent.trim(),
    hint: element.getAttribute("placeholder") || "",
    inputType: element.getAttribute("type") || "text",
    checked: isSwitch ? element.checked : false,
    enabled: !element.disabled,
    min: Number(element.min || 0),
    max: Number(element.max || 1),
    step: Number(element.step || 1),
    options: element.matches("select") ? [...element.options].map((option) => ({ value: option.value, label: option.textContent })) : [],
    className: element.className || "",
    parentClassName: element.parentElement?.className || ""
  };
}

function syncNativeControls() {
  const bridge = nativeControlsBridge();
  if (!bridge) return;
  const elements = [...document.querySelectorAll("button, input, select")].filter((element) => !element.matches(".time-option"));
  const controls = elements.map(nativeControlDescriptor).filter(Boolean);
  bridge.syncNativeControls(JSON.stringify({ view: currentView, controls }));
}

const syncNativeSwitches = syncNativeControls;

window.setNativeSwitch = (key, checked) => {
  const input = key.startsWith("alarm:")
    ? [...document.querySelectorAll("input[data-toggle]")].find((item) => item.dataset.toggle === key.slice(6))
    : document.getElementById(key);
  if (!input || input.type !== "checkbox" || input.checked === Boolean(checked)) return;
  input.checked = Boolean(checked);
  input.dispatchEvent(new Event("change", { bubbles: true }));
};

window.dispatchNativeControl = (key, value) => {
  const element = [...document.querySelectorAll("[data-native-key]")].find((item) => item.dataset.nativeKey === key);
  if (!element) return;
  if (element.matches("[data-time-toggle]")) {
    const picker = element.closest("[data-time-picker]");
    const [id, field] = picker.dataset.timePicker.split("|");
    updateAlarmInline(id, { [field]: String(value) });
    renderList();
    return;
  }
  if (value !== undefined && "value" in element) {
    element.value = String(value);
    element.dispatchEvent(new Event("input", { bubbles: true }));
    element.dispatchEvent(new Event("change", { bubbles: true }));
    return;
  }
  element.click();
};

function mergeHistoryLists(...lists) {
  const byId = new Map();
  for (const list of lists) {
    for (const item of Array.isArray(list) ? list : []) {
      if (item?.id) byId.set(item.id, item);
    }
  }
  return [...byId.values()].sort((left, right) => Number(left.atMs || 0) - Number(right.atMs || 0)).slice(-MAX_HISTORY);
}

function syncNativeState() {
  const bridge = nativeBridge();
  if (!bridge || typeof bridge.syncState !== "function") return;
  bridge.syncState(JSON.stringify({ alarms, history }));
}

function syncNativeQuickTimers() {
  const bridge = nativeBridge();
  if (!bridge || typeof bridge.syncQuickTimers !== "function") return;
  bridge.syncQuickTimers(JSON.stringify(quickTimers.map(({ audioStarted, ...timer }) => timer)));
}

window.applyNativeAlarmState = (stateJson) => {
  try {
    const state = JSON.parse(stateJson || "{}");
    const nativeAlarms = Array.isArray(state.alarms) ? state.alarms : [];
    if (nativeAlarms.length > 0 || alarms.length === 0) {
      alarms = nativeAlarms.map((alarm) => createAlarm({ ...alarm, alarmType: "auto" }));
      saveJson(ALARMS_KEY, alarms);
    }
    history = mergeHistoryLists(history, state.history);
    saveJson(HISTORY_KEY, history);
    if (Array.isArray(state.quickTimers)) {
      quickTimers = state.quickTimers.map((timer) => ({ ...timer, audioStarted: false }));
      saveJson(QUICK_TIMERS_KEY, state.quickTimers);
    }
    render();
  } catch {
    // Native state is optional in browser mode; keep the last valid local state.
  }
};

function hydrateNativeState() {
  const bridge = nativeBridge();
  if (!bridge) return;
  try {
    const state = JSON.parse(bridge.getState() || "{}");
    const nativeAlarms = Array.isArray(state.alarms) ? state.alarms : [];
    if (nativeAlarms.length > 0 || alarms.length === 0) {
      alarms = nativeAlarms.map((alarm) => createAlarm({ ...alarm, alarmType: "auto" }));
      saveJson(ALARMS_KEY, alarms);
    }
    history = mergeHistoryLists(history, state.history);
    saveJson(HISTORY_KEY, history);
    if (Array.isArray(state.quickTimers)) {
      quickTimers = state.quickTimers.map((timer) => ({ ...timer, audioStarted: false }));
      saveJson(QUICK_TIMERS_KEY, state.quickTimers);
    }
    syncNativeState();
    syncNativeQuickTimers();
  } catch {
    // Browser mode remains usable if the Android bridge is unavailable.
  }
}

function saveQuickTimers() {
  saveJson(QUICK_TIMERS_KEY, quickTimers.map(({ audioStarted, ...timer }) => timer));
  syncNativeQuickTimers();
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" }[character]));
}

function navMarkup(active) {
  return [
    ["quick", "◷", "Multi-Timer"], ["list", "☰", "Alarms"], ["counter", "◉", "Counter"], ["info", "☁", "Weather"]
  ].map(([view, icon, label]) => `<button type="button" data-view="${view}" class="${active === view ? "active" : ""}">${icon}<span>${label}</span></button>`).join("");
}

const WEATHER_CODES = {
  "0": "Clear sky", "1": "Mainly clear", "2": "Partly cloudy", "3": "Overcast", "45": "Fog", "48": "Rime fog",
  "51": "Light drizzle", "53": "Drizzle", "55": "Heavy drizzle", "56": "Freezing drizzle", "57": "Heavy freezing drizzle",
  "61": "Slight rain", "63": "Rain", "65": "Heavy rain", "66": "Freezing rain", "67": "Heavy freezing rain",
  "71": "Slight snow", "73": "Snow", "75": "Heavy snow", "77": "Snow grains", "80": "Slight rain showers",
  "81": "Rain showers", "82": "Heavy rain showers", "85": "Slight snow showers", "86": "Heavy snow showers",
  "95": "Thunderstorm", "96": "Thunderstorm with hail", "99": "Heavy thunderstorm with hail"
};

function primeAlarmAudio() {
  void alarmAudio.ensureContext().catch(() => { /* the ringing UI still works if audio is unavailable */ });
}

function setupNavigation() {
  document.querySelectorAll(".bottom-nav").forEach((nav) => { nav.innerHTML = navMarkup("list"); });
  document.addEventListener("click", (event) => {
    const button = event.target.closest("[data-view]");
    if (button) showView(button.dataset.view);
  });
}

function showView(view) {
  currentView = view;
  Object.entries(screens).forEach(([name, screen]) => screen.classList.toggle("hidden", name !== view));
  document.querySelectorAll(".bottom-nav").forEach((nav) => { nav.innerHTML = navMarkup(view); });
  render();
  if (view === "info") void loadWeather();
  syncNativeSwitches();
}

function render() {
  if (currentView === "list") renderList();
  if (currentView === "edit") renderEdit();
  if (currentView === "counter") renderCounter();
  if (currentView === "quick") renderQuick();
  if (currentView === "info") renderInfo();
  renderRingingBanner();
  syncNativeControls();
}

function renderList() {
  const list = document.querySelector("#alarm-list");
  if (!alarms.length) {
    list.innerHTML = `<div class="empty-state">No alarms yet.<br />Use + to create your first alarm.</div>`;
    return;
  }
  list.innerHTML = alarms.map((alarm) => `
    <article class="alarm-card" data-edit="${alarm.id}">
      <div class="alarm-time-row">
        <span class="inline-time-group">${timePickerMarkup(alarm.id, "startTime", alarm.startTime, "Start time")}${alarm.frequency === "several" ? `<span class="time-range-separator">–</span>${timePickerMarkup(alarm.id, "endTime", alarm.endTime, "End time")}` : ""}</span>
      </div>
      <div class="alarm-details-row">
        <input class="inline-input inline-name" data-inline-field="name" value="${escapeHtml(alarm.name)}" aria-label="Alarm name" />
        ${alarm.frequency === "several" ? `<label class="inline-interval"><span class="sr-only">Interval</span><input type="number" min="1" max="1440" step="1" value="${alarm.intervalMinutes}" data-inline-field="intervalMinutes" aria-label="Alarm interval in minutes" /><span class="interval-unit">min</span></label>` : `<span class="alarm-mode">${escapeHtml(formatDays(alarm.repeatDays))}</span>`}
      </div>
      <div class="alarm-day-strip" aria-label="${escapeHtml(formatDays(alarm.repeatDays))}">${DAY_CODES.map((day) => `<button type="button" class="${alarm.repeatDays.includes(day) ? "selected" : ""}" data-inline-day="${day}" aria-label="${DAY_LABELS[day]}" aria-pressed="${alarm.repeatDays.includes(day)}">${day[0]}</button>`).join("")}</div>
      <div class="alarm-status"><button type="button" class="advanced-button" data-advanced="${alarm.id}" aria-label="Advanced settings for ${escapeHtml(alarm.name)}">⚙</button><label class="alarm-switch"><span class="alarm-switch-label">${alarm.enabled ? "ON" : "OFF"}</span><span class="alarm-switch-control"><input type="checkbox" class="alarm-switch-input" data-toggle="${alarm.id}" role="switch" aria-checked="${alarm.enabled}" aria-label="Toggle ${escapeHtml(alarm.name)}" ${alarm.enabled ? "checked" : ""} /><span class="alarm-switch-track" aria-hidden="true"><span class="alarm-switch-thumb"></span></span></span></label></div>
    </article>`).join("");
}

function formatInterval(minutes) {
  if (minutes % 60 === 0) return `${minutes / 60} ${minutes === 60 ? "hour" : "hours"}`;
  return `${minutes} minutes`;
}

function timePickerMarkup(alarmId, field, selected, label) {
  return `<span class="time-picker" data-time-picker="${escapeHtml(alarmId)}|${field}"><button type="button" class="inline-time-button" data-time-toggle aria-label="${label}">${displayTime(selected)}⌄</button><div class="time-picker-menu" role="listbox" aria-label="Choose ${label.toLowerCase()}"></div></span>`;
}

function timeMenuMarkup(selected) {
  const options = [];
  for (let minutes = 0; minutes < 1440; minutes += 1) {
    const value = formatTime24(minutes);
    options.push(`<button type="button" role="option" class="time-option${value === selected ? " selected" : ""}" data-time-option="${value}" aria-selected="${value === selected}">${value}</button>`);
  }
  return options.join("");
}

function formatCompactTime(alarm) {
  return alarm.frequency === "once"
    ? displayTime(alarm.startTime)
    : `${displayTime(alarm.startTime)}–${displayTime(alarm.endTime)}`;
}

function displayTime(value) {
  return formatTime24(value);
}

function displayDateTime(timestamp) {
  const date = new Date(timestamp);
  const minutes = date.getHours() * 60 + date.getMinutes();
  return `${date.toLocaleDateString()} ${displayTime(minutes)}`;
}

function formatSnoozeLength(minutes) {
  return `${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
}

function nextSnoozeMinutes(alarm, snoozeIndex) {
  if (!alarm.snoozeEnabled || !alarm.snoozeSequenceMinutes.length) return null;
  const values = alarm.snoozeSequenceMinutes;
  if (snoozeIndex < values.length) return values[snoozeIndex];
  if (alarm.afterSnoozeExhausted === "repeat-last") return values[values.length - 1];
  if (alarm.afterSnoozeExhausted === "repeat-sequence") return values[0];
  return null;
}

function syncFrequencyFields() {
  const isSingle = draft?.frequency === "once";
  const timeRange = document.querySelector("#time-range");
  const timeRangeLabel = document.querySelector("#time-range-label");
  const timeRangeHelp = document.querySelector("#time-range-help");
  const separator = document.querySelector("#time-range-separator");
  const endTime = document.querySelector("#end-time");
  const previewCard = document.querySelector("#preview-card");
  if (!timeRange || !timeRangeLabel || !timeRangeHelp || !separator || !endTime) return;

  timeRange.classList.toggle("single", isSingle);
  timeRangeLabel.textContent = isSingle ? "Alarm time" : "Alarm time range";
  timeRangeHelp.textContent = isSingle ? "Start" : "Start and end";
  separator.classList.toggle("hidden", isSingle);
  endTime.classList.toggle("hidden", isSingle);
  endTime.disabled = isSingle;
  endTime.required = !isSingle;
  document.querySelector("#interval-card").classList.toggle("hidden", isSingle);
  previewCard?.classList.toggle("hidden", isSingle);
}

function renderEdit() {
  if (!draft) return;
  const form = document.querySelector("#alarm-form");
  form.querySelector("#alarm-name").value = draft.name;
  form.querySelector("#start-time").value = draft.startTime;
  form.querySelector("#end-time").value = draft.endTime;
  form.querySelector("#interval-minutes").value = String(draft.intervalMinutes);
  form.querySelector("#sound").value = draft.sound;
  form.querySelector("#volume").value = String(draft.volume);
  form.querySelector("#volume-value").value = `${Math.round(draft.volume * 100)}%`;
  form.querySelector("#volume-value").textContent = `${Math.round(draft.volume * 100)}%`;
  form.querySelector("#duration-seconds").value = String(draft.durationSeconds);
  form.querySelector("#snooze-enabled").checked = draft.snoozeEnabled;
  form.querySelector("#snooze-sequence").value = draft.snoozeSequenceMinutes.join(", ");
  form.querySelector("#after-snooze").value = draft.afterSnoozeExhausted;
  form.querySelector("#enabled").checked = draft.enabled;
  form.querySelector("#enabled-state").textContent = draft.enabled ? "ON" : "OFF";
  form.querySelector("#enabled").setAttribute("aria-checked", String(draft.enabled));
  syncSwitchStates(form);
  document.querySelectorAll("#frequency-choice button").forEach((button) => button.classList.toggle("selected", button.dataset.frequency === draft.frequency));
  syncFrequencyFields();
  document.querySelector("#repeat-days").innerHTML = DAY_CODES.map((day) => `<button type="button" data-day="${day}" class="${draft.repeatDays.includes(day) ? "selected" : ""}" aria-label="${DAY_LABELS[day]}">${day[0]}</button>`).join("");
  renderPreview();
  syncNativeSwitches();
}

function syncSwitchStates(root = document) {
  root.querySelectorAll('input[type="checkbox"][role="switch"]').forEach((input) => {
    const state = root.querySelector(`[data-switch-label="${input.id}"]`);
    if (state) state.textContent = input.checked ? "ON" : "OFF";
    input.setAttribute("aria-checked", String(input.checked));
  });
}

function renderPreview() {
  if (!draft) return;
  const preview = previewOccurrences(draft);
  const expanded = document.querySelector("#preview-list").classList.contains("expanded");
  const visible = expanded ? preview : preview.slice(0, 4);
  document.querySelector("#preview-list").innerHTML = visible.map((item) => `<div class="preview-row"><span>${item.index} ${item.index === 1 ? "time" : "times"}</span><span></span><strong>${formatTime24(item.minutes)}</strong></div>`).join("");
  document.querySelector("#view-all-preview").textContent = expanded ? "Show less" : `View all ${preview.length}`;
}

function renderRingingBanner() {
  const banner = document.querySelector("#ringing-banner");
  if (isNativeAndroid()) {
    banner.classList.add("hidden");
    return;
  }
  banner.classList.toggle("hidden", !ringing);
  if (ringing) {
    const minutes = nextSnoozeMinutes(ringing.alarm, ringing.snoozeIndex);
    document.querySelector("#ringing-name").textContent = ringing.alarm.name;
    document.querySelector("#ringing-time").textContent = minutes === null
      ? `Started ${displayDateTime(ringing.occurrenceAtMs)}`
      : `Started ${displayDateTime(ringing.occurrenceAtMs)} · Next snooze ${formatSnoozeLength(minutes)}`;
    document.querySelector("#ringing-snooze").textContent = minutes === null ? "Snooze unavailable" : `Snooze ${formatSnoozeLength(minutes)}`;
    document.querySelector("#ringing-snooze").disabled = minutes === null;
  }
}

function renderQuick() {
  const activeCount = quickTimers.length;
  document.querySelector("#start-quick-timer").disabled = activeCount >= MAX_QUICK_TIMERS;

  const timers = document.querySelector("#quick-timers");
  const visibleTimers = isNativeAndroid()
    ? quickTimers.filter((timer) => timer.state !== "ringing")
    : quickTimers;
  timers.innerHTML = visibleTimers.length
    ? visibleTimers.map((timer) => {
      const seconds = timer.state === "running" ? Math.max(0, Math.ceil((timer.endsAtMs - Date.now()) / 1000)) : timer.remainingSeconds;
      const action = timer.state === "running"
        ? `<button data-quick-action="pause" data-quick-id="${timer.id}">Pause</button>`
        : timer.state === "paused"
          ? `<button data-quick-action="resume" data-quick-id="${timer.id}">Resume</button>`
          : `<button data-quick-action="snooze" data-quick-id="${timer.id}">Snooze 5m</button>`;
      const status = timer.state === "ringing" ? "Alarm ringing" : timer.state === "paused" ? "Paused" : "Running";
      return `<article class="quick-timer-card ${timer.state === "ringing" ? "ringing" : ""}"><div><strong>${escapeHtml(timer.label)}</strong><span>${status}</span></div><time>${formatQuickDuration(seconds)}</time><div class="quick-timer-actions">${action}<button data-quick-action="stop" data-quick-id="${timer.id}">Stop</button></div></article>`;
    }).join("")
    : `<div class="empty-state">No timers running.</div>`;
}

function renderCounter() {
  const list = document.querySelector("#counter-list");
  list.innerHTML = counters.length
    ? counters.map((counter) => `
      <article class="counter-card" data-counter-id="${escapeHtml(counter.id)}">
        <div class="counter-card-header">
          <label class="counter-name-field"><span class="sr-only">Counter name</span><input type="text" value="${escapeHtml(counter.name)}" data-counter-field="name" aria-label="Counter name" /></label>
          <button type="button" class="counter-delete" data-counter-action="delete" aria-label="Delete ${escapeHtml(counter.name)}">×</button>
        </div>
        <output class="counter-value" aria-live="polite">${counter.value.toLocaleString()}</output>
        <div class="counter-controls">
          <button type="button" class="counter-step-button" data-counter-action="decrement" aria-label="Decrease ${escapeHtml(counter.name)}">−</button>
          <button type="button" class="counter-reset" data-counter-action="reset">Reset</button>
          <button type="button" class="counter-step-button" data-counter-action="increment" aria-label="Increase ${escapeHtml(counter.name)}">+</button>
        </div>
      </article>`).join("")
    : `<div class="empty-state">No counters yet.<br />Use + to create one.</div>`;
}

function updateCounter(id, changes) {
  const current = counters.find((counter) => counter.id === id);
  if (!current) return;
  const next = normalizeCounter({ ...current, ...changes, updatedAt: Date.now() });
  counters = counters.map((counter) => counter.id === id ? next : counter);
  saveCounters();
  renderCounter();
}

function changeCounter(id, action) {
  const current = counters.find((counter) => counter.id === id);
  if (!current) return;
  const delta = action === "increment" ? current.step : action === "decrement" ? -current.step : 0;
  updateCounter(id, { value: action === "reset" ? 0 : current.value + delta });
}

function addCounter() {
  if (counters.length >= 20) return;
  counters.push(createCounter(`Counter ${counters.length + 1}`));
  saveCounters();
  showView("counter");
  const newest = document.querySelector("#counter-list .counter-card:last-child");
  newest?.querySelector("[data-counter-field=\"name\"]")?.focus();
}

function deleteCounter(id) {
  counters = counters.filter((counter) => counter.id !== id);
  saveCounters();
  renderCounter();
}

function formatQuickDuration(totalSeconds) {
  const seconds = Math.max(0, Math.floor(Number(totalSeconds) || 0));
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remainder = seconds % 60;
  return hours > 0
    ? `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`
    : `${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`;
}

function addQuickHistory(timer, action) {
  history.push({ id: crypto.randomUUID(), alarmId: timer.id, name: timer.label, action, atMs: Date.now() });
  history = history.slice(-MAX_HISTORY);
  saveJson(HISTORY_KEY, history);
  syncNativeState();
}

function startQuickTimer(durationSeconds, label) {
  if (quickTimers.length >= MAX_QUICK_TIMERS) return setQuickMessage("You already have 10 timers running.", "error");
  if (!Number.isInteger(durationSeconds) || durationSeconds <= 0) return setQuickMessage("Enter a duration longer than zero.", "error");
  if (durationSeconds > MAX_CUSTOM_MINUTES * 60) return setQuickMessage("A timer cannot exceed one month (720 hours).", "error");
  const timer = { id: `quick-${crypto.randomUUID()}`, label: label.trim() || "Timer", totalSeconds: durationSeconds, remainingSeconds: durationSeconds, endsAtMs: Date.now() + durationSeconds * 1000, state: "running", snoozeCount: 0 };
  quickTimers.unshift(timer);
  saveQuickTimers();
  addQuickHistory(timer, "started");
  setQuickMessage("");
  renderQuick();
}

function selectedQuickMinutes() {
  const preset = document.querySelector("#quick-minutes").value;
  if (preset !== "custom") return Number(preset);
  return Number(document.querySelector("#quick-custom-minutes").value);
}

function setQuickMessage(message, kind = "info") {
  const element = document.querySelector("#quick-message");
  element.textContent = message;
  element.dataset.kind = kind;
}

function stopQuickTimer(id, action = "stopped") {
  const timer = quickTimers.find((item) => item.id === id);
  if (!timer) return;
  if (isNativeAndroid()) {
    const bridge = nativeBridge();
    if (typeof bridge.stopQuickTimer === "function") bridge.stopQuickTimer(id);
    quickTimers = quickTimers.filter((item) => item.id !== id);
    saveJson(QUICK_TIMERS_KEY, quickTimers);
    renderQuick();
    return;
  }
  quickAudio.get(id)?.stop();
  quickAudio.delete(id);
  addQuickHistory(timer, action);
  quickTimers = quickTimers.filter((item) => item.id !== id);
  saveQuickTimers();
  renderQuick();
}

function pauseQuickTimer(id) {
  const timer = quickTimers.find((item) => item.id === id);
  if (!timer || timer.state !== "running") return;
  timer.remainingSeconds = Math.max(0, Math.ceil((timer.endsAtMs - Date.now()) / 1000));
  timer.state = "paused";
  delete timer.endsAtMs;
  saveQuickTimers();
  renderQuick();
}

function resumeQuickTimer(id) {
  const timer = quickTimers.find((item) => item.id === id);
  if (!timer || timer.state !== "paused") return;
  timer.endsAtMs = Date.now() + timer.remainingSeconds * 1000;
  timer.state = "running";
  saveQuickTimers();
  renderQuick();
}

function snoozeQuickTimer(id) {
  const timer = quickTimers.find((item) => item.id === id);
  if (!timer || timer.state !== "ringing") return;
  if (isNativeAndroid()) {
    const bridge = nativeBridge();
    if (typeof bridge.snoozeQuickTimer === "function") bridge.snoozeQuickTimer(id);
    timer.state = "running";
    timer.snoozeCount = Number(timer.snoozeCount || 0) + 1;
    timer.remainingSeconds = 5 * 60;
    timer.endsAtMs = Date.now() + timer.remainingSeconds * 1000;
    saveJson(QUICK_TIMERS_KEY, quickTimers);
    renderQuick();
    return;
  }
  quickAudio.get(id)?.stop();
  quickAudio.delete(id);
  timer.state = "running";
  timer.snoozeCount += 1;
  timer.remainingSeconds = 5 * 60;
  timer.endsAtMs = Date.now() + timer.remainingSeconds * 1000;
  addQuickHistory(timer, "snoozed 5 minutes");
  saveQuickTimers();
  renderQuick();
}

async function ringQuickTimer(timer) {
  if (timer.audioStarted) return;
  timer.audioStarted = true;
  const audio = new AlarmAudio();
  quickAudio.set(timer.id, audio);
  addQuickHistory(timer, "ringing");
  await audio.start({ sound: "classic", volume: 1, gradualVolume: false, vibration: true, vibrationPattern: "standard" });
}

function quickTimerTick() {
  if (isNativeAndroid()) {
    if (currentView === "quick") renderQuick();
    return;
  }
  const now = Date.now();
  let changed = false;
  for (const timer of quickTimers) {
    if (timer.state === "running" && timer.endsAtMs <= now) {
      timer.state = "ringing";
      timer.remainingSeconds = 0;
      changed = true;
      void ringQuickTimer(timer);
    } else if (timer.state === "ringing" && !timer.audioStarted) {
      void ringQuickTimer(timer);
    }
  }
  if (changed) saveQuickTimers();
  if (currentView === "quick") renderQuick();
}

function setWeatherMessage(message, kind = "info") {
  const target = document.querySelector("#weather-message");
  if (!target) return;
  target.textContent = message;
  target.dataset.kind = kind;
}

function formatWeatherTime(value) {
  const date = new Date(value);
  return new Intl.DateTimeFormat(undefined, { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(date);
}

function weatherNumber(value, suffix = "") {
  return Number.isFinite(Number(value)) ? `${Number(value).toFixed(1)}${suffix}` : "—";
}

function weatherDescription(code) {
  return WEATHER_CODES[String(code)] ?? `Weather code ${code ?? "—"}`;
}






function renderWeatherData() {
  const panel = document.querySelector("#weather-data");
  const current = weatherState.current;
  const daily = weatherState.daily;
  if (!panel || !current || !daily?.time?.length) {
    panel?.classList.add("hidden");
    return;
  }

  panel.classList.remove("hidden");
  const formatDay = (value) => new Intl.DateTimeFormat(undefined, { weekday: "short", day: "2-digit", month: "short" }).format(new Date(`${value}T12:00:00`));
  document.querySelector("#weather-location-name").textContent = "Your location";
  document.querySelector("#weather-coordinates").textContent = `${Number(weatherState.latitude).toFixed(4)}, ${Number(weatherState.longitude).toFixed(4)}`;
  document.querySelector("#weather-temperature").textContent = weatherNumber(current.temperature_2m, "°C");
  document.querySelector("#weather-description").textContent = weatherDescription(current.weather_code);
  document.querySelector("#weather-updated").textContent = `Forecast time ${formatWeatherTime(current.time)}`;
  document.querySelector("#weather-low").textContent = weatherNumber(daily.temperature_2m_min[0], "°C");
  document.querySelector("#weather-high").textContent = weatherNumber(daily.temperature_2m_max[0], "°C");
  document.querySelector("#weather-wind").textContent = weatherNumber(current.wind_speed_10m, " km/h");
  document.querySelector("#weather-humidity").textContent = weatherNumber(current.relative_humidity_2m, "%");
  document.querySelector("#weather-hourly").innerHTML = daily.time.slice(0, 3).map((date, index) => `<div class="weather-hour"><time>${formatDay(date)}</time><strong>${weatherNumber(daily.temperature_2m_max[index], "°C")}</strong><span>${escapeHtml(weatherDescription(daily.weather_code[index]))}</span><small>Low ${weatherNumber(daily.temperature_2m_min[index], "°C")}</small></div>`).join("");
  document.querySelector("#weather-status").textContent = "GPS connected";
}

function renderInfo() {
  renderWeatherData();
}

function currentPosition() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) return reject(new Error("This browser does not provide GPS location."));
    navigator.geolocation.getCurrentPosition(resolve, (error) => {
      const messages = { 1: "Location permission was denied.", 2: "Your location could not be found.", 3: "Location lookup timed out." };
      reject(new Error(messages[error.code] || "Location lookup failed."));
    }, { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 });
  });
}

async function loadWeather() {
  setWeatherMessage("Getting your location and Open-Meteo forecast…");
  try {
    const position = await currentPosition();
    const latitude = position.coords.latitude;
    const longitude = position.coords.longitude;
    const url = new URL(OPEN_METEO_ENDPOINT);
    url.search = new URLSearchParams({ latitude: latitude.toFixed(6), longitude: longitude.toFixed(6), timezone: "auto", forecast_days: "3", current: "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m", daily: "temperature_2m_min,temperature_2m_max,weather_code" });
    const response = await fetch(url);
    if (!response.ok) throw new Error(`Open-Meteo request failed (${response.status}).`);
    const data = await response.json();
    if (!data.current || !data.daily?.time?.length) throw new Error("Open-Meteo returned no forecast for this location.");
    weatherState = { ...weatherState, latitude, longitude, timezone: data.timezone || "", current: data.current, hourly: data.hourly || null, daily: data.daily, receivedAt: Date.now() };
    saveJson(WEATHER_KEY, weatherState);
    renderInfo();
    setWeatherMessage("");
  } catch (error) {
    setWeatherMessage(error.message || "Weather could not be loaded.", "error");
  }
}

function readDraft() {
  const form = document.querySelector("#alarm-form");
  return validateAlarm({
    ...draft,
    name: form.querySelector("#alarm-name").value.trim(),
    frequency: form.querySelector("#frequency-choice .selected").dataset.frequency,
    repeatDays: [...form.querySelectorAll("#repeat-days .selected")].map((button) => button.dataset.day),
    startTime: form.querySelector("#start-time").value,
    endTime: form.querySelector("#end-time").value,
    intervalMinutes: Number(form.querySelector("#interval-minutes").value),
    alarmType: "auto",
    sound: form.querySelector("#sound").value,
    volume: Number(form.querySelector("#volume").value),
    durationSeconds: Number(form.querySelector("#duration-seconds").value),
    snoozeEnabled: form.querySelector("#snooze-enabled").checked,
    snoozeSequenceMinutes: form.querySelector("#snooze-sequence").value.split(",").map((value) => Number(value.trim())).filter((value) => value > 0),
    afterSnoozeExhausted: form.querySelector("#after-snooze").value,
    enabled: form.querySelector("#enabled").checked
  });
}

function openEditor(id = null) {
  editingId = id;
  draft = id ? createAlarm(JSON.parse(JSON.stringify(alarms.find((alarm) => alarm.id === id)))) : createAlarm({ name: "New alarm" });
  document.querySelector("#edit-screen").scrollTop = 0;
  window.scrollTo(0, 0);
  showView("edit");
}

function saveDraft(event) {
  event.preventDefault();
  try {
    draft = readDraft();
    if (editingId) alarms = alarms.map((alarm) => alarm.id === editingId ? draft : alarm);
    else alarms.push(draft);
    nextEvents.delete(draft.id);
    snoozeEvents.delete(draft.id);
    saveJson(ALARMS_KEY, alarms);
    syncNativeState();
    setFormMessage("Saved");
    showView("list");
  } catch (error) { setFormMessage(error.message, "error"); }
}

function setFormMessage(message, kind = "info") {
  const messageElement = document.querySelector("#form-message");
  messageElement.textContent = message;
  messageElement.dataset.kind = kind;
}

function deleteCurrentAlarm() {
  if (!editingId) { showView("list"); return; }
  alarms = alarms.filter((alarm) => alarm.id !== editingId);
  saveJson(ALARMS_KEY, alarms);
  syncNativeState();
  nextEvents.delete(editingId);
  snoozeEvents.delete(editingId);
  showView("list");
}

function audioSettings(alarm) {
  return { sound: alarm.sound, volume: alarm.volume, gradualVolume: true, vibration: alarm.alarmType !== "sound", vibrationPattern: "standard" };
}

function addHistory(alarm, action) {
  history.push({ id: crypto.randomUUID(), alarmId: alarm.id, name: alarm.name, action, atMs: Date.now(), alarmSnapshot: JSON.parse(JSON.stringify(alarm)) });
  history = history.slice(-MAX_HISTORY);
  saveJson(HISTORY_KEY, history);
  syncNativeState();
}

function localMidnight(date) { return new Date(date.getFullYear(), date.getMonth(), date.getDate()); }

function timestampFor(base, minutes) {
  const result = new Date(base);
  result.setMinutes(result.getMinutes() + minutes);
  return result.getTime();
}

function dayCode(date) { return JS_DAY_CODES[date.getDay()]; }

function nextOccurrence(alarm, afterMs) {
  const after = new Date(afterMs);
  for (let offset = 0; offset < 8; offset += 1) {
    const baseDate = new Date(after.getFullYear(), after.getMonth(), after.getDate() + offset);
    if (!alarm.repeatDays.includes(dayCode(baseDate))) continue;
    const base = localMidnight(baseDate);
    const occurrences = previewOccurrences(alarm);
    for (const occurrence of occurrences) {
      const timestamp = timestampFor(base, occurrence.minutes);
      if (timestamp > afterMs) return timestamp;
    }
  }
  return null;
}

async function triggerAlarm(alarm, occurrenceAtMs, snoozeIndex = 0) {
  if (isNativeAndroid()) return;
  if (ringing) return;
  ringing = { alarm, occurrenceAtMs, snoozeIndex };
  addHistory(alarm, "started");
  clearTimeout(ringStopTimer);
  ringStopTimer = window.setTimeout(() => stopRinging("duration complete"), alarm.durationSeconds * 1000);
  render();
  try {
    await alarmAudio.start(audioSettings(alarm));
    if (!ringing || ringing.alarm.id !== alarm.id || ringing.occurrenceAtMs !== occurrenceAtMs) alarmAudio.stop();
  } catch {
    addHistory(alarm, "sound unavailable");
  }
}

function stopRinging(action = "stopped") {
  if (!ringing) return;
  snoozeEvents.delete(ringing.alarm.id);
  addHistory(ringing.alarm, action);
  alarmAudio.stop();
  clearTimeout(ringStopTimer);
  ringing = null;
  render();
}

function snoozeRinging() {
  if (!ringing) return;
  const alarm = ringing.alarm;
  const values = alarm.snoozeSequenceMinutes;
  if (!alarm.snoozeEnabled || !values.length) return stopRinging("stopped");
  let index = ringing.snoozeIndex;
  if (index >= values.length) {
    if (alarm.afterSnoozeExhausted === "repeat-last") index = values.length - 1;
    else if (alarm.afterSnoozeExhausted === "repeat-sequence") index = 0;
    else return stopRinging("dismissed after snooze sequence");
  }
  snoozeEvents.set(alarm.id, { atMs: Date.now() + values[index] * 60_000, snoozeIndex: index + 1 });
  addHistory(alarm, `snoozed ${values[index]} minutes`);
  alarmAudio.stop();
  clearTimeout(ringStopTimer);
  ringing = null;
  render();
}

function schedulerTick() {
  if (isNativeAndroid()) return;
  const now = Date.now();
  for (const alarm of alarms.filter((item) => item.enabled)) {
    const snooze = snoozeEvents.get(alarm.id);
    if (!snooze && !nextEvents.has(alarm.id)) nextEvents.set(alarm.id, nextOccurrence(alarm, now - 1000));
    const due = snooze?.atMs ?? nextEvents.get(alarm.id);
    if (due !== null && due <= now && !ringing) {
      if (snooze) snoozeEvents.delete(alarm.id);
      else nextEvents.set(alarm.id, nextOccurrence(alarm, now + 1000));
      void triggerAlarm(alarm, due, snooze?.snoozeIndex ?? 0);
    }
  }
}

document.querySelector("#add-alarm").addEventListener("click", () => openEditor());
document.querySelector("#back-to-list").addEventListener("click", () => showView("list"));
document.querySelector("#delete-alarm").addEventListener("click", deleteCurrentAlarm);
document.querySelector("#alarm-form").addEventListener("submit", saveDraft);
document.querySelector("#alarm-form").addEventListener("change", (event) => {
  if (event.target.matches('input[type="checkbox"][role="switch"]')) {
    syncSwitchStates(document);
    syncNativeSwitches();
  }
});
document.querySelector("#ringing-stop").addEventListener("click", () => stopRinging());
document.querySelector("#ringing-snooze").addEventListener("click", snoozeRinging);
document.querySelector("#quick-minutes").addEventListener("change", (event) => {
  const custom = document.querySelector("#quick-custom-minutes");
  custom.hidden = event.target.value !== "custom";
  if (!custom.hidden) custom.focus();
});
document.querySelector("#start-quick-timer").addEventListener("click", () => {
  const minutes = selectedQuickMinutes();
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > MAX_CUSTOM_MINUTES) {
    setQuickMessage("Custom timer must be between 1 minute and one month (43,200 minutes).", "error");
    return;
  }
  startQuickTimer(Math.round(minutes * 60), document.querySelector("#quick-label").value);
});

document.querySelector("#quick-timers").addEventListener("click", (event) => {
  const button = event.target.closest("[data-quick-action]");
  if (!button) return;
  const id = button.dataset.quickId;
  if (button.dataset.quickAction === "pause") pauseQuickTimer(id);
  if (button.dataset.quickAction === "resume") resumeQuickTimer(id);
  if (button.dataset.quickAction === "snooze") snoozeQuickTimer(id);
  if (button.dataset.quickAction === "stop") stopQuickTimer(id);
});

document.querySelector("#add-counter").addEventListener("click", addCounter);

document.querySelector("#counter-list").addEventListener("click", (event) => {
  const button = event.target.closest("[data-counter-action]");
  if (!button) return;
  const card = button.closest("[data-counter-id]");
  if (!card) return;
  const id = card.dataset.counterId;
  if (button.dataset.counterAction === "delete") deleteCounter(id);
  else changeCounter(id, button.dataset.counterAction);
});

document.querySelector("#counter-list").addEventListener("change", (event) => {
  const field = event.target.closest("[data-counter-field]");
  const card = field?.closest("[data-counter-id]");
  if (!field || !card) return;
  const current = counters.find((counter) => counter.id === card.dataset.counterId);
  if (!current) return;
  if (field.dataset.counterField === "name") updateCounter(current.id, { name: field.value });
});

function updateAlarmInline(id, changes) {
  const current = alarms.find((alarm) => alarm.id === id);
  if (!current) return;
  const updated = validateAlarm({ ...current, ...changes });
  alarms = alarms.map((alarm) => alarm.id === id ? updated : alarm);
  saveJson(ALARMS_KEY, alarms);
  syncNativeState();
  nextEvents.delete(id);
  snoozeEvents.delete(id);
}

document.querySelector("#alarm-list").addEventListener("click", (event) => {
  const timeToggle = event.target.closest("[data-time-toggle]");
  if (timeToggle) {
    const picker = timeToggle.closest("[data-time-picker]");
    const wasOpen = picker.classList.contains("open");
    document.querySelectorAll("[data-time-picker].open").forEach((item) => item.classList.remove("open"));
    if (!wasOpen) {
      const [id, field] = picker.dataset.timePicker.split("|");
      const alarm = alarms.find((item) => item.id === id);
      if (alarm) {
        const menu = picker.querySelector(".time-picker-menu");
        menu.innerHTML = timeMenuMarkup(alarm[field]);
        picker.classList.add("open");
        const selected = menu.querySelector(".time-option.selected");
        if (selected) menu.scrollTop = Math.max(0, selected.offsetTop - (menu.clientHeight - selected.offsetHeight) / 2);
      }
    }
    return;
  }
  const timeOption = event.target.closest("[data-time-option]");
  if (timeOption) {
    const picker = timeOption.closest("[data-time-picker]");
    const [id, field] = picker.dataset.timePicker.split("|");
    updateAlarmInline(id, { [field]: timeOption.dataset.timeOption });
    renderList();
    return;
  }
  const advanced = event.target.closest("[data-advanced]");
  if (advanced) {
    openEditor(advanced.dataset.advanced);
    return;
  }
  const dayButton = event.target.closest("[data-inline-day]");
  if (dayButton) {
    const card = dayButton.closest("[data-edit]");
    const alarm = alarms.find((item) => item.id === card?.dataset.edit);
    if (!alarm) return;
    const day = dayButton.dataset.inlineDay;
    if (alarm.repeatDays.length === 1 && alarm.repeatDays.includes(day)) return;
    const repeatDays = alarm.repeatDays.includes(day)
      ? alarm.repeatDays.filter((item) => item !== day)
      : [...alarm.repeatDays, day];
    updateAlarmInline(alarm.id, { repeatDays });
    renderList();
    return;
  }
  if (event.target.closest("[data-inline-field], [data-time-picker]")) return;
});

document.querySelector("#alarm-list").addEventListener("change", (event) => {
  const toggle = event.target.closest("input[data-toggle]");
  if (!toggle) return;
  const id = toggle.dataset.toggle;
  alarms = alarms.map((alarm) => alarm.id === id ? { ...alarm, enabled: toggle.checked } : alarm);
  saveJson(ALARMS_KEY, alarms);
  syncNativeState();
  nextEvents.delete(id);
  snoozeEvents.delete(id);
  render();
});

document.addEventListener("click", (event) => {
  if (!event.target.closest("[data-time-picker]")) document.querySelectorAll("[data-time-picker].open").forEach((item) => item.classList.remove("open"));
});

function commitInlineField(control) {
  if (!control) return;
  const card = control.closest("[data-edit]");
  if (!card) return;
  const field = control.dataset.inlineField;
  const value = field === "intervalMinutes" ? Number(control.value) : field === "name" ? control.value.trim() : control.value;
  const current = alarms.find((alarm) => alarm.id === card.dataset.edit);
  if (current && String(current[field]) === String(value)) return;
  try {
    updateAlarmInline(card.dataset.edit, { [field]: value });
  } catch {
    renderList();
    return;
  }
  renderList();
}

document.querySelector("#alarm-list").addEventListener("change", (event) => {
  commitInlineField(event.target.closest("[data-inline-field]"));
});

document.querySelector("#alarm-list").addEventListener("blur", (event) => {
  if (event.target.matches("[data-inline-field=\"name\"]")) commitInlineField(event.target);
});

document.querySelector("#frequency-choice").addEventListener("click", (event) => {
  const button = event.target.closest("[data-frequency]");
  if (!button) return;
  try { draft = readDraft(); } catch { /* keep the current draft when a field is incomplete */ }
  draft.frequency = button.dataset.frequency;
  renderEdit();
});

document.querySelector("#repeat-days").addEventListener("click", (event) => {
  const button = event.target.closest("[data-day]");
  if (!button) return;
  try { draft = readDraft(); } catch { /* keep the current draft when a field is incomplete */ }
  draft.repeatDays = draft.repeatDays.includes(button.dataset.day)
    ? draft.repeatDays.filter((day) => day !== button.dataset.day)
    : [...draft.repeatDays, button.dataset.day];
  renderEdit();
});

document.querySelector("#alarm-form").addEventListener("input", (event) => {
  if (event.target.id === "volume") document.querySelector("#volume-value").textContent = `${Math.round(Number(event.target.value) * 100)}%`;
  if (["start-time", "end-time", "interval-minutes"].includes(event.target.id)) {
    try { draft = readDraft(); renderPreview(); } catch { /* wait for a complete field */ }
  }
});

document.querySelector("#view-all-preview").addEventListener("click", () => {
  document.querySelector("#preview-list").classList.toggle("expanded");
  renderPreview();
});

document.querySelector("#preview-sound").addEventListener("click", async () => {
  try { draft = readDraft(); await alarmAudio.preview(audioSettings(draft)); } catch (error) { setFormMessage(error.message, "error"); }
});

setupNavigation();
hydrateNativeState();
document.querySelector("#edit-screen .alarm-form")?.addEventListener("scroll", syncNativeSwitches, { passive: true });
document.querySelector("#alarm-list")?.addEventListener("scroll", syncNativeSwitches, { passive: true });
window.addEventListener("resize", syncNativeSwitches);
document.addEventListener("pointerdown", primeAlarmAudio, { passive: true });
document.addEventListener("keydown", primeAlarmAudio, { passive: true });
document.addEventListener("visibilitychange", () => { if (!document.hidden) primeAlarmAudio(); });
showView("list");
setInterval(() => { schedulerTick(); quickTimerTick(); }, 1000);
