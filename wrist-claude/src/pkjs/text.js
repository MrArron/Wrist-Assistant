// Pure text helpers for Wrist Assistant. No Pebble globals. ES5 only.

var fromCode = String.fromCharCode;

// Replacement table for characters the watch fonts lack (or that we normalise).
var MAP = {};
(function () {
  function put(codes, rep) {
    for (var i = 0; i < codes.length; i++) { MAP[codes[i]] = rep; }
  }
  put([0x2018, 0x2019, 0x201A, 0x201B, 0x2032, 0x02BC], "'");
  put([0x201C, 0x201D, 0x201E, 0x201F, 0x2033], '"');
  put([0x2010, 0x2011, 0x2012, 0x2013, 0x2212], '-');
  put([0x2014, 0x2015], '-');
  put([0x2026], '...');
  put([0x2022, 0x25CF, 0x25AA, 0x2023], '-');
  put([0x2192], '->');
  put([0x2190], '<-');
  put([0x00A0, 0x2002, 0x2003, 0x2009, 0x200A, 0x202F, 0x2007, 0x3000], ' ');
  put([0x00AD, 0x200B, 0x200C, 0x200D, 0xFEFF, 0x2060], '');
})();

function stripMarkdown(s) {
  s = s.replace(/\r\n?/g, '\n');
  s = s.replace(/```[A-Za-z0-9_+-]*\n?/g, '');
  s = s.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1');
  s = s.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1');
  s = s.replace(/^[ \t]{0,3}#{1,6}[ \t]+/gm, '');
  s = s.replace(/^[ \t]*([-*_])([ \t]*\1){2,}[ \t]*$/gm, '');
  s = s.replace(/^[ \t]*[-*+][ \t]+/gm, '- ');
  s = s.replace(/^[ \t]*>[ \t]?/gm, '');
  s = s.replace(/(\*\*|__)(.+?)\1/g, '$2');
  s = s.replace(/\*([^*\n]+)\*/g, '$1');
  s = s.replace(/(^|[^A-Za-z0-9_])_([^_\n]+)_(?![A-Za-z0-9_])/g, '$1$2');
  s = s.replace(/`([^`\n]*)`/g, '$1');
  s = s.replace(/`/g, '');
  return s;
}

function sanitize(str) {
  if (str === null || str === undefined) { return ''; }
  var s = stripMarkdown(String(str));
  var out = '';
  for (var i = 0; i < s.length; i++) {
    var c = s.charCodeAt(i);
    if (MAP.hasOwnProperty(c)) {
      out += MAP[c];
    } else if (c === 10 || c === 9 || (c >= 0x20 && c <= 0x7E) || (c >= 0xA1 && c <= 0xFF)) {
      out += fromCode(c);
    }
    // everything else (controls, emoji, surrogates, non-Latin-1) is dropped
  }
  out = out.replace(/[ \t]+\n/g, '\n');
  out = out.replace(/ {2,}/g, ' ');
  out = out.replace(/\n{3,}/g, '\n\n');
  return out.replace(/^\s+|\s+$/g, '');
}

// Number of UTF-8 bytes for the code point starting at index i, and its length in UTF-16 units.
function cpInfo(str, i) {
  var c = str.charCodeAt(i);
  if (c < 0x80) { return { bytes: 1, units: 1 }; }
  if (c < 0x800) { return { bytes: 2, units: 1 }; }
  if (c >= 0xD800 && c <= 0xDBFF && i + 1 < str.length) {
    var d = str.charCodeAt(i + 1);
    if (d >= 0xDC00 && d <= 0xDFFF) { return { bytes: 4, units: 2 }; }
  }
  return { bytes: 3, units: 1 };
}

function utf8Bytes(str) {
  str = String(str);
  var n = 0;
  var i = 0;
  while (i < str.length) {
    var info = cpInfo(str, i);
    n += info.bytes;
    i += info.units;
  }
  return n;
}

// Longest prefix (in UTF-16 units) that fits in maxBytes.
function prefixUnits(str, maxBytes) {
  var n = 0;
  var i = 0;
  while (i < str.length) {
    var info = cpInfo(str, i);
    if (n + info.bytes > maxBytes) { break; }
    n += info.bytes;
    i += info.units;
  }
  return i;
}

function truncateBytes(str, maxBytes) {
  str = String(str);
  if (utf8Bytes(str) <= maxBytes) { return str; }
  if (maxBytes <= 3) { return '...'.substring(0, Math.max(0, maxBytes)); }
  var end = prefixUnits(str, maxBytes - 3);
  return str.substring(0, end).replace(/\s+$/, '') + '...';
}

// Split into pieces of at most maxBytes UTF-8 bytes. Prefers to break after a
// space or newline. Concatenating the pieces gives back the original string.
function chunk(str, maxBytes) {
  str = String(str);
  var out = [];
  var start = 0;
  while (start < str.length) {
    var rest = str.substring(start);
    if (utf8Bytes(rest) <= maxBytes) { out.push(rest); break; }
    var end = prefixUnits(rest, maxBytes);
    if (end <= 0) { end = rest.length >= 2 ? 2 : 1; } // maxBytes too small for one char; avoid infinite loop
    var cut = end;
    for (var j = end - 1; j > 0; j--) {
      var ch = rest.charAt(j);
      if (ch === ' ' || ch === '\n') { cut = j + 1; break; }
    }
    out.push(rest.substring(0, cut));
    start += cut;
  }
  return out;
}

module.exports = {
  sanitize: sanitize,
  utf8Bytes: utf8Bytes,
  truncateBytes: truncateBytes,
  chunk: chunk
};
