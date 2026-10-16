// Settings storage, validation and the offline config page. ES5 only.
var text = require('./text');

var STORAGE_KEY = 'wc_settings';
var MODELS = ['claude-haiku-5-5', 'claude-sonnet-5-5', 'claude-opus-5-5'];
var LENGTHS = ['short', 'medium', 'long'];
var UNITS = ['imperial', 'metric'];
var MAX_PROMPTS = 5;
var MAX_LABEL = 24;
var MAX_PROMPT = 200;
var MAX_ABOUT = 300;
var MAX_CITY = 60;

function defaultQuickPrompts() {
  return [
    { label: 'Fun fact', prompt: 'Tell me a surprising fun fact.' },
    { label: 'Motivate me', prompt: 'Give me a short, genuine bit of motivation for right now.' },
    { label: 'Word of the day', prompt: 'Give me an interesting English word of the day with its meaning and an example.' }
  ];
}

function defaults() {
  return {
    apiKey: '',
    model: 'claude-haiku-5-5',
    length: 'short',
    webSearch: true,
    units: 'imperial',
    city: '',
    aboutMe: '',
    quickPrompts: defaultQuickPrompts()
  };
}

function indexOf(arr, v) {
  for (var i = 0; i < arr.length; i++) { if (arr[i] === v) { return i; } }
  return -1;
}

function str(v) {
  return (typeof v === 'string') ? v : '';
}

function clean(v, maxChars) {
  // trim, drop control characters except newline in aboutMe-style fields handled by caller
  return str(v).replace(/^\s+|\s+$/g, '').substring(0, maxChars);
}

function oneLine(v, maxChars) {
  return clean(str(v).replace(/[\r\n\t]+/g, ' '), maxChars);
}

// Returns a fully valid settings object built from possibly-partial input.
function validate(input) {
  var d = defaults();
  var s = (input && typeof input === 'object') ? input : {};
  var out = {};
  out.apiKey = str(s.apiKey).replace(/\s+/g, '');
  out.model = indexOf(MODELS, s.model) >= 0 ? s.model : d.model;
  out.length = indexOf(LENGTHS, s.length) >= 0 ? s.length : d.length;
  out.webSearch = (typeof s.webSearch === 'boolean') ? s.webSearch : d.webSearch;
  out.units = indexOf(UNITS, s.units) >= 0 ? s.units : d.units;
  out.city = oneLine(s.city, MAX_CITY);
  out.aboutMe = clean(s.aboutMe, MAX_ABOUT);
  if (Object.prototype.toString.call(s.quickPrompts) === '[object Array]') {
    var list = [];
    for (var i = 0; i < s.quickPrompts.length && list.length < MAX_PROMPTS; i++) {
      var q = s.quickPrompts[i];
      if (!q || typeof q !== 'object') { continue; }
      var label = text.truncateBytes(text.sanitize(oneLine(q.label, MAX_LABEL)).replace(/\n/g, ' '), MAX_LABEL);
      var prompt = oneLine(q.prompt, MAX_PROMPT);
      if (label && prompt) { list.push({ label: label, prompt: prompt }); }
    }
    out.quickPrompts = list;
  } else {
    out.quickPrompts = d.quickPrompts;
  }
  return out;
}

function getStorage() {
  try {
    if (typeof localStorage !== 'undefined') { return localStorage; }
  } catch (e) { /* ignore */ }
  return null;
}

function load(storage) {
  var st = storage || getStorage();
  var raw = null;
  try { raw = st ? st.getItem(STORAGE_KEY) : null; } catch (e) { raw = null; }
  if (!raw) { return defaults(); }
  try {
    return validate(JSON.parse(raw));
  } catch (e2) {
    return defaults();
  }
}

function save(settings, storage) {
  var st = storage || getStorage();
  if (!st) { return false; }
  try {
    st.setItem(STORAGE_KEY, JSON.stringify(validate(settings)));
    return true;
  } catch (e) {
    return false;
  }
}

// Merge the string returned by the config page into existing settings.
function applyConfigResponse(settings, responseString) {
  var old = validate(settings);
  if (!responseString || typeof responseString !== 'string') { return old; }
  var r;
  try {
    r = JSON.parse(decodeURIComponentSafe(responseString));
  } catch (e) {
    return old;
  }
  if (!r || typeof r !== 'object') { return old; }
  var merged = {};
  for (var k in r) {
    if (r.hasOwnProperty(k)) { merged[k] = r[k]; }
  }
  var newKey = str(r.apiKey).replace(/\s+/g, '');
  if (r.clearKey === true) {
    merged.apiKey = '';
  } else if (newKey) {
    merged.apiKey = newKey;
  } else {
    merged.apiKey = old.apiKey;
  }
  return validate(merged);
}

