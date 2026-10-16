var test = require('node:test');
var assert = require('node:assert');
var claude = require('../../src/pkjs/claude');

test('reminderTime minutes_from_now', function () {
  var now = new Date(2026, 9, 14, 12, 0, 0);
  var s = Math.floor(now.getTime() / 1000);
  assert.strictEqual(claude.reminderTime({ minutes_from_now: 20 }, now), s + 1200);
  assert.strictEqual(claude.reminderTime({ minutes_from_now: 0 }, now), s + 60);
  assert.strictEqual(claude.reminderTime({ minutes_from_now: 99999 }, now), s + 7 * 86400);
  assert.strictEqual(claude.reminderTime({}, now), null);
});

test('reminderTime at_local_time today and rolls to tomorrow', function () {
  var now = new Date(2026, 9, 14, 12, 0, 0);
  var today = new Date(2026, 9, 14, 15, 30, 0);
  assert.strictEqual(claude.reminderTime({ at_local_time: '15:30' }, now), Math.floor(today.getTime() / 1000));
  var tomorrow = new Date(2026, 9, 15, 9, 5, 0);
  assert.strictEqual(claude.reminderTime({ at_local_time: '09:05' }, now), Math.floor(tomorrow.getTime() / 1000));
  var same = new Date(2026, 9, 15, 12, 0, 0);
  assert.strictEqual(claude.reminderTime({ at_local_time: '12:00' }, now), Math.floor(same.getTime() / 1000));
  assert.strictEqual(claude.reminderTime({ at_local_time: '25:00' }, now), null);
  assert.strictEqual(claude.reminderTime({ at_local_time: 'noon' }, now), null);
});

test('ask runs the reminder tool loop with a fake transport', function (t, done) {
  var calls = 0;
  var bodies = [];
  claude.setTransport(function (key, body, cb) {
    calls++;
    bodies.push(body);
    if (calls === 1) {
      cb({ status: 200, netError: false, json: { stop_reason: 'tool_use', content: [
        { type: 'text', text: 'On it.' },
        { type: 'tool_use', id: 't1', name: 'set_reminder', input: { minutes_from_now: 20, text: 'Check the oven' } }
      ] } });
    } else {
      cb({ status: 200, netError: false, json: { stop_reason: 'end_turn', content: [{ type: 'text', text: '**Done.** Reminder set.' }] } });
    }
  });
  var reminders = [];
  var statuses = [];
  var hist = [{ role: 'user', content: 'hi' }, { role: 'assistant', content: 'hello' }];
  claude.ask({ apiKey: 'k', model: 'claude-haiku-5-5', length: 'short', webSearch: true, units: 'metric', city: 'Springfield', aboutMe: '' },
    hist, 'remind me', {
      onStatus: function (s) { statuses.push(s); },
      onReminder: function (u, x) { reminders.push([u, x]); },
      onDone: function (a) {
        claude.setTransport(null);
        assert.strictEqual(a, 'Done. Reminder set.');
        assert.strictEqual(reminders.length, 1);
        assert.strictEqual(reminders[0][1], 'Check the oven');
        assert.strictEqual(statuses[0], 'Thinking...');
        assert.strictEqual(hist.length, 2);
        assert.strictEqual(bodies[1].messages.length, 5);
        assert.strictEqual(bodies[0].tools[1].user_location.city, 'Springfield');
        assert.strictEqual(bodies[0].tools[1].user_location.country, undefined);
        done();
      },
      onError: function (e) { claude.setTransport(null); done(new Error(e)); }
    });
});

test('ask maps errors', function (t, done) {
  var seen = [];
  function run(res, expected, next) {
    claude.setTransport(function (k, b, cb) { cb(res); });
    claude.ask({ apiKey: 'k', model: 'm', length: 'short', units: 'imperial' }, [], 'q', {
      onError: function (e) { assert.strictEqual(e, expected); next(); },
      onDone: function () { done(new Error('unexpected success')); }
    });
  }
  run({ status: 401, json: null }, 'API key rejected. Check it in settings.', function () {
    run({ status: 529, json: null }, 'Claude is busy. Try again in a moment.', function () {
      run({ status: 0, netError: true }, "No connection to Claude. Check your phone's internet.", function () {
        run({ status: 400, json: { error: { message: 'bad thing' } } }, 'Error 400: bad thing', function () {
          claude.setTransport(null);
          claude.ask({ apiKey: '' }, [], 'q', { onError: function (e) {
            assert.strictEqual(e, 'Add your API key in the Pebble app settings for Wrist Assistant.');
            done();
          } });
        });
      });
    });
  });
});
