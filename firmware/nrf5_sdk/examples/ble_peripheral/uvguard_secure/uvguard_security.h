#ifndef UVGUARD_SECURITY_H
#define UVGUARD_SECURITY_H
#include <stdbool.h>
#include <stdint.h>
/* Security owns pairing/storage; application continues to own sensor and LEDs. */
void uvguard_security_init(void);
void uvguard_security_process(void);
bool uvguard_security_ready(uint16_t connection);
#endif
