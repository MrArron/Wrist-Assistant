var test = require('node:test');
var assert = require('node:assert');
var settings = require('../../src/pkjs/settings');

function resp(o) { return encodeURIComponent(JSON.stringify(o)); }

test('defaults', function () {
  var d = settings.defaults();
  assert.strictEqual(d.model, 'claude-haiku-5-5');
  assert.strictEqual(d.webSearch, true);
  assert.strictEqual(d.quickPrompts.length, 3);
  assert.strictEqual(d.quickPrompts[0].label, 'Fun fact');
});

test('empty apiKey keeps the old key', function () {
  var old = settings.validate({ apiKey: 'sk-ant-old' });
  var n = settings.applyConfigResponse(old, resp({ apiKey: '', model: 'claude-sonnet-5-5' }));
  assert.strictEqual(n.apiKey, 'sk-ant-old');
  assert.strictEqual(n.model, 'claude-sonnet-5-5');
});

test('new key replaces, clear flag wins', function () {
  var old = settings.validate({ apiKey: 'sk-ant-old' });
  assert.strictEqual(settings.applyConfigResponse(old, resp({ apiKey: ' sk-ant-new ' })).apiKey, 'sk-ant-new');
  assert.strictEqual(settings.applyConfigResponse(old, resp({ apiKey: 'sk-ant-new', clearKey: true })).apiKey, '');
  assert.strictEqual(settings.applyConfigResponse(old, resp({ clearKey: true })).apiKey, '');
});

test('clamping and validation', function () {
  var old = settings.defaults();
  var prompts = [];
  for (var i = 0; i < 8; i++) { prompts.push({ label: new Array(40).join('L'), prompt: new Array(300).join('p') }); }
  prompts.push({ label: '', prompt: 'no label' });
  var n = settings.applyConfigResponse(old, resp({
    model: 'bogus', length: 'huge', units: 'furlongs', webSearch: 'yes',
    city: 'Springfield', aboutMe: new Array(500).join('a'), quickPrompts: prompts
  }));
  assert.strictEqual(n.model, 'claude-haiku-5-5');
  assert.strictEqual(n.length, 'short');
  assert.strictEqual(n.units, 'imperial');
  assert.strictEqual(n.webSearch, true);
  assert.strictEqual(n.aboutMe.length, 300);
  assert.strictEqual(n.quickPrompts.length, 5);
  assert.ok(n.quickPrompts[0].label.length <= 24);
  assert.ok(n.quickPrompts[0].prompt.length <= 200);
});

test('empty quick prompt rows are dropped; garbage response keeps old', function () {
  var old = settings.validate({ apiKey: 'k', city: 'Here' });
  var n = settings.applyConfigResponse(old, resp({ quickPrompts: [{ label: '', prompt: '' }, { label: 'A', prompt: 'B' }] }));
  assert.deepStrictEqual(n.quickPrompts, [{ label: 'A', prompt: 'B' }]);
  assert.strictEqual(settings.applyConfigResponse(old, 'not json').apiKey, 'k');
  assert.strictEqual(settings.applyConfigResponse(old, '').city, 'Here');
  assert.strictEqual(settings.applyConfigResponse(old, undefined).city, 'Here');
});

test('load/save round trip with fake storage', function () {
  var store = {};
  var fake = { getItem: function (k) { return store.hasOwnProperty(k) ? store[k] : null; }, setItem: function (k, v) { store[k] = v; } };
  assert.strictEqual(settings.load(fake).model, 'claude-haiku-5-5');
  settings.save({ apiKey: 'abc', model: 'claude-opus-5-5' }, fake);
  assert.ok(store.wc_settings);
  assert.strictEqual(settings.load(fake).model, 'claude-opus-5-5');
  assert.strictEqual(settings.load(fake).apiKey, 'abc');
});

test('buildConfigUrl is a data URL that does not contain the api key', function () {
  var url = settings.buildConfigUrl(settings.validate({ apiKey: 'sk-ant-SECRET', aboutMe: '</script><b>x' }));
  assert.ok(url.indexOf('data:text/html;charset=utf-8,') === 0);
  var html = decodeURIComponent(url.substring('data:text/html;charset=utf-8,'.length));
  assert.ok(html.indexOf('SECRET') < 0);
  assert.ok(html.indexOf('</script><b>x') < 0);
  assert.ok(html.indexOf('pebblejs://close') > 0);
  // the inline script must parse
  var m = /<script>([\s\S]*)<\/script>/.exec(html);
  new Function(m[1]);
});
