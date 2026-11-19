#include "battery.h"
#include <stdbool.h>
#include "nrf.h"
#include "nrf_gpio.h"

/*
 * 参数来源：
 * 1. nRF52832 SAADC 内部参考电压为 0.6 V；
 * 2. 配置增益为 1/6，所以 ADC 满量程输入约为 0.6/(1/6)=3.6 V；
 * 3. PCB 将电池电压二分之一送入 AIN0，因此还要再乘2；
 * 4. 12位 ADC 最大码为 2^12-1=4095。
 *
 * 因而：VBAT(mV) = raw / 4095 * 3600mV * 2 = raw * 7200 / 4095。
 */
#define SAADC_MAX_CODE             4095L
#define BATTERY_FULL_SCALE_MV      7200L
#define BATTERY_EMPTY_MV           3300U
#define BATTERY_FULL_MV            4200U
#define SAADC_WAIT_LIMIT           1000000UL

/* EasyDMA changes RAM without a C assignment: volatile prevents GCC from
 * folding the result to its initial zero. Static + alignment also guarantees
 * a valid RAM buffer and lifetime until STOPPED (including error paths). */
static volatile int16_t m_dma_sample __attribute__((aligned(4)));
static bool m_calibrated;

static bool wait_for(volatile uint32_t * event)
{
    uint32_t left = SAADC_WAIT_LIMIT;
    while ((*event == 0U) && (--left != 0U)) { }
    return left != 0U;
}

static bool stop_adc(void)
{
    NRF_SAADC->EVENTS_STOPPED = 0U;
    NRF_SAADC->TASKS_STOP = 1U;
    return wait_for(&NRF_SAADC->EVENTS_STOPPED);
}

void Battery_Init(void)
{
    uint8_t channel;
    nrf_gpio_cfg(2U, NRF_GPIO_PIN_DIR_INPUT, NRF_GPIO_PIN_INPUT_DISCONNECT,
                 NRF_GPIO_PIN_NOPULL, NRF_GPIO_PIN_S0S1, NRF_GPIO_PIN_NOSENSE);
    for (channel = 0U; channel < 8U; channel++)
    {
        NRF_SAADC->CH[channel].PSELP = SAADC_CH_PSELP_PSELP_NC;
        NRF_SAADC->CH[channel].PSELN = SAADC_CH_PSELN_PSELN_NC;
    }
    /* ENABLE：打开 SAADC 外设。没有使能时，TASKS_SAMPLE 不会工作。 */
    NRF_SAADC->ENABLE = SAADC_ENABLE_ENABLE_Enabled;

    /* RESOLUTION=12bit：转换结果范围约为0~4095，精度高于8/10位。 */
    NRF_SAADC->RESOLUTION = SAADC_RESOLUTION_VAL_12bit;

    /* PSELP：通道0的正输入选择 AIN0；它对应芯片的模拟输入0。 */
    NRF_SAADC->CH[0].PSELP = SAADC_CH_PSELP_PSELP_AnalogInput0;

    /* 单端模式不使用负输入，所以 PSELN 选择 Not Connected。 */
    NRF_SAADC->CH[0].PSELN = SAADC_CH_PSELN_PSELN_NC;

    /*
     * CONFIG 是多个位域拼成的寄存器：
     * GAIN=1/6       ：把可测输入范围扩大到约3.6V；
     * REFSEL=Internal：使用芯片内部0.6V参考；
     * MODE=SE        ：单端测量，电压相对GND。
     */
    NRF_SAADC->CH[0].CONFIG =
        (SAADC_CH_CONFIG_GAIN_Gain1_6 << SAADC_CH_CONFIG_GAIN_Pos) |
        (SAADC_CH_CONFIG_REFSEL_Internal << SAADC_CH_CONFIG_REFSEL_Pos) |
        (SAADC_CH_CONFIG_MODE_SE << SAADC_CH_CONFIG_MODE_Pos) |
        /* R5||R6 = 500 kohm: use the longest acquisition time (40 us),
         * not the reset default of 3 us. C5 supplies the sampling charge. */
        (SAADC_CH_CONFIG_TACQ_40us << SAADC_CH_CONFIG_TACQ_Pos);
    NRF_SAADC->OVERSAMPLE = SAADC_OVERSAMPLE_OVERSAMPLE_Bypass;
    NRF_SAADC->SAMPLERATE = 0U;
    NRF_SAADC->EVENTS_CALIBRATEDONE = 0U;
    NRF_SAADC->TASKS_CALIBRATEOFFSET = 1U;
    m_calibrated = wait_for(&NRF_SAADC->EVENTS_CALIBRATEDONE);
}

uint8_t Battery_Read(uint16_t * millivolts, int16_t * raw)
{
    int16_t sample;
    int32_t voltage_mv;
    bool complete;

    if (!m_calibrated) Battery_Init();
    if (!m_calibrated) goto failed;
    m_dma_sample = -32768;

    /* RESULT.PTR：EasyDMA 把转换结果写入 sample 的内存地址。 */
    NRF_SAADC->RESULT.PTR = (uint32_t)&m_dma_sample;

    /* MAXCNT=1：本次只采集一个16位样本。 */
    NRF_SAADC->RESULT.MAXCNT = 1U;

    /* 先清事件再启动，防止读取到上一次转换留下的 END=1。 */
    NRF_SAADC->EVENTS_STARTED = 0U;
    NRF_SAADC->EVENTS_END = 0U;
    __DMB();
    NRF_SAADC->TASKS_START = 1U;
    /* START与SAMPLE不能无等待地连写；先确认采样器已启动。 */
    if (!wait_for(&NRF_SAADC->EVENTS_STARTED))
    {
        (void)stop_adc();
        goto failed;
    }
    NRF_SAADC->TASKS_SAMPLE = 1U;

    /* END 由硬件置1，表示 EasyDMA 已经把结果写入 sample。 */
    complete = wait_for(&NRF_SAADC->EVENTS_END);
    if (!stop_adc() || !complete || NRF_SAADC->RESULT.AMOUNT != 1U)
    {
        goto failed;
    }
    __DMB();
    sample = m_dma_sample;
    if (sample == -32768) goto failed;

    /* 防止异常负值被换算为很大的无符号电压。 */
    if (sample < 0) sample = 0;
    voltage_mv = ((int32_t)sample * BATTERY_FULL_SCALE_MV) / SAADC_MAX_CODE;

    *raw = sample;
    *millivolts = (uint16_t)voltage_mv;

    /* 3.3V以下显示0%，4.2V以上显示100%，中间使用线性估算。 */
    if (*millivolts <= BATTERY_EMPTY_MV) return 0U;
    if (*millivolts >= BATTERY_FULL_MV) return 100U;
    return (uint8_t)(((*millivolts - BATTERY_EMPTY_MV) * 100U) /
                     (BATTERY_FULL_MV - BATTERY_EMPTY_MV));

failed:
    m_calibrated = false;
    *raw = -1; /* Diagnostic sentinel, not a measured zero. */
    *millivolts = 0U;
    return 0U;
}
