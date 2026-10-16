#include "common.h"

typedef struct {
  int32_t wakeup_id;
  int32_t time;
  char text[REMINDER_TEXT_LEN];
} Reminder;

#define SNOOZE_SECONDS (10 * 60)

static Window *s_list;
static MenuLayer *s_list_menu;
static TextLayer *s_list_empty;

// ---------- storage ----------
static bool load(int slot, Reminder *r) {
  if (!persist_exists(PK_REMINDER_BASE + slot)) return false;
  return persist_read_data(PK_REMINDER_BASE + slot, r, sizeof(*r)) == (int)sizeof(*r);
}

static void clear_slot(int slot) { persist_delete(PK_REMINDER_BASE + slot); }

int reminders_count(void) {
  int n = 0;
  Reminder r;
  for (int i = 0; i < REMINDER_MAX; i++) if (load(i, &r)) n++;
  return n;
}

// Index of the n-th used slot in time order (simple selection; at most 8 items)
static int nth_slot(int n) {
  int used[REMINDER_MAX];
  int32_t times[REMINDER_MAX];
  int count = 0;
  Reminder r;
  for (int i = 0; i < REMINDER_MAX; i++) {
    if (load(i, &r)) { used[count] = i; times[count] = r.time; count++; }
  }
  for (int a = 0; a < count; a++) {
    for (int b = a + 1; b < count; b++) {
      if (times[b] < times[a]) {
        int32_t t = times[a]; times[a] = times[b]; times[b] = t;
        int u = used[a]; used[a] = used[b]; used[b] = u;
      }
    }
  }
  return n < count ? used[n] : -1;
}

bool reminders_add(time_t when, const char *text) {
  time_t now = time(NULL);
  if (when < now + 30) when = now + 30;
  int slot = -1;
  Reminder r;
  for (int i = 0; i < REMINDER_MAX; i++) if (!load(i, &r)) { slot = i; break; }
  if (slot < 0) return false;

  // Wakeups can't be within a minute of another app's wakeup: nudge forward if refused.
  WakeupId id = -1;
  for (int tries = 0; tries < 5; tries++) {
    id = wakeup_schedule(when, slot, true);
    if (id != E_RANGE) break;
    when += 60;
  }
  if (id < 0) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "wakeup_schedule failed %d", (int)id);
    return false;
  }
  memset(&r, 0, sizeof(r));
  r.wakeup_id = id;
  r.time = (int32_t)when;
  u_copy(r.text, text && text[0] ? text : "Reminder", sizeof(r.text));
  persist_write_data(PK_REMINDER_BASE + slot, &r, sizeof(r));
  return true;
}

// ---------- alert window ----------
static Window *s_alert;
static TextLayer *s_alert_title, *s_alert_text, *s_alert_hint;
static char s_alert_buf[REMINDER_TEXT_LEN];
static char s_alert_time[24];
static AppTimer *s_buzz_timer;
static int s_buzz_count;
static bool s_alert_from_launch;

static const uint32_t BUZZ[] = { 300, 150, 300, 150, 600 };

static void buzz(void *ctx) {
  s_buzz_timer = NULL;
  VibePattern p = { .durations = BUZZ, .num_segments = ARRAY_LENGTH(BUZZ) };
  vibes_enqueue_custom_pattern(p);
  if (++s_buzz_count < 3) s_buzz_timer = app_timer_register(8000, buzz, NULL);
}

static void close_alert(void) {
  if (s_buzz_timer) { app_timer_cancel(s_buzz_timer); s_buzz_timer = NULL; }
  vibes_cancel();
  if (s_alert_from_launch) window_stack_pop_all(true);   // launched just for this: exit
  else window_stack_remove(s_alert, true);
}

static void alert_select(ClickRecognizerRef r, void *ctx) { close_alert(); }
static void alert_back(ClickRecognizerRef r, void *ctx) { close_alert(); }

static void alert_down(ClickRecognizerRef r, void *ctx) {
  if (!reminders_add(time(NULL) + SNOOZE_SECONDS, s_alert_buf)) {
    vibes_double_pulse();   // couldn't snooze (too many reminders): stay on screen
    return;
  }
  close_alert();
}

static void alert_clicks(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, alert_select);
  window_single_click_subscribe(BUTTON_ID_BACK, alert_back);
  window_single_click_subscribe(BUTTON_ID_DOWN, alert_down);
}

static void alert_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  int mx = PBL_IF_ROUND_ELSE(24, 8);
  window_set_background_color(w, ACCENT_COLOR);

  s_alert_title = text_layer_create(GRect(mx, PBL_IF_ROUND_ELSE(30, 10), b.size.w - 2 * mx, 34));
  text_layer_set_text(s_alert_title, s_alert_time);
  text_layer_set_font(s_alert_title, fonts_get_system_font(FONT_TITLE));

  s_alert_text = text_layer_create(GRect(mx, PBL_IF_ROUND_ELSE(66, 48), b.size.w - 2 * mx, b.size.h - 100));
  text_layer_set_text(s_alert_text, s_alert_buf);
  text_layer_set_font(s_alert_text, fonts_get_system_font(FONT_BODY));

  s_alert_hint = text_layer_create(GRect(mx, b.size.h - PBL_IF_ROUND_ELSE(56, 40), b.size.w - 2 * mx, 36));
  text_layer_set_text(s_alert_hint, "Select: done\nDown: snooze 10 min");
  text_layer_set_font(s_alert_hint, fonts_get_system_font(FONT_KEY_GOTHIC_14));

  TextLayer *layers[] = { s_alert_title, s_alert_text, s_alert_hint };
  for (int i = 0; i < 3; i++) {
    text_layer_set_background_color(layers[i], GColorClear);
    text_layer_set_text_color(layers[i], GColorWhite);
    text_layer_set_text_alignment(layers[i], PBL_IF_ROUND_ELSE(GTextAlignmentCenter, GTextAlignmentLeft));
    layer_add_child(root, text_layer_get_layer(layers[i]));
  }
  s_buzz_count = 0;
  buzz(NULL);
}

