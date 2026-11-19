#ifndef BSP_UVGUARD_H
#define BSP_UVGUARD_H

#include <stdbool.h>
#include <stdint.h>

/*
 * 文件作用：集中保存 PCB 引脚连接，类似 STM32 工程中的 board.h。
 * 参数来源：UV-Guard 原理图和已经实机验证过的设备树配置。
 *
 * 为什么集中定义：如果以后 PCB 改版，只需要在这里改引脚号，
 * 上层 LTR390、灯环和应用逻辑不用到处修改。
 */
#define UV_PIN_LED_DATA   15U  /* P0.15 -> 第1颗 SK6805 的 DIN。 */
#define UV_PIN_BUTTON     11U  /* P0.11 -> SW1，按下后被拉到 GND。 */
#define UV_PIN_CHARGE     12U  /* P0.12 -> 充电芯片 STAT，低电平表示充电。 */
#define UV_PIN_I2C_SDA    26U  /* P0.26 -> LTR-390 SDA。 */
#define UV_PIN_I2C_SCL    27U  /* P0.27 -> LTR-390 SCL。 */

/* 初始化板级 GPIO：设置方向、上下拉和默认电平。 */
void BSP_UVGuard_Init(void);

/* 返回 true 表示 SW1 当前被按下。 */
bool BSP_ButtonPressed(void);

/* 返回 true 表示充电芯片 STAT 输出低电平，即正在充电。 */
bool BSP_IsCharging(void);

/* 诊断函数：绕过灯环协议，直接把 P0.15 拉高供万用表测量。 */
void BSP_LedPinHigh(void);

#endif
