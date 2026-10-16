#include "common.h"

// "Send this?" screen for actions the companion takes on the user's behalf
// (for example replying to a message). Select = send, Back = cancel.

#define CONFIRM_TEXT_SIZE 320
#define CONFIRM_TIMEOUT_MS 60000

static Window *s_window;
static ScrollLayer *s_scroll;
static TextLayer *s_title, *s_body, *s_hint;
static char s_text[CONFIRM_TEXT_SIZE];
static int32_t s_id;
static bool s_answered;
static AppTimer *s_timer;

static void answer(bool approved) {
  if (s_answered) return;
  s_answered = true;
  if (s_timer) { app_timer_cancel(s_timer); s_timer = NULL; }
  comm_send_confirm(s_id, approved);
  if (s_window) window_stack_remove(s_window, true);
}

static void timed_out(void *ctx) { s_timer = NULL; answer(false); }
static void select_click(ClickRecognizerRef r, void *ctx) { answer(true); }
static void back_click(ClickRecognizerRef r, void *ctx) { answer(false); }

static void click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click);
  window_single_click_subscribe(BUTTON_ID_BACK, back_click);
}

static void layout(void) {
  GRect b = layer_get_bounds(window_get_root_layer(s_window));
  int mx = PBL_IF_ROUND_ELSE(26, 6);
  int w = b.size.w - 2 * mx;
  int y = PBL_IF_ROUND_ELSE(24, 4);
  TextLayer *layers[] = { s_title, s_body, s_hint };
  for (int i = 0; i < 3; i++) {
    layer_set_frame(text_layer_get_layer(layers[i]), GRect(mx, y, w, 2000));
    GSize sz = text_layer_get_content_size(layers[i]);
    layer_set_frame(text_layer_get_layer(layers[i]), GRect(mx, y, w, sz.h + 4));
    y += sz.h + 8;
  }
  scroll_layer_set_content_size(s_scroll, GSize(b.size.w, y + PBL_IF_ROUND_ELSE(40, 8)));
}

static void window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  s_scroll = scroll_layer_create(b);
  scroll_layer_set_shadow_hidden(s_scroll, true);
  scroll_layer_set_callbacks(s_scroll, (ScrollLayerCallbacks) { .click_config_provider = click_config });
  scroll_layer_set_click_config_onto_window(s_scroll, w);

  s_title = text_layer_create(GRect(0, 0, b.size.w, 30));
  text_layer_set_text(s_title, "Send this?");
  text_layer_set_font(s_title, fonts_get_system_font(FONT_TITLE));
  text_layer_set_text_color(s_title, ACCENT_COLOR);

  s_body = text_layer_create(GRect(0, 0, b.size.w, 30));
  text_layer_set_text(s_body, s_text);
  text_layer_set_font(s_body, fonts_get_system_font(FONT_BODY));

  s_hint = text_layer_create(GRect(0, 0, b.size.w, 30));
  text_layer_set_text(s_hint, "Select: send\nBack: cancel");
  text_layer_set_font(s_hint, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  text_layer_set_text_color(s_hint, PBL_IF_COLOR_ELSE(ACCENT_COLOR, GColorBlack));

  TextLayer *layers[] = { s_title, s_body, s_hint };
  for (int i = 0; i < 3; i++) {
    text_layer_set_text_alignment(layers[i], PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft));
    scroll_layer_add_child(s_scroll, text_layer_get_layer(layers[i]));
  }
  layer_add_child(root, scroll_layer_get_layer(s_scroll));
  layout();
}

static void window_unload(Window *w) {
  // Leaving any other way (e.g. the app closing) counts as "no"
  if (!s_answered) {
    s_answered = true;
    if (s_timer) { app_timer_cancel(s_timer); s_timer = NULL; }
    comm_send_confirm(s_id, false);
  }
  text_layer_destroy(s_title);
  text_layer_destroy(s_body);
  text_layer_destroy(s_hint);
  scroll_layer_destroy(s_scroll);
  window_destroy(w);
  if (s_window == w) s_window = NULL;
}

void confirm_win_show(int32_t id, const char *text) {
  if (s_window) {                 // a newer request replaces an unanswered one
    // remove without animation so the old window unloads before we reuse the globals
    window_stack_remove(s_window, false);   // its unload sends "no" if unanswered
  }
  s_id = id;
  s_answered = false;
  u_copy(s_text, text ? text : "", sizeof(s_text));
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) { .load = window_load, .unload = window_unload });
  window_stack_push(s_window, true);
  vibes_double_pulse();
  s_timer = app_timer_register(CONFIRM_TIMEOUT_MS, timed_out, NULL);
}
