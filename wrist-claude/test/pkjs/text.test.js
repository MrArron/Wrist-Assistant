var test = require('node:test');
var assert = require('node:assert');
var text = require('../../src/pkjs/text');

test('sanitize strips markdown', function () {
  var s = text.sanitize('# Title\n\n**Bold** and *italic* and `code`.\n\n- one\n* two\n[link](http://x.y) end');
  assert.strictEqual(s, 'Title\n\nBold and italic and code.\n\n- one\n- two\nlink end');
});

test('sanitize replaces smart punctuation and nbsp', function () {
  var q = String.fromCharCode(0x201C) + 'Hi' + String.fromCharCode(0x201D) + ' it' + String.fromCharCode(0x2019) + 's' +
    String.fromCharCode(0x2013) + 'ok' + String.fromCharCode(0x2026) + String.fromCharCode(0xA0) + 'x';
  assert.strictEqual(text.sanitize(q), '"Hi" it\'s-ok... x');
});

test('sanitize removes emoji and non-Latin-1 but keeps Latin-1', function () {
  var emoji = String.fromCharCode(0xD83D, 0xDE00);
  var s = text.sanitize('Hot ' + emoji + ' 72' + String.fromCharCode(0xB0) + 'F caf' + String.fromCharCode(0xE9) + ' ' + String.fromCharCode(0x4E2D));
  assert.strictEqual(s, 'Hot 72' + String.fromCharCode(0xB0) + 'F caf' + String.fromCharCode(0xE9));
});

test('sanitize collapses newlines and removes control chars', function () {
  assert.strictEqual(text.sanitize('a\n\n\n\nb' + String.fromCharCode(1, 7)), 'a\n\nb');
});

test('utf8Bytes counts multibyte and surrogate pairs', function () {
  assert.strictEqual(text.utf8Bytes('abc'), 3);
  assert.strictEqual(text.utf8Bytes(String.fromCharCode(0xE9)), 2);
  assert.strictEqual(text.utf8Bytes(String.fromCharCode(0x4E2D)), 3);
  assert.strictEqual(text.utf8Bytes(String.fromCharCode(0xD83D, 0xDE00)), 4);
});

test('truncateBytes adds ellipsis within limit', function () {
  var long = new Array(50).join('word ');
  var t = text.truncateBytes(long, 20);
  assert.ok(text.utf8Bytes(t) <= 20);
  assert.ok(/\.\.\.$/.test(t));
  assert.strictEqual(text.truncateBytes('short', 20), 'short');
  var mb = new Array(20).join(String.fromCharCode(0xE9));
  var t2 = text.truncateBytes(mb, 11);
  assert.ok(text.utf8Bytes(t2) <= 11);
  assert.strictEqual(Buffer.from(t2, 'utf8').toString('utf8'), t2);
});

test('chunk respects byte limit, round-trips, never splits characters', function () {
  var e = String.fromCharCode(0xE9);
  var src = '';
  for (var i = 0; i < 120; i++) { src += 'caf' + e + ' word' + i + ' ' + String.fromCharCode(0x4E2D) + ' '; }
  var parts = text.chunk(src, 180);
  assert.ok(parts.length > 1);
  parts.forEach(function (p) {
    assert.ok(Buffer.byteLength(p, 'utf8') <= 180);
    assert.strictEqual(Buffer.from(p, 'utf8').toString('utf8'), p);
  });
  assert.strictEqual(parts.join(''), src);
  // prefers breaking at spaces
  assert.ok(/ $/.test(parts[0]));
});

test('chunk handles unbroken text, surrogates and short input', function () {
  var emoji = String.fromCharCode(0xD83D, 0xDE00);
  var src = '';
  for (var i = 0; i < 100; i++) { src += emoji; }
  var parts = text.chunk(src, 10);
  parts.forEach(function (p) { assert.ok(Buffer.byteLength(p, 'utf8') <= 10); });
  assert.strictEqual(parts.join(''), src);
  assert.deepStrictEqual(text.chunk('hello', 180), ['hello']);
  assert.deepStrictEqual(text.chunk('', 180), []);
  var nospace = new Array(401).join('x');
  assert.strictEqual(text.chunk(nospace, 180).join(''), nospace);
});
