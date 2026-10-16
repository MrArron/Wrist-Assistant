#include "common.h"

static void init(void) {
  comm_init();
  reminders_init();
  menu_win_push();
  if (launch_reason() == APP_LAUNCH_WAKEUP) {
    reminders_handle_launch();
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
