#include "led_ring.h"
#include "bsp_uvguard.h"
#include "nrf.h"
#include "nrf_gpio.h"

#define LED_COUNT       12U /* 数量来自PCB灯环BOM。 */
#define BITS_PER_LED    24U /* 每颗灯G、R、B各8位。 */
#define RESET_SLOTS     240U /* 240*1.25us=300us复位低电平。 */
#define PWM_TOP         20U   /* nRF PWM周期=COUNTERTOP/16MHz，即1.25us。 */
#define PWM_ZERO_HIGH   6U    /* About 0.375 us high. */
#define PWM_ONE_HIGH    13U   /* About 0.813 us high. */
#define PWM_FALLING_EDGE 0x8000U /* bit15=1：周期开始为高，到比较值时变低。 */
#define LED_BRIGHTNESS_PERCENT 10U /* 统一亮度：0=熄灭，10=原RGB数值的10%，100=原值。 */
#define PWM_WAIT_LIMIT  1000000UL /* 硬件事件异常时退出，不能把主程序永远卡住。 */

#if LED_BRIGHTNESS_PERCENT > 100U
#error LED_BRIGHTNESS_PERCENT_must_be_between_0_and_100
#endif

/* PWM EasyDMA can only read from RAM, therefore this cannot be const. */
static uint16_t m_sequence[LED_COUNT * BITS_PER_LED + RESET_SLOTS];
static uint8_t m_brightness_percent = LED_BRIGHTNESS_PERCENT;

void LedRing_SetBrightness(uint8_t percent)
{
    if (percent <= 100U) m_brightness_percent = percent;
}

static void encode_byte(uint8_t value, uint16_t * output)
{
    uint8_t bit;
    for (bit = 0; bit < 8; bit++)
    {
        /* SK6805要求最高位先发；PWM序列值表示一个周期内高电平计数。 */
        output[bit] = PWM_FALLING_EDGE |
                      ((value & (0x80U >> bit)) ?
                       PWM_ONE_HIGH : PWM_ZERO_HIGH);
    }
}

void LedRing_Init(void)
{
    /* PWM0使用16MHz外设时钟；PRESCALER DIV_1表示不分频。 */
    NRF_PWM0->ENABLE = PWM_ENABLE_ENABLE_Enabled;
    /* SK6805 needs 1.25 us/bit. Up mode counts 20 ticks at 16 MHz.
       Up-down mode would double the bit time to 2.5 us. */
    NRF_PWM0->MODE = PWM_MODE_UPDOWN_Up;
    NRF_PWM0->PRESCALER = PWM_PRESCALER_PRESCALER_DIV_1;
    /* nRF PWM向上计数模式：周期为COUNTERTOP个时钟，16MHz/20=800kHz。 */
    NRF_PWM0->COUNTERTOP = PWM_TOP;
    NRF_PWM0->LOOP = 0;
    /* Common模式：EasyDMA每次读取一个16位值，送到输出通道0。 */
    NRF_PWM0->DECODER = PWM_DECODER_LOAD_Common |
                        PWM_DECODER_MODE_RefreshCount;
    NRF_PWM0->PSEL.OUT[0] = UV_PIN_LED_DATA; /* PWM通道0连接P0.15。 */
    NRF_PWM0->PSEL.OUT[1] = 0xFFFFFFFFUL;
    NRF_PWM0->PSEL.OUT[2] = 0xFFFFFFFFUL;
    NRF_PWM0->PSEL.OUT[3] = 0xFFFFFFFFUL;
}

void LedRing_Show(uint8_t red, uint8_t green, uint8_t blue)
{
    uint16_t index = 0;
    uint8_t led;
    uint32_t wait_left;

    /* 只缩放RGB亮度数值；不能通过修改800kHz通信时序来调暗灯。 */
    red = (uint8_t)(((uint16_t)red * m_brightness_percent + 50U) / 100U);
    green = (uint8_t)(((uint16_t)green * m_brightness_percent + 50U) / 100U);
    blue = (uint8_t)(((uint16_t)blue * m_brightness_percent + 50U) / 100U);

    /* SK6805数据顺序是GRB，不是程序参数的RGB顺序。 */
    for (led = 0; led < LED_COUNT; led++)
    {
        encode_byte(green, &m_sequence[index]); index += 8;
        encode_byte(red,   &m_sequence[index]); index += 8;
        encode_byte(blue,  &m_sequence[index]); index += 8;
    }
    while (index < (uint16_t)(LED_COUNT * BITS_PER_LED + RESET_SLOTS))
        m_sequence[index++] = PWM_FALLING_EDGE; /* 比较值为0，整周期保持低电平。 */

    LedRing_Init();
    /* SEQ[0].PTR/CNT告诉EasyDMA从RAM哪里取、取多少个16位波形值。 */
    NRF_PWM0->SEQ[0].PTR = (uint32_t)m_sequence;
    NRF_PWM0->SEQ[0].CNT = index;
    NRF_PWM0->SEQ[0].REFRESH = 0;
    NRF_PWM0->SEQ[0].ENDDELAY = 0;
    NRF_PWM0->EVENTS_SEQEND[0] = 0;
    NRF_PWM0->TASKS_SEQSTART[0] = 1; /* 写1启动序列0。 */
    wait_left = PWM_WAIT_LIMIT;
    while ((NRF_PWM0->EVENTS_SEQEND[0] == 0U) && (--wait_left != 0U)) { }
    NRF_PWM0->EVENTS_STOPPED = 0;
    NRF_PWM0->TASKS_STOP = 1;
    /* PWM stop completes at a period boundary. Wait before the next frame. */
    wait_left = PWM_WAIT_LIMIT;
    while ((NRF_PWM0->EVENTS_STOPPED == 0U) && (--wait_left != 0U)) { }
    /* 发送后交回GPIO并保持低电平；超时时也释放PWM输出。 */
    NRF_PWM0->ENABLE = PWM_ENABLE_ENABLE_Disabled;
    NRF_PWM0->PSEL.OUT[0] = 0xFFFFFFFFUL;
    nrf_gpio_pin_clear(UV_PIN_LED_DATA);
    nrf_gpio_cfg_output(UV_PIN_LED_DATA);
}
