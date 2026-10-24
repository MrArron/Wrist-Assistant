#include "common.h"

// Opened from a Quick Launch button: go straight to the microphone.
static void quick_launch_ask(void *ctx) {
  if (comm_key_set()) answer_win_ask(ASK_NEW, 0, NULL);
}

static void init(void) {
  comm_init();
  reminders_init();
  menu_win_push();
  if (launch_reason() == APP_LAUNCH_WAKEUP) {
    reminders_handle_launch();
  } else if (launch_reason() == APP_LAUNCH_QUICK_LAUNCH) {
    app_timer_register(0, quick_launch_ask, NULL);
  }
  reminders_prune();
}

static void deinit(void) {
  app_message_deregister_callbacks();
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