static void alert_unload(Window *w) {
  if (s_buzz_timer) { app_timer_cancel(s_buzz_timer); s_buzz_timer = NULL; }
  text_layer_destroy(s_alert_title);
  text_layer_destroy(s_alert_text);
  text_layer_destroy(s_alert_hint);
  window_destroy(w);
  if (s_alert == w) s_alert = NULL;
}

static void show_alert(int slot, bool from_launch) {
  Reminder r;
  if (!load(slot, &r)) return;
  clear_slot(slot);
  u_copy(s_alert_buf, r.text, sizeof(s_alert_buf));
  u_time_str((time_t)r.time, s_alert_time, sizeof(s_alert_time));
  s_alert_from_launch = from_launch;
  if (s_alert) window_stack_remove(s_alert, false);
  s_alert = window_create();
  window_set_click_config_provider(s_alert, alert_clicks);
  window_set_window_handlers(s_alert, (WindowHandlers) { .load = alert_load, .unload = alert_unload });
  window_stack_push(s_alert, true);
  menu_win_reload();
  if (s_list_menu) menu_layer_reload_data(s_list_menu);
}

static int slot_for_wakeup(WakeupId id, int32_t cookie) {
  Reminder r;
  if (cookie >= 0 && cookie < REMINDER_MAX && load(cookie, &r) && r.wakeup_id == id) return cookie;
  for (int i = 0; i < REMINDER_MAX; i++) if (load(i, &r) && r.wakeup_id == id) return i;
  return -1;
}

static void wakeup_handler(WakeupId id, int32_t cookie) {
  int slot = slot_for_wakeup(id, cookie);
  if (slot >= 0) show_alert(slot, false);
}

void reminders_handle_launch(void) {
  WakeupId id;
  int32_t cookie;
  if (wakeup_get_launch_event(&id, &cookie)) {
    int slot = slot_for_wakeup(id, cookie);
    if (slot >= 0) show_alert(slot, true);
  }
}

void reminders_init(void) {
  wakeup_service_subscribe(wakeup_handler);
}

void reminders_prune(void) {
  // Drop reminders whose wakeup no longer exists (fired while we were busy, or cancelled)
  time_t now = time(NULL);
  Reminder r;
  for (int i = 0; i < REMINDER_MAX; i++) {
    if (!load(i, &r)) continue;
    time_t t;
    if (!wakeup_query(r.wakeup_id, &t) && r.time < now - 120) clear_slot(i);
  }
}

// ---------- list window ----------

static uint16_t list_rows(MenuLayer *m, uint16_t s, void *ctx) { return reminders_count(); }

static void list_draw(GContext *g, const Layer *cell, MenuIndex *idx, void *ctx) {
  Reminder r;
  int slot = nth_slot(idx->row);
  if (slot < 0 || !load(slot, &r)) return;
  char when[32];
  u_time_str((time_t)r.time, when, sizeof(when));
  bool sel = menu_layer_is_index_selected(s_list_menu, idx);
  if (sel) u_append(when, " - hold to cancel", sizeof(when));
  menu_cell_basic_draw(g, cell, r.text, when, NULL);
}

static void list_long(MenuLayer *m, MenuIndex *idx, void *ctx) {
  Reminder r;
  int slot = nth_slot(idx->row);
  if (slot < 0 || !load(slot, &r)) return;
  wakeup_cancel(r.wakeup_id);
  clear_slot(slot);
  vibes_short_pulse();
  menu_layer_reload_data(s_list_menu);
  if (reminders_count() == 0) window_stack_remove(s_list, true);
}

static void list_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  if (reminders_count() == 0) {
    s_list_empty = text_layer_create(GRect(10, b.size.h / 2 - 40, b.size.w - 20, 80));
    text_layer_set_text(s_list_empty, "No reminders.\nTry: \"Remind me in 20 minutes to check the oven.\"");
    text_layer_set_font(s_list_empty, fonts_get_system_font(FONT_SMALL));
    text_layer_set_text_alignment(s_list_empty, GTextAlignmentCenter);
    layer_add_child(root, text_layer_get_layer(s_list_empty));
    return;
  }
  s_list_menu = menu_layer_create(b);
  menu_layer_set_callbacks(s_list_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = list_rows, .draw_row = list_draw, .select_long_click = list_long,
  });
  menu_layer_set_highlight_colors(s_list_menu, ACCENT_COLOR, GColorWhite);
  menu_layer_set_click_config_onto_window(s_list_menu, w);
  layer_add_child(root, menu_layer_get_layer(s_list_menu));
}

static void list_unload(Window *w) {
  if (s_list_menu) { menu_layer_destroy(s_list_menu); s_list_menu = NULL; }
  if (s_list_empty) { text_layer_destroy(s_list_empty); s_list_empty = NULL; }
  window_destroy(w);
  if (s_list == w) s_list = NULL;
}

void reminders_win_push(void) {
  s_list = window_create();
  window_set_window_handlers(s_list, (WindowHandlers) { .load = list_load, .unload = list_unload });
  window_stack_push(s_list, true);
}
