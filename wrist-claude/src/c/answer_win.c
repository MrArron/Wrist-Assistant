#include "common.h"

typedef enum { ST_IDLE, ST_DICTATING, ST_WAITING, ST_DONE, ST_ERROR } State;

static Window *s_window;
static ScrollLayer *s_scroll;
static TextLayer *s_q_layer, *s_a_layer, *s_hint_layer;
static Layer *s_bar;                 // thin accent bar at the top while waiting
static AppTimer *s_timeout, *s_anim;
static int s_dots;
#if defined(PBL_MICROPHONE)
static DictationSession *s_dictation;
#endif

static char s_question[QUESTION_BUF_SIZE];
static char *s_answer;               // heap, only while the window exists
static char s_status[48];
static State s_state;
static bool s_view_only;             // showing a saved history item
static bool s_live;                  // a conversation the phone remembers exists
static bool s_has_answer;            // that conversation has at least one answer
static AskKind s_pending_kind;
static int s_last_quick = -1;
static bool s_speaking;              // asked the phone to read the answer aloud
static AskKind s_sent_kind;          // kind of the ask in flight

#define MARGIN_X PBL_IF_ROUND_ELSE(26, 6)
#define MARGIN_TOP PBL_IF_ROUND_ELSE(22, 4)

static void layout(void);
static void start_dictation(AskKind kind);

// ---------- waiting animation ----------
static void anim_tick(void *ctx) {
  s_anim = NULL;
  if (s_state != ST_WAITING) return;
  s_dots = (s_dots + 1) % 4;
  static char buf[56];
  u_copy(buf, s_status, sizeof(buf));
  int n = u_len(buf);
  // drop any trailing dots the phone sent, then animate our own
  while (n > 0 && buf[n - 1] == '.') buf[--n] = '\0';
  for (int i = 0; i < s_dots && n < (int)sizeof(buf) - 1; i++) buf[n++] = '.';
  buf[n] = '\0';
  u_copy(s_answer, buf, ANSWER_BUF_SIZE);
  layout();
  s_anim = app_timer_register(400, anim_tick, NULL);
}

static void stop_timers(void) {
  if (s_timeout) { app_timer_cancel(s_timeout); s_timeout = NULL; }
  if (s_anim) { app_timer_cancel(s_anim); s_anim = NULL; }
}

static void show_error(const char *text) {
  stop_timers();
  s_state = ST_ERROR;
  if (s_answer) u_copy(s_answer, text, ANSWER_BUF_SIZE);
  vibes_double_pulse();
  layout();
}

static void timeout_fired(void *ctx) {
  s_timeout = NULL;
  if (s_state == ST_WAITING) show_error("No reply from your phone. Is the Pebble app running and online?");
}

// ---------- sending ----------
static void send_question(AskKind kind, int quick_index) {
  if (s_speaking) { comm_send_speak(false); s_speaking = false; }
  s_state = ST_WAITING;
  s_view_only = false;
  u_copy(s_status, "Thinking", sizeof(s_status));
  s_answer[0] = '\0';
  s_dots = 0;
  stop_timers();
  if (!connection_service_peek_pebble_app_connection()) {
    show_error("Your phone isn't connected.");
    return;
  }
  const char *text = (kind == ASK_QUICK || kind == ASK_HANDOFF) ? NULL : s_question;
  if (!comm_send_ask(kind, text, quick_index)) {
    show_error("Couldn't send to your phone. Try again.");
    return;
  }
  s_live = true;
  s_sent_kind = kind;
  s_timeout = app_timer_register(70000, timeout_fired, NULL);
  anim_tick(NULL);
}

// ---------- dictation ----------
static void close_later(void *ctx) {
  if (s_window && s_state == ST_IDLE) window_stack_remove(s_window, true);
}