function decodeURIComponentSafe(s) {
  // Pebble may hand us the raw JSON or a URI-encoded version of it.
  var t = s.replace(/^\s+/, '');
  if (t.charAt(0) === '{') { return s; }
  try { return decodeURIComponent(s); } catch (e) { return s; }
}

var PAGE_TEMPLATE = [
  '<!DOCTYPE html>',
  '<html lang="en"><head><meta charset="utf-8">',
  '<meta name="viewport" content="width=device-width, initial-scale=1">',
  '<meta name="color-scheme" content="light dark">',
  '<title>Wrist Assistant</title>',
  '<style>',
  ':root{--bg:#f5f3ef;--card:#fff;--fg:#1f1d1a;--muted:#6b6660;--line:#d9d4cc;--accent:#c4592f;--accent-fg:#fff}',
  '@media (prefers-color-scheme:dark){:root{--bg:#181715;--card:#242220;--fg:#f0ece6;--muted:#a39d94;--line:#3a3733;--accent:#e0794d;--accent-fg:#1a1410}}',
  '*{box-sizing:border-box}',
  'body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:16px/1.4 -apple-system,Roboto,"Helvetica Neue",Arial,sans-serif}',
  'h1{font-size:22px;margin:4px 0 4px}',
  'h2{font-size:13px;letter-spacing:.06em;text-transform:uppercase;color:var(--muted);margin:22px 0 8px}',
  '.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px}',
  'label{display:block;font-weight:600;margin:12px 0 4px}',
  'label:first-child{margin-top:0}',
  '.hint{color:var(--muted);font-size:13px;margin:4px 0 0}',
  'input[type=text],input[type=password],select,textarea{width:100%;font:inherit;color:var(--fg);background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:10px}',
  'textarea{min-height:84px;resize:vertical}',
  '.row{display:flex;align-items:center;gap:10px}',
  '.row input[type=checkbox]{width:22px;height:22px;flex:none}',
  '.row label{margin:0;font-weight:500}',
  '.qp{border-top:1px solid var(--line);padding-top:10px;margin-top:10px}',
  '.qp:first-of-type{border-top:0;padding-top:0;margin-top:0}',
  '.count{float:right;font-weight:400;color:var(--muted);font-size:12px}',
  '.btns{display:flex;gap:10px;margin:22px 0 8px}',
  'button{flex:1;font:inherit;font-weight:600;padding:13px;border-radius:10px;border:1px solid var(--line);background:var(--card);color:var(--fg)}',
  'button.primary{background:var(--accent);border-color:var(--accent);color:var(--accent-fg)}',
  '.foot{color:var(--muted);font-size:12px;text-align:center;margin:8px 0 24px}',
  '</style></head><body>',
  '<h1>Wrist Assistant</h1>',
  '<p class="hint">Your key is stored only on this phone and sent only to api.anthropic.com.</p>',
  '<h2>Account</h2><div class="card">',
  '<label for="apiKey">Anthropic API key</label>',
  '<input id="apiKey" type="password" autocomplete="off" autocapitalize="off" spellcheck="false">',
  '<p class="hint" id="keyHint">Get a key at console.anthropic.com</p>',
  '<div class="row" style="margin-top:10px"><input id="clearKey" type="checkbox"><label for="clearKey">Clear saved key</label></div>',
  '</div>',
  '<h2>Answers</h2><div class="card">',
  '<label for="model">Model</label>',
  '<select id="model">',
  '<option value="claude-haiku-5-5">Claude Haiku 5.5 - fastest/cheapest</option>',
  '<option value="claude-sonnet-5-5">Claude Sonnet 5.5</option>',
  '<option value="claude-opus-5-5">Claude Opus 5.5</option>',
  '</select>',
  '<label for="length">Answer length</label>',
  '<select id="length">',
  '<option value="short">Short (about 40 words)</option>',
  '<option value="medium">Medium (about 80 words)</option>',
  '<option value="long">Long (about 150 words)</option>',
  '</select>',
  '<div class="row" style="margin-top:14px"><input id="webSearch" type="checkbox"><label for="webSearch">Web search for current info</label></div>',
  '<label for="units">Units</label>',
  '<select id="units"><option value="imperial">Imperial (F, miles)</option><option value="metric">Metric (C, km)</option></select>',
  '</div>',
  '<h2>About you</h2><div class="card">',
  '<label for="city">City</label>',
  '<input id="city" type="text" maxlength="60" placeholder="Optional, for local answers">',
  '<label for="aboutMe">About me <span class="count" id="aboutCount"></span></label>',
  '<textarea id="aboutMe" maxlength="300" placeholder="Optional. Anything Claude should know when answering."></textarea>',
  '</div>',
  '<h2>Quick prompts</h2><div class="card" id="qps"></div>',
  '<p class="hint">Up to 5. Leave a row empty to remove it. Label up to 24 characters.</p>',
  '<div class="btns"><button id="cancel" type="button">Cancel</button><button id="save" type="button" class="primary">Save</button></div>',
  '<div class="foot">Wrist Assistant __VERSION__</div>',
  '<script>',
  'var S=__SETTINGS__;',
  'function $(i){return document.getElementById(i);}',
  'var qps=$("qps");',
  'for(var i=0;i<5;i++){',
  ' var q=S.quickPrompts[i]||{label:"",prompt:""};',
  ' var d=document.createElement("div");d.className="qp";',
  ' d.innerHTML="<label>Label</label><input type=\\"text\\" maxlength=\\"24\\" class=\\"ql\\"><label>Prompt</label><input type=\\"text\\" maxlength=\\"200\\" class=\\"qt\\">";',
  ' qps.appendChild(d);',
  ' d.getElementsByClassName("ql")[0].value=q.label;',
  ' d.getElementsByClassName("qt")[0].value=q.prompt;',
  '}',
  '$("model").value=S.model;$("length").value=S.length;$("units").value=S.units;',
  '$("webSearch").checked=!!S.webSearch;$("city").value=S.city;$("aboutMe").value=S.aboutMe;',
  'if(S.hasKey){$("apiKey").placeholder="Key saved (leave empty to keep)";$("keyHint").textContent="A key is saved on this phone. Get a new one at console.anthropic.com";}',
  'else{$("apiKey").placeholder="sk-ant-...";$("clearKey").parentNode.style.display="none";}',
  'function cnt(){$("aboutCount").textContent=$("aboutMe").value.length+" / 300";}',
  '$("aboutMe").oninput=cnt;cnt();',
  '$("cancel").onclick=function(){document.location="pebblejs://close";};',
  '$("save").onclick=function(){',
  ' var ls=document.getElementsByClassName("ql"),ts=document.getElementsByClassName("qt"),qp=[];',
  ' for(var j=0;j<ls.length;j++){qp.push({label:ls[j].value,prompt:ts[j].value});}',
  ' var v={apiKey:$("apiKey").value,clearKey:$("clearKey").checked,model:$("model").value,length:$("length").value,',
  ' webSearch:$("webSearch").checked,units:$("units").value,city:$("city").value,aboutMe:$("aboutMe").value,quickPrompts:qp};',
  ' document.location="pebblejs://close#"+encodeURIComponent(JSON.stringify(v));',
  '};',
  '</script></body></html>'
].join('\n');

function buildConfigUrl(settings) {
  var s = validate(settings);
  var view = {
    hasKey: s.apiKey.length > 0,
    model: s.model,
    length: s.length,
    webSearch: s.webSearch,
    units: s.units,
    city: s.city,
    aboutMe: s.aboutMe,
    quickPrompts: s.quickPrompts
  };
  var json = JSON.stringify(view).replace(/</g, '\\u003c').replace(/>/g, '\\u003e')
    .replace(new RegExp(String.fromCharCode(0x2028), 'g'), '\\u2028')
    .replace(new RegExp(String.fromCharCode(0x2029), 'g'), '\\u2029');
  var version = require('./version');
  var html = PAGE_TEMPLATE
    .replace('__VERSION__', function () { return version; })
    .replace('__SETTINGS__', function () { return json; });
  return 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
}

module.exports = {
  STORAGE_KEY: STORAGE_KEY,
  defaults: defaults,
  validate: validate,
  load: load,
  save: save,
  buildConfigUrl: buildConfigUrl,
  applyConfigResponse: applyConfigResponse
};
