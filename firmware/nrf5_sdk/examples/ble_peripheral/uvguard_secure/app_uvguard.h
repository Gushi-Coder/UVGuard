#ifndef APP_UVGUARD_H
#define APP_UVGUARD_H

#include <stdint.h>
#include "ble_uvguard.h"

/* 初始化板级硬件、传感器和1秒软件定时器。 */
void app_uvguard_init(ble_uvguard_t * service);

/* 主循环反复调用；只有1秒采样标志到达时才执行一次完整业务。 */
void app_uvguard_process(void);

/* FF02写入回调：解析手机发送的命令字节。 */
void app_uvguard_command(uint8_t const * data, uint16_t length);

#endif
