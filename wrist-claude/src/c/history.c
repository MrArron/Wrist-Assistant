#include "common.h"

// Each entry: "question\x1fanswer" in one persist string (max 256 bytes incl. NUL).
#define SEP '\x1f'
#define ENTRY_SIZE 256
#define Q_MAX 60

static Window *s_window;
static MenuLayer *s_menu;
static TextLayer *s_empty;

int history_count(void) {
  int n = 0;
  while (n < HISTORY_MAX && persist_exists(PK_HISTORY_BASE + n)) n++;
  return n;
}

void history_add(const char *question, const char *answer) {
  if (!question || !question[0] || !answer || !answer[0]) return;
  char *entry = malloc(ENTRY_SIZE);
  char *tmp = malloc(ENTRY_SIZE);
  if (!entry || !tmp) { free(entry); free(tmp); return; }

  u_copy(entry, question, Q_MAX + 1);
  int n = u_len(entry);
  entry[n++] = SEP;
  entry[n] = '\0';
  int room = ENTRY_SIZE - n;
  if (u_len(answer) < room) {
    u_copy(entry + n, answer, room);
  } else {
    u_copy(entry + n, answer, room - 3);
    u_append(entry, "...", ENTRY_SIZE);
  }

  // shift older entries down (oldest falls off)
  for (int i = HISTORY_MAX - 1; i > 0; i--) {
    if (persist_exists(PK_HISTORY_BASE + i - 1)) {
      persist_read_string(PK_HISTORY_BASE + i - 1, tmp, ENTRY_SIZE);
      persist_write_string(PK_HISTORY_BASE + i, tmp);
    }
  }
  persist_write_string(PK_HISTORY_BASE, entry);
  free(entry);
  free(tmp);
}

bool history_get(int index, char *question, int q_size, char *answer, int a_size) {
  if (index < 0 || index >= HISTORY_MAX || !persist_exists(PK_HISTORY_BASE + index)) return false;
  char *entry = malloc(ENTRY_SIZE);
  if (!entry) return false;
  persist_read_string(PK_HISTORY_BASE + index, entry, ENTRY_SIZE);
  int i = 0;
  while (entry[i] && entry[i] != SEP) i++;
  bool has_sep = entry[i] == SEP;
  entry[i] = '\0';
  if (question) u_copy(question, entry, q_size);
  if (answer) u_copy(answer, has_sep ? entry + i + 1 : "", a_size);
  free(entry);
  return true;
}

// ---------- list window ----------
static uint16_t num_rows(MenuLayer *m, uint16_t s, void *ctx) { return history_count(); }

static void draw_row(GContext *g, const Layer *cell, MenuIndex *idx, void *ctx) {
  char q[Q_MAX + 4];
  history_get(idx->row, q, sizeof(q), NULL, 0);
  menu_cell_basic_draw(g, cell, q, NULL, NULL);
}

static void select_click(MenuLayer *m, MenuIndex *idx, void *ctx) {
  char q[Q_MAX + 4];
  char *a = malloc(ENTRY_SIZE);
  if (!a) return;
  if (history_get(idx->row, q, sizeof(q), a, ENTRY_SIZE)) answer_win_view(q, a);
  free(a);
}

static void window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  GRect b = layer_get_bounds(root);
  if (history_count() == 0) {
    s_empty = text_layer_create(GRect(10, b.size.h / 2 - 30, b.size.w - 20, 60));
    text_layer_set_text(s_empty, "No history yet.\nAsk something first.");
    text_layer_set_font(s_empty, fonts_get_system_font(FONT_SMALL));
    text_layer_set_text_alignment(s_empty, GTextAlignmentCenter);
    layer_add_child(root, text_layer_get_layer(s_empty));
    return;
  }
  s_menu = menu_layer_create(b);
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks) {
    .get_num_rows = num_rows, .draw_row = draw_row, .select_click = select_click,
  });
  menu_layer_set_highlight_colors(s_menu, ACCENT_COLOR, GColorWhite);
  menu_layer_set_click_config_onto_window(s_menu, w);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void window_unload(Window *w) {
  if (s_menu) { menu_layer_destroy(s_menu); s_menu = NULL; }
  if (s_empty) { text_layer_destroy(s_empty); s_empty = NULL; }
  window_destroy(w);
  if (s_window == w) s_window = NULL;
}

void history_win_push(void) {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) { .load = window_load, .unload = window_unload });
  window_stack_push(s_window, true);
}
