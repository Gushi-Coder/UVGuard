#ifndef LED_RING_H
#define LED_RING_H
#include <stdint.h>
/* 初始化PWM0为SK6805所需的800kHz时基。 */
void LedRing_Init(void);
/* 让12颗灯全部显示同一种RGB颜色；每个参数范围0~255。 */
void LedRing_Show(uint8_t red, uint8_t green, uint8_t blue);
/* 统一RGB数值缩放，0~100；不改变灯珠800kHz通信时序。 */
void LedRing_SetBrightness(uint8_t percent);
#endif