#if defined(PBL_MICROPHONE)
static void dictation_cb(DictationSession *session, DictationSessionStatus status,
                         char *transcription, void *ctx) {
  if (status == DictationSessionStatusSuccess && transcription && transcription[0]) {
    if (s_pending_kind == ASK_NEW) {   // a new conversation starts only once we have words
      s_live = false;
      s_has_answer = false;
      s_last_quick = -1;
    }
    u_copy(s_question, transcription, sizeof(s_question));
    send_question(s_pending_kind, 0);
    return;
  }
  if (s_state != ST_DICTATING) return;
  bool user_cancel = status == DictationSessionStatusFailureTranscriptionRejected;
  if (s_answer && s_answer[0] && s_question[0]) {
    s_state = ST_DONE;   // go back to showing the previous answer
    layout();
  } else if (user_cancel) {
    s_state = ST_IDLE;
    // Don't destroy the dictation session from inside its own callback
    app_timer_register(0, close_later, NULL);
  } else {
    show_error(status == DictationSessionStatusFailureConnectivityError
               ? "Voice needs your phone's internet connection."
               : "Couldn't hear that. Press Select to try again.");
  }
}
#endif

static void start_dictation(AskKind kind) {
  s_pending_kind = kind;
#if defined(PBL_MICROPHONE)
  if (!s_dictation) {
    s_dictation = dictation_session_create(QUESTION_BUF_SIZE, dictation_cb, NULL);
    if (s_dictation) dictation_session_enable_confirmation(s_dictation, false);
  }
  if (s_dictation) {
    s_state = ST_DICTATING;
    if (dictation_session_start(s_dictation) == DictationSessionStatusSuccess) return;
  }
  show_error("Voice isn't available right now.");
#else
  show_error("This watch has no microphone.");
#endif
}

// ---------- layout ----------
static void layout(void) {
  if (!s_window || !s_scroll) return;
  GRect b = layer_get_bounds(window_get_root_layer(s_window));
  int w = b.size.w - 2 * MARGIN_X;
  int y = MARGIN_TOP;

  text_layer_set_text(s_q_layer, s_question);
  layer_set_frame(text_layer_get_layer(s_q_layer), GRect(MARGIN_X, y, w, 2000));
  GSize qs = text_layer_get_content_size(s_q_layer);
  layer_set_frame(text_layer_get_layer(s_q_layer), GRect(MARGIN_X, y, w, qs.h + 4));
  y += qs.h + 8;

  text_layer_set_text(s_a_layer, s_answer);
  layer_set_frame(text_layer_get_layer(s_a_layer), GRect(MARGIN_X, y, w, 4000));
  GSize as = text_layer_get_content_size(s_a_layer);
  layer_set_frame(text_layer_get_layer(s_a_layer), GRect(MARGIN_X, y, w, as.h + 6));
  y += as.h + 10;

  const char *hint = "";
  if (s_state == ST_DONE || s_state == ST_ERROR) {
    if (s_view_only || !s_live) hint = "Hold Select: new question";
    else if (!s_has_answer) hint = "Select: try again";
    else if (comm_companion()) hint = "Select: follow up\nHold Select: new question\nHold Up: read aloud\nHold Down: hand off to Claude";
    else hint = "Select: follow up\nHold Select: new question";
  }
  text_layer_set_text(s_hint_layer, hint);
  layer_set_frame(text_layer_get_layer(s_hint_layer), GRect(MARGIN_X, y, w, 2000));
  GSize hs = text_layer_get_content_size(s_hint_layer);
  layer_set_frame(text_layer_get_layer(s_hint_layer), GRect(MARGIN_X, y, w, hs.h + 4));
  y += hs.h + PBL_IF_ROUND_ELSE(40, 12);

  scroll_layer_set_content_size(s_scroll, GSize(b.size.w, y));
  layer_mark_dirty(s_bar);
}

static void bar_update(Layer *layer, GContext *g) {
  if (s_state != ST_WAITING) return;
  GRect r = layer_get_bounds(layer);
  graphics_context_set_fill_color(g, ACCENT_COLOR);
  int seg = r.size.w / 4;
  graphics_fill_rect(g, GRect(s_dots * seg, 0, seg, r.size.h), 0, GCornerNone);
}

