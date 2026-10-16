#include "common.h"

static char s_quick_names[QUICK_NAMES_SIZE];
static bool s_key_set;
static bool s_ask_in_flight;
static bool s_companion;   // per launch: set once the companion says hello

static void inbox_received(DictionaryIterator *iter, void *context) {
  Tuple *t;
  Tuple *comp = dict_find(iter, MESSAGE_KEY_COMPANION);
  if (comp && comp->value->int32 == 1) s_companion = true;
  // Once the companion is here, its settings win over the Pebble-app JS
  bool settings_ok = !s_companion || comp;

  if ((t = dict_find(iter, MESSAGE_KEY_CONFIRM_TEXT))) {
    Tuple *id = dict_find(iter, MESSAGE_KEY_CONFIRM_ID);
    if (id) confirm_win_show(id->value->int32, t->value->cstring);
  }

  if (settings_ok && (t = dict_find(iter, MESSAGE_KEY_READY))) {
    s_key_set = t->value->int32 != 0;
    persist_write_int(PK_KEY_SET, s_key_set ? 1 : 0);
  }
  if (settings_ok && (t = dict_find(iter, MESSAGE_KEY_QUICK_NAMES))) {
    u_copy(s_quick_names, t->value->cstring, sizeof(s_quick_names));
    persist_write_string(PK_QUICK_NAMES, s_quick_names);
  }
  if (comp || dict_find(iter, MESSAGE_KEY_READY) || dict_find(iter, MESSAGE_KEY_QUICK_NAMES)) {
    menu_win_reload();
  }

  Tuple *rt = dict_find(iter, MESSAGE_KEY_REMINDER_TIME);
  if (rt) {
    Tuple *rx = dict_find(iter, MESSAGE_KEY_REMINDER_TEXT);
    reminders_add((time_t)rt->value->int32, rx ? rx->value->cstring : "Reminder");
  }

  if ((t = dict_find(iter, MESSAGE_KEY_STATUS))) {
    answer_win_on_status(t->value->cstring);
  }
  Tuple *chunk = dict_find(iter, MESSAGE_KEY_CHUNK);
  Tuple *fin = dict_find(iter, MESSAGE_KEY_CHUNK_FINAL);
  if (chunk || fin) {
    answer_win_on_chunk(chunk ? chunk->value->cstring : NULL, fin && fin->value->int32 != 0);
  }
  if ((t = dict_find(iter, MESSAGE_KEY_ERROR))) {
    answer_win_on_error(t->value->cstring);
  }
}

static void outbox_sent(DictionaryIterator *iter, void *context) { s_ask_in_flight = false; }

static void outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "outbox failed %d", (int)reason);
  if (s_ask_in_flight) {
    s_ask_in_flight = false;
    answer_win_on_error("Couldn't reach your phone. Try again.");
  }
}

void comm_init(void) {
  if (persist_exists(PK_QUICK_NAMES)) {
    persist_read_string(PK_QUICK_NAMES, s_quick_names, sizeof(s_quick_names));
  }
  s_key_set = persist_exists(PK_KEY_SET) ? persist_read_int(PK_KEY_SET) != 0 : true;

  app_message_register_inbox_received(inbox_received);
  app_message_register_outbox_failed(outbox_failed);
  app_message_register_outbox_sent(outbox_sent);
  app_message_open(1024, 768);
}

bool comm_send_ask(AskKind kind, const char *text, int quick_index) {
  if (!connection_service_peek_pebble_app_connection()) return false;
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return false;
  dict_write_int32(out, MESSAGE_KEY_ASK_KIND, (int32_t)kind);
  if (text) dict_write_cstring(out, MESSAGE_KEY_ASK_TEXT, text);
  if (kind == ASK_QUICK) dict_write_int32(out, MESSAGE_KEY_QUICK_INDEX, quick_index);
  dict_write_int32(out, MESSAGE_KEY_ROUTE, s_companion ? 1 : 0);
  s_ask_in_flight = app_message_outbox_send() == APP_MSG_OK;
  return s_ask_in_flight;
}

bool comm_send_speak(bool on) {
  if (!s_companion || !connection_service_peek_pebble_app_connection()) return false;
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return false;
  dict_write_int32(out, MESSAGE_KEY_SPEAK, on ? 1 : 0);
  dict_write_int32(out, MESSAGE_KEY_ROUTE, 1);
  return app_message_outbox_send() == APP_MSG_OK;
}

// Retried a few times: the companion is waiting on this answer.
static int32_t s_confirm_id;
static bool s_confirm_ok;
static int s_confirm_tries;
static void confirm_retry(void *ctx) {
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) {
    if (++s_confirm_tries < 5) app_timer_register(400, confirm_retry, NULL);
    return;
  }
  dict_write_int32(out, MESSAGE_KEY_CONFIRM_ID, s_confirm_id);
  dict_write_int32(out, MESSAGE_KEY_CONFIRM_RESULT, s_confirm_ok ? 1 : 0);
  dict_write_int32(out, MESSAGE_KEY_ROUTE, 1);
  if (app_message_outbox_send() != APP_MSG_OK && ++s_confirm_tries < 5) {
    app_timer_register(400, confirm_retry, NULL);
  }
}

void comm_send_confirm(int32_t id, bool approved) {
  s_confirm_id = id;
  s_confirm_ok = approved;
  s_confirm_tries = 0;
  confirm_retry(NULL);
}

bool comm_companion(void) { return s_companion; }

void comm_send_ready(void) {
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return;
  dict_write_int32(out, MESSAGE_KEY_READY, 1);
  app_message_outbox_send();
}

bool comm_key_set(void) { return s_key_set; }
const char *comm_quick_names(void) { return s_quick_names; }

int comm_quick_count(void) {
  if (!s_quick_names[0]) return 0;
  int n = 1;
  for (int i = 0; s_quick_names[i]; i++) {
    if (s_quick_names[i] == '\n' && s_quick_names[i + 1]) n++;
  }
  return n > QUICK_MAX ? QUICK_MAX : n;
}

void comm_quick_name(int index, char *out, int out_size) {
  int cur = 0, o = 0;
  out[0] = '\0';
  for (int i = 0; s_quick_names[i]; i++) {
    char c = s_quick_names[i];
    if (c == '\n') { if (cur == index) break; cur++; continue; }
    if (cur == index && o < out_size - 1) out[o++] = c;
  }
  out[o] = '\0';
}
