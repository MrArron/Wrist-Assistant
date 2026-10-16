## What changed

<!-- One or two sentences. -->

## How it was tested

- [ ] `pebble build` + `python3 tools/watch_size.py --platform emery` (paste the first lines below)
- [ ] `for f in test/pkjs/*.test.js; do node $f; done`
- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug`
- [ ] Emulator / real watch / real phone (say which)

```
(size report here, if the watch app changed)
```

## Not verified

<!-- What you could not test. -->

## Checklist

- [ ] No API keys, tokens, keystores or personal messages in the diff, logs or screenshots
- [ ] Anything sent to another person is still confirmed on the watch first
- [ ] pkjs code is ES5 only
- [ ] Pebble UUID, message keys, `applicationId` and prefs keys unchanged
- [ ] Docs updated if behaviour or protocol changed
