#ifndef IDLE_POWER_POLICY_H
#define IDLE_POWER_POLICY_H
#include <stdbool.h>
#include <stdint.h>

/* APP_TIMER uses a wrapping 24-bit RTC counter. 180 s is less than one wrap.
 * Keep this decision pure so boundary/connection/DFU cases can be tested. */
static inline bool idle_power_due(uint32_t elapsed, uint32_t limit,
                                  bool connected, bool entering_dfu,
                                  bool button_pressed, bool flash_busy)
{
    return elapsed >= limit && !connected && !entering_dfu &&
           !button_pressed && !flash_busy;
}
#endif
