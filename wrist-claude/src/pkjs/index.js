// Wrist Assistant phone companion (PebbleKit JS). ES5 only.
var text = require('./text');
var settingsMod = require('./settings');
var claude = require('./claude');

var MAX_ANSWER_BYTES = 1500;
var CHUNK_BYTES = 180;
var MAX_ERROR_BYTES = 180;
var MAX_HISTORY = 6;
var MAX_RETRIES = 3;
var RETRY_DELAY_MS = 300;

var settings = settingsMod.load();
var conversation = []; // prior {role, content} messages
var askId = 0;         // bumps on each new ask so stale results are ignored

// ---------- outbox: one AppMessage at a time, wait for ACK ----------
var outbox = [];
var sending = false;

function enqueue(dict) {
  outbox.push({ dict: dict, retries: 0 });
  pump();
}

function pump() {
  if (sending || outbox.length === 0) { return; }
  sending = true;
  var item = outbox[0];
  try {
    Pebble.sendAppMessage(item.dict, onAck, onNack);
  } catch (e) {
    onNack();
  }
  function onAck() {
    outbox.shift();
    sending = false;
    pump();
  }
  function onNack() {
    if (item.retries < MAX_RETRIES) {
      item.retries++;
      setTimeout(function () { sending = false; pump(); }, RETRY_DELAY_MS);
    } else {
      outbox.shift();
      sending = false;
      pump();
    }
  }
}

// Drop queued (not yet in flight) messages, e.g. when a new question starts.
function clearPending() {
  outbox = outbox.length > 0 && sending ? [outbox[0]] : [];
}

// ---------- helpers ----------
function quickNames() {
  var names = [];
  for (var i = 0; i < settings.quickPrompts.length && i < 5; i++) {
    names.push(text.truncateBytes(text.sanitize(settings.quickPrompts[i].label).replace(/\n/g, ' '), 24));
  }
  return names.join('\n');
}

function sendReady() {
  enqueue({ READY: settings.apiKey ? 1 : 0, QUICK_NAMES: quickNames() });
}

function sendError(msg) {
  enqueue({ ERROR: text.truncateBytes(msg, MAX_ERROR_BYTES) });
}

function sendAnswer(answer) {
  var pieces = text.chunk(text.truncateBytes(answer, MAX_ANSWER_BYTES), CHUNK_BYTES);
  if (pieces.length === 0) { sendError('Claude sent no answer. Try again.'); return; }
  for (var i = 0; i < pieces.length; i++) {
    var d = { CHUNK: pieces[i] };
    if (i === pieces.length - 1) { d.CHUNK_FINAL = 1; }
    enqueue(d);
  }
}

function handleAsk(payload) {
  settings = settingsMod.load();
  var kind = payload.ASK_KIND | 0;
  var userText = '';
  if (kind === 3) { clearPending(); sendError('Hand off needs the phone companion.'); return; }
  if (kind === 2) {
    var idx = payload.QUICK_INDEX | 0;
    var q = settings.quickPrompts[idx];
    if (!q) { clearPending(); sendError('Quick prompt not found. Check settings.'); return; }
    userText = q.prompt;
  } else {
    userText = typeof payload.ASK_TEXT === 'string' ? payload.ASK_TEXT : '';
  }
  userText = userText.replace(/^\s+|\s+$/g, '');
  if (!userText) { clearPending(); sendError("Didn't catch that. Try again."); return; }

  var history;
  if (kind === 1) {
    history = conversation.slice(-MAX_HISTORY);
  } else {
    conversation = [];
    history = [];
  }

  clearPending();
  var myId = ++askId;
  claude.ask(settings, history, userText, {
    onStatus: function (t) { if (myId === askId) { enqueue({ STATUS: text.truncateBytes(t, MAX_ERROR_BYTES) }); } },
    onReminder: function (unix, rtext) {
      if (myId === askId) { enqueue({ REMINDER_TIME: unix, REMINDER_TEXT: text.truncateBytes(rtext, 60) }); }
    },
    onDone: function (answer) {
      if (myId !== askId) { return; }
      conversation = history.concat([{ role: 'user', content: userText }, { role: 'assistant', content: answer }]).slice(-MAX_HISTORY);
      sendAnswer(answer);
    },
    onError: function (t) { if (myId === askId) { sendError(t); } }
  });
}

// ---------- Pebble events ----------
Pebble.addEventListener('ready', function () {
  settings = settingsMod.load();
  sendReady();
});

Pebble.addEventListener('appmessage', function (e) {
  var p = (e && e.payload) || {};
  // Messages routed to the Android companion are not ours
  if (p.ROUTE === 1 || p.SPEAK !== undefined || p.CONFIRM_ID !== undefined) { return; }
  if (p.ASK_KIND !== undefined || p.ASK_TEXT !== undefined) {
    handleAsk(p);
  } else if (p.READY !== undefined) {
    settings = settingsMod.load();
    sendReady();
  }
});

Pebble.addEventListener('showConfiguration', function () {
  settings = settingsMod.load();
  Pebble.openURL(settingsMod.buildConfigUrl(settings));
});

Pebble.addEventListener('webviewclosed', function (e) {
  if (!e || !e.response) { return; }
  settings = settingsMod.applyConfigResponse(settingsMod.load(), e.response);
  settingsMod.save(settings);
  sendReady();
});
