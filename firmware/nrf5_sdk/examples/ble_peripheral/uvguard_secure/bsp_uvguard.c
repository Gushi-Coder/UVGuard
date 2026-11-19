#include "bsp_uvguard.h"
#include "nrf_gpio.h"

/*
 * nrf_gpio_cfg_input()/output() 来自 Nordic nRF5 SDK 的 GPIO HAL。
 * 它们内部最终配置 nRF52832 的 GPIO PIN_CNF[n] 寄存器。
 *
 * PIN_CNF 的主要字段：
 *   DIR   ：输入还是输出；
 *   INPUT ：是否接入输入缓冲；
 *   PULL  ：不上拉、下拉或上拉；
 *   DRIVE ：引脚驱动能力；
 *   SENSE ：是否用电平唤醒芯片。
 */
void BSP_UVGuard_Init(void)
{
    /* SW1 按下时接地，所以平时必须用内部上拉保持为高电平。 */
    nrf_gpio_cfg_input(UV_PIN_BUTTON, NRF_GPIO_PIN_PULLUP);

    /* 充电 STAT 通常是开漏输出，也需要上拉；低电平表示正在充电。 */
    nrf_gpio_cfg_input(UV_PIN_CHARGE, NRF_GPIO_PIN_PULLUP);

    /* 灯环 DIN 是 MCU 输出。上电先输出低电平，避免灯珠误收到脉冲。 */
    nrf_gpio_cfg_output(UV_PIN_LED_DATA);
    nrf_gpio_pin_clear(UV_PIN_LED_DATA);
}

bool BSP_ButtonPressed(void)
{
    /* 上拉输入：未按=1，按下接地=0，所以这里判断是否等于0。 */
    return nrf_gpio_pin_read(UV_PIN_BUTTON) == 0U;
}

bool BSP_IsCharging(void)
{
    /* 根据充电芯片 STAT 定义：低电平有效。 */
    return nrf_gpio_pin_read(UV_PIN_CHARGE) == 0U;
}

void BSP_LedPinHigh(void)
{
    /* 重新确认方向为输出，再通过 GPIO OUTSET 把引脚置1。 */
    nrf_gpio_cfg_output(UV_PIN_LED_DATA);
    nrf_gpio_pin_set(UV_PIN_LED_DATA);
}
