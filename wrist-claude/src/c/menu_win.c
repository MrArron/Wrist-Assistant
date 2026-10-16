#include "common.h"

// Sections: 0 = Ask, 1 = Quick prompts (if any), last = History + Reminders
static Window *s_window;
static MenuLayer *s_menu;

static int quick_section(void) { return comm_quick_count() > 0 ? 1 : -1; }
static int tools_section(void) { return comm_quick_count() > 0 ? 2 : 1; }

static uint16_t num_sections(MenuLayer *m, void *ctx) { return comm_quick_count() > 0 ? 3 : 2; }

static uint16_t num_rows(MenuLayer *m, uint16_t section, void *ctx) {
  if (section == 0) return 1;
  if ((int)section == quick_section()) return comm_quick_count();
  return 2;
}

static int16_t header_height(MenuLayer *m, uint16_t section, void *ctx) {
  return section == 0 ? 0 : PBL_IF_ROUND_ELSE(0, MENU_CELL_BASIC_HEADER_HEIGHT);
}

static void draw_header(GContext *g, const Layer *cell, uint16_t section, void *ctx) {
  if ((int)section == quick_section()) menu_cell_basic_header_draw(g, cell, "Quick prompts");
  else if ((int)section == tools_section()) menu_cell_basic_header_draw(g, cell, "More");
}

static int16_t cell_height(MenuLayer *m, MenuIndex *idx, void *ctx) {
  if (idx->section == 0) return PBL_IF_ROUND_ELSE(68, 60);
  return PBL_IF_ROUND_ELSE(menu_layer_is_index_selected(m, idx) ? 52 : 36, 44);
}

static void draw_row(GContext *g, const Layer *cell, MenuIndex *idx, void *ctx) {
  static char s_buf[32];
  static char s_sub[24];
  if (idx->section == 0) {
    menu_cell_basic_draw(g, cell, "Ask Claude",
                         comm_key_set() ? "Press to talk" : "Add API key on phone", NULL);
    return;
  }
  if ((int)idx->section == quick_section()) {
    comm_quick_name(idx->row, s_buf, sizeof(s_buf));
    menu_cell_basic_draw(g, cell, s_buf, NULL, NULL);
    return;
  }
  if (idx->row == 0) {
    snprintf(s_sub, sizeof(s_sub), "%d saved", history_count());
    menu_cell_basic_draw(g, cell, "History", s_sub, NULL);
  } else {
    int n = reminders_count();
    if (n == 0) u_copy(s_sub, "None set", sizeof(s_sub));
    else snprintf(s_sub, sizeof(s_sub), "%d upcoming", n);
    menu_cell_basic_draw(g, cell, "Reminders", s_sub, NULL);
  }
}

static void select_click(MenuLayer *m, MenuIndex *idx, void *ctx) {
  if (idx->section == 0) {
    answer_win_ask(ASK_NEW, 0, NULL);
  } else if ((int)idx->section == quick_section()) {
    char label[32];
    comm_quick_name(idx->row, label, sizeof(label));
    answer_win_ask(ASK_QUICK, idx->row, label);
  } else if (idx->row == 0) {
    history_win_push();
  } else {
    reminders_win_push();
  }
}

static void window_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL, (MenuLayerCallbacks) {
    .get_num_sections = num_sections,
    .get_num_rows = num_rows,
    .get_header_height = header_height,
    .draw_header = draw_header,
    .get_cell_height = cell_height,
    .draw_row = draw_row,
    .select_click = select_click,
  });
  menu_layer_set_highlight_colors(s_menu, ACCENT_COLOR, GColorWhite);
  menu_layer_set_click_config_onto_window(s_menu, w);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void window_unload(Window *w) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
}

static void window_appear(Window *w) {
  if (s_menu) menu_layer_reload_data(s_menu);
}

void menu_win_push(void) {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = window_load, .unload = window_unload, .appear = window_appear,
  });
  window_stack_push(s_window, true);
}

void menu_win_reload(void) {
  if (s_menu) menu_layer_reload_data(s_menu);
}
