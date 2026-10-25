#pragma once
#include <pebble.h>

// ---- Version (same scheme as the companion's versionCode: 2.2.1 = 221; bump with package.json) ----
#define APP_VERSION_CODE  221

// ---- Limits ----
#define ANSWER_BUF_SIZE   1600   // phone caps answers at 1,500 bytes
#define QUESTION_BUF_SIZE 512
#define QUICK_MAX         5
#define QUICK_NAMES_SIZE  128
#define HISTORY_MAX       5
#define REMINDER_MAX      8
#define REMINDER_TEXT_LEN 64

// ---- Persist keys ----
#define PK_QUICK_NAMES    1
#define PK_KEY_SET        2
#define PK_HISTORY_BASE   10   // 10..14
#define PK_REMINDER_BASE  20   // 20..27

// ---- Fonts (bigger watches get bigger text) ----
#if PBL_DISPLAY_HEIGHT >= 228
  #define FONT_BODY   FONT_KEY_GOTHIC_24_BOLD
  #define FONT_SMALL  FONT_KEY_GOTHIC_18
  #define FONT_TITLE  FONT_KEY_GOTHIC_28_BOLD
#else
  #define FONT_BODY   FONT_KEY_GOTHIC_18_BOLD
  #define FONT_SMALL  FONT_KEY_GOTHIC_14
  #define FONT_TITLE  FONT_KEY_GOTHIC_24_BOLD
#endif

#define ACCENT_COLOR PBL_IF_COLOR_ELSE(GColorFromHEX(0xFF5500), GColorBlack)

// ---- util.c ----
int  u_len(const char *s);
void u_copy(char *dst, const char *src, int dst_size);
void u_append(char *dst, const char *src, int dst_size);
void u_time_str(time_t t, char *out, int out_size);

// ---- comm.c ----
typedef enum { ASK_NEW = 0, ASK_FOLLOWUP = 1, ASK_QUICK = 2, ASK_HANDOFF = 3 } AskKind;
void comm_init(void);
bool comm_send_ask(AskKind kind, const char *text, int quick_index);
bool comm_send_speak(bool on);
void comm_send_confirm(int32_t id, bool approved);
bool comm_companion(void);              // the Android companion answered this launch
typedef enum { VER_OK = 0, VER_PHONE_OLDER = 1, VER_WATCH_OLDER = 2 } VersionMismatch;
VersionMismatch comm_version_mismatch(void);   // companion vs this watch app
void comm_send_ready(void);
bool comm_key_set(void);
const char *comm_quick_names(void);   // '\n' separated
int  comm_quick_count(void);
void comm_quick_name(int index, char *out, int out_size);

// ---- menu_win.c ----
void menu_win_push(void);
void menu_win_reload(void);

// ---- answer_win.c ----
void answer_win_ask(AskKind kind, int quick_index, const char *quick_label);
void answer_win_view(const char *question, const char *answer);
void answer_win_on_status(const char *status);
void answer_win_on_chunk(const char *chunk, bool final);
void answer_win_on_error(const char *text);

// ---- confirm_win.c ----
void confirm_win_show(int32_t id, const char *text);

// ---- history.c ----
void history_add(const char *question, const char *answer);
int  history_count(void);
bool history_get(int index, char *question, int q_size, char *answer, int a_size);
void history_win_push(void);

// ---- reminders.c ----
void reminders_init(void);              // prunes stale entries, subscribes to wakeups
bool reminders_add(time_t when, const char *text);
void reminders_handle_launch(void);     // call when launched by a wakeup
void reminders_prune(void);             // call after handling a wakeup launch
void reminders_win_push(void);
int  reminders_count(void);
