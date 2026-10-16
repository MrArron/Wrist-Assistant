#include "common.h"

// strlen() faulted on real Pebble Time 2 hardware, so we use local helpers.
int u_len(const char *s) {
  int n = 0;
  if (!s) return 0;
  while (s[n]) n++;
  return n;
}

void u_copy(char *dst, const char *src, int dst_size) {
  if (!dst || dst_size <= 0) return;
  int i = 0;
  if (src) {
    while (src[i] && i < dst_size - 1) { dst[i] = src[i]; i++; }
  }
  // don't leave a split UTF-8 sequence at the end
  if (src && src[i] && i > 0) {
    int j = i;
    while (j > 0 && (((unsigned char)dst[j - 1]) & 0xC0) == 0x80) j--;
    if (j > 0 && (((unsigned char)dst[j - 1]) & 0xC0) == 0xC0) {
      unsigned char lead = (unsigned char)dst[j - 1];
      int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : 2;
      if (i - (j - 1) < need) i = j - 1;   // only drop an incomplete character
    }
  }
  dst[i] = '\0';
}

void u_append(char *dst, const char *src, int dst_size) {
  int n = u_len(dst);
  if (n >= dst_size - 1) return;
  u_copy(dst + n, src, dst_size - n);
}

void u_time_str(time_t t, char *out, int out_size) {
  // localtime() returns a shared static buffer, so copy each result before the next call
  struct tm t_tm = *localtime(&t);
  struct tm *lt = &t_tm;
  time_t now = time(NULL);
  struct tm now_tm = *localtime(&now);
  bool same_day = lt->tm_yday == now_tm.tm_yday && lt->tm_year == now_tm.tm_year;
  if (clock_is_24h_style()) {
    strftime(out, out_size, same_day ? "%H:%M" : "%a %H:%M", lt);
  } else {
    strftime(out, out_size, same_day ? "%l:%M %p" : "%a %l:%M %p", lt);
  }
  // trim the leading space %l leaves
  if (out[0] == ' ') {
    int i = 0;
    while (out[i + 1]) { out[i] = out[i + 1]; i++; }
    out[i] = '\0';
  }
}
