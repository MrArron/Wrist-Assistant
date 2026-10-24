var test = require('node:test');
var assert = require('node:assert');
var topics = require('../../src/pkjs/topics');
var settings = require('../../src/pkjs/settings');

function seq(values) { var i = 0; return function () { return values[i++ % values.length]; }; }

test('word list is big and has no duplicates or separators', function () {
  assert.ok(topics.WORDS.length >= 800);
  var seen = {};
  for (var i = 0; i < topics.WORDS.length; i++) {
    var w = topics.WORDS[i].toLowerCase();
    assert.ok(w.length > 0 && w.indexOf('|') < 0, 'bad word at ' + i);
    assert.ok(!seen[w], 'duplicate ' + w);
    seen[w] = true;
  }
  assert.ok(topics.WORDS.length * topics.ANGLES.length >= 10000);
});

test('prompts without {topic} are unchanged', function () {
  assert.strictEqual(topics.expand('Motivate me', ['x']), 'Motivate me');
  assert.strictEqual(topics.hasTopic('Motivate me'), false);
});

test('expand fills the word and an angle', function () {
  var out = topics.expand('Fun fact about {topic}.', null, seq([0, 0]));
  assert.strictEqual(out.indexOf('Fun fact about ' + topics.WORDS[0] + '. ' + topics.ANGLES[0]), 0);
  assert.ok(out.indexOf('{topic}') < 0);
  assert.ok(out.indexOf('repeat') < 0);
  // rand() at the top of its range still picks a real entry
  var last = topics.expand('{topic}', null, seq([0.9999999999]));
  assert.strictEqual(last.indexOf(topics.WORDS[topics.WORDS.length - 1]), 0);
});

test('expand varies with the random source', function () {
  var seen = {};
  for (var i = 0; i < 50; i++) { seen[topics.expand('{topic}', null)] = true; }
  assert.ok(Object.keys(seen).length > 40);
});

test('recent answers are listed to avoid, newest five kept', function () {
  var r = [];
  for (var i = 1; i <= 7; i++) { r = topics.remember(r, 'fact ' + i); }
  assert.deepStrictEqual(r, ['fact 3', 'fact 4', 'fact 5', 'fact 6', 'fact 7']);
  var out = topics.expand('{topic}', ['Honey\nnever spoils.', new Array(300).join('z')], seq([0]));
  assert.ok(out.indexOf('Do not repeat any of these: "Honey never spoils."') > 0);
  assert.ok(out.length < 600);
});

test('default fun fact uses {topic}; the old default is upgraded', function () {
  assert.ok(topics.hasTopic(settings.defaults().quickPrompts[0].prompt));
  var s = settings.validate({ quickPrompts: [{ label: 'Fun fact', prompt: 'Tell me a surprising fun fact.' }, { label: 'Mine', prompt: 'Tell me a fun fact.' }] });
  assert.strictEqual(s.quickPrompts[0].prompt, 'Tell me a surprising fun fact about {topic}.');
  assert.strictEqual(s.quickPrompts[1].prompt, 'Tell me a fun fact.');
});
