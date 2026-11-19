#ifndef BATTERY_H
#define BATTERY_H

#include <stdint.h>

/* 初始化 nRF52832 的 SAADC 通道0，用 AIN0 读取电池分压。 */
void Battery_Init(void);

/*
 * 进行一次 ADC 转换。
 * millivolts：输出换算后的电池电压，单位 mV。
 * raw        ：输出 ADC 原始码，便于诊断。
 * 返回值     ：估算电量百分比 0~100。
 */
uint8_t Battery_Read(uint16_t * millivolts, int16_t * raw);

#endif