// ---------- buttons ----------
static void select_click(ClickRecognizerRef r, void *ctx) {
  if ((s_state == ST_DONE || s_state == ST_ERROR) && !s_view_only && s_live) {
    if (s_has_answer) start_dictation(ASK_FOLLOWUP);
    else if (s_last_quick >= 0) send_question(ASK_QUICK, s_last_quick);
    else start_dictation(ASK_NEW);
  }
}

static void select_long(ClickRecognizerRef r, void *ctx) {
  if (s_state == ST_WAITING || s_state == ST_DICTATING) return;
  start_dictation(ASK_NEW);
}

// Page through the answer: one screen minus a line of overlap per press.
static void page(int dir) {
  GRect frame = layer_get_frame(scroll_layer_get_layer(s_scroll));
  GSize content = scroll_layer_get_content_size(s_scroll);
  GPoint off = scroll_layer_get_content_offset(s_scroll);
  int step = frame.size.h - PBL_IF_ROUND_ELSE(60, 30);
  int y = off.y - dir * step;
  int min_y = frame.size.h - content.h;
  if (min_y > 0) min_y = 0;
  if (y < min_y) y = min_y;
  if (y > 0) y = 0;
  scroll_layer_set_content_offset(s_scroll, GPoint(0, y), true);
}
static void up_click(ClickRecognizerRef r, void *ctx) { page(-1); }

// Hold Up: have the phone read the answer aloud (again to stop)
static void up_long(ClickRecognizerRef r, void *ctx) {
  if (s_state != ST_DONE || !s_answer || !s_answer[0]) return;
  if (!comm_companion()) { vibes_double_pulse(); return; }
  if (s_view_only) { vibes_double_pulse(); return; }   // phone only has the latest answer
  s_speaking = !s_speaking;
  if (comm_send_speak(s_speaking)) vibes_short_pulse();
  else s_speaking = false;
}
static void down_click(ClickRecognizerRef r, void *ctx) { page(1); }

// Hold Down: hand this conversation off to the notes doc (needs the companion)
static void down_long(ClickRecognizerRef r, void *ctx) {
  if (s_state != ST_DONE || s_view_only || !s_live || !s_has_answer) return;
  if (!comm_companion()) { vibes_double_pulse(); return; }
  u_copy(s_question, "Hand off to Claude", sizeof(s_question));
  send_question(ASK_HANDOFF, 0);
}

static void click_config(void *ctx) {
  window_single_repeating_click_subscribe(BUTTON_ID_UP, 200, up_click);
  window_long_click_subscribe(BUTTON_ID_UP, 600, up_long, NULL);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, 200, down_click);
  window_long_click_subscribe(BUTTON_ID_DOWN, 600, down_long, NULL);
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click);
  window_long_click_subscribe(BUTTON_ID_SELECT, 600, select_long, NULL);
}

// ---------- window ----------
static void window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  s_answer = malloc(ANSWER_BUF_SIZE);
  if (s_answer) s_answer[0] = '\0';

  s_scroll = scroll_layer_create(b);
  scroll_layer_set_shadow_hidden(s_scroll, true);
  scroll_layer_set_callbacks(s_scroll, (ScrollLayerCallbacks) { .click_config_provider = click_config });
  scroll_layer_set_click_config_onto_window(s_scroll, w);

  s_q_layer = text_layer_create(GRect(0, 0, b.size.w, 40));
  text_layer_set_font(s_q_layer, fonts_get_system_font(FONT_SMALL));
  text_layer_set_text_color(s_q_layer, PBL_IF_COLOR_ELSE(GColorDarkGray, GColorBlack));
  text_layer_set_text_alignment(s_q_layer, PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft));

  s_a_layer = text_layer_create(GRect(0, 0, b.size.w, 40));
  text_layer_set_font(s_a_layer, fonts_get_system_font(FONT_BODY));
  text_layer_set_text_alignment(s_a_layer, PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft));

  s_hint_layer = text_layer_create(GRect(0, 0, b.size.w, 40));
  text_layer_set_font(s_hint_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  text_layer_set_text_color(s_hint_layer, PBL_IF_COLOR_ELSE(ACCENT_COLOR, GColorBlack));
  text_layer_set_text_alignment(s_hint_layer, PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft));

  scroll_layer_add_child(s_scroll, text_layer_get_layer(s_q_layer));
  scroll_layer_add_child(s_scroll, text_layer_get_layer(s_a_layer));
  scroll_layer_add_child(s_scroll, text_layer_get_layer(s_hint_layer));
  layer_add_child(root, scroll_layer_get_layer(s_scroll));

  s_bar = layer_create(GRect(0, 0, b.size.w, 3));
  layer_set_update_proc(s_bar, bar_update);
  layer_add_child(root, s_bar);
}

static void window_unload(Window *w) {
  stop_timers();
  if (s_speaking) { comm_send_speak(false); s_speaking = false; }
#if defined(PBL_MICROPHONE)
  if (s_dictation) { dictation_session_destroy(s_dictation); s_dictation = NULL; }
#endif
  text_layer_destroy(s_q_layer);
  text_layer_destroy(s_a_layer);
  text_layer_destroy(s_hint_layer);
  scroll_layer_destroy(s_scroll);
  layer_destroy(s_bar);
  free(s_answer);
  s_answer = NULL;
  s_scroll = NULL;
  window_destroy(w);
  if (s_window == w) s_window = NULL;
  s_state = ST_IDLE;
  s_live = false;
  s_has_answer = false;
}

static bool ensure_window(void) {
  if (!s_window) {
    s_window = window_create();
    window_set_window_handlers(s_window, (WindowHandlers) { .load = window_load, .unload = window_unload });
    window_stack_push(s_window, true);
  }
  if (!s_answer) { window_stack_remove(s_window, false); return false; }
  return true;
}

// ---------- public ----------
void answer_win_ask(AskKind kind, int quick_index, const char *quick_label) {
  if (!ensure_window()) return;
  s_question[0] = '\0';
  s_answer[0] = '\0';
  s_view_only = false;
  s_live = false;
  s_has_answer = false;
  layout();
  s_last_quick = kind == ASK_QUICK ? quick_index : -1;
  if (kind == ASK_QUICK) {
    u_copy(s_question, quick_label ? quick_label : "", sizeof(s_question));
    send_question(ASK_QUICK, quick_index);
  } else {
    start_dictation(kind);
  }
}

void answer_win_view(const char *question, const char *answer) {
  if (!ensure_window()) return;
  stop_timers();
  u_copy(s_question, question, sizeof(s_question));
  u_copy(s_answer, answer, ANSWER_BUF_SIZE);
  s_view_only = true;
  s_live = false;
  s_state = ST_DONE;
  layout();
}

void answer_win_on_status(const char *status) {
  if (s_state != ST_WAITING || !status) return;
  u_copy(s_status, status, sizeof(s_status));
  if (s_timeout) app_timer_reschedule(s_timeout, 70000);   // the phone is still working
}

void answer_win_on_chunk(const char *chunk, bool final) {
  if (s_state != ST_WAITING || !s_answer) return;
  if (s_anim) {                 // first chunk: drop the "Thinking..." text
    app_timer_cancel(s_anim);
    s_anim = NULL;
    s_answer[0] = '\0';
  }
  if (s_status[0]) { s_status[0] = '\0'; s_answer[0] = '\0'; }
  if (chunk) u_append(s_answer, chunk, ANSWER_BUF_SIZE);
  if (s_timeout) { app_timer_reschedule(s_timeout, 30000); }
  if (final) {
    stop_timers();
    s_state = ST_DONE;
    s_has_answer = true;
    s_speaking = false;
    vibes_short_pulse();
    if (s_sent_kind != ASK_HANDOFF) history_add(s_question, s_answer);
  }
  layout();
}

void answer_win_on_error(const char *text) {
  if (s_state != ST_WAITING) return;
  show_error(text ? text : "Something went wrong.");
}
