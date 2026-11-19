#include "ltr390.h"
#include "bsp_uvguard.h"
#include "nrf.h"
#include "nrf_gpio.h"
#include "nrf_log.h"
#include "nrf_delay.h"

/* 这些数值来自 LTR-390 数据手册的 I2C 地址和寄存器表。 */
#define LTR390_ADDRESS          0x53U
#define LTR390_REG_MAIN_CTRL    0x00U
#define LTR390_REG_MEAS_RATE    0x04U
#define LTR390_REG_GAIN         0x05U
#define LTR390_REG_PART_ID      0x06U
#define LTR390_REG_STATUS       0x07U
#define LTR390_REG_UVS_DATA     0x10U
#define LTR390_REG_ALS_DATA     0x0DU
#define LTR390_EXPECTED_PART_ID 0xB2U
#define LTR390_RATE_18BIT_100MS 0x22U /* 分辨率18位，测量周期100ms。 */
#define LTR390_GAIN_X3          0x01U
#define LTR390_CTRL_ALS         0x02U /* bit1=enable，bit3=0选择ALS。 */
#define LTR390_CTRL_UVS         0x0AU /* bit1=enable，bit3=1选择UVS。 */
#define I2C_TIMEOUT             100000UL
static uint32_t m_last_error;

/* I2C bus clear: a reset in the middle of a slave read can leave SDA low.
 * S0D1 is open drain: writing 1 RELEASES the line, never drives it high.
 * Only the documented SDA/SCL pins are touched; no supply is switched. */
static bool release_scl(void)
{
    uint16_t left = 1000U;
    nrf_gpio_pin_set(UV_PIN_I2C_SCL);
    while (!nrf_gpio_pin_read(UV_PIN_I2C_SCL) && --left) nrf_delay_us(1U);
    nrf_delay_us(5U);
    return left != 0U;
}

static bool clear_bus(void)
{
    uint8_t pulse;
    NRF_TWI0->ENABLE = TWI_ENABLE_ENABLE_Disabled;
    NRF_TWI0->PSELSDA = 0xFFFFFFFFUL;
    NRF_TWI0->PSELSCL = 0xFFFFFFFFUL;
    nrf_gpio_pin_set(UV_PIN_I2C_SDA);
    nrf_gpio_pin_set(UV_PIN_I2C_SCL);
    nrf_gpio_cfg(UV_PIN_I2C_SDA, NRF_GPIO_PIN_DIR_OUTPUT,
                 NRF_GPIO_PIN_INPUT_CONNECT, NRF_GPIO_PIN_PULLUP,
                 NRF_GPIO_PIN_S0D1, NRF_GPIO_PIN_NOSENSE);
    nrf_gpio_cfg(UV_PIN_I2C_SCL, NRF_GPIO_PIN_DIR_OUTPUT,
                 NRF_GPIO_PIN_INPUT_CONNECT, NRF_GPIO_PIN_PULLUP,
                 NRF_GPIO_PIN_S0D1, NRF_GPIO_PIN_NOSENSE);
    if (!release_scl()) return false;
    for (pulse = 0U; pulse < 9U && !nrf_gpio_pin_read(UV_PIN_I2C_SDA); pulse++)
    {
        nrf_gpio_pin_clear(UV_PIN_I2C_SCL);
        nrf_delay_us(5U);
        if (!release_scl()) return false;
    }
    /* STOP: SDA low while SCL low, release SCL, then release SDA. */
    nrf_gpio_pin_clear(UV_PIN_I2C_SCL);
    nrf_gpio_pin_clear(UV_PIN_I2C_SDA);
    nrf_delay_us(5U);
    if (!release_scl()) { nrf_gpio_pin_set(UV_PIN_I2C_SDA); return false; }
    nrf_gpio_pin_set(UV_PIN_I2C_SDA);
    nrf_delay_us(5U);
    NRF_LOG_INFO("I2C released SDA=%u SCL=%u pulses=%u",
                 nrf_gpio_pin_read(UV_PIN_I2C_SDA),
                 nrf_gpio_pin_read(UV_PIN_I2C_SCL), pulse);
    return nrf_gpio_pin_read(UV_PIN_I2C_SDA) != 0U;
}

static bool wait_event(volatile uint32_t * event)
{
    /* event参数是某个 EVENTS_xxx 寄存器的地址。硬件完成动作后把它置1。 */
    uint32_t timeout = I2C_TIMEOUT;
    while ((*event == 0U) && (--timeout != 0U))
    {
        if (NRF_TWI0->EVENTS_ERROR != 0U)
        {
            m_last_error = NRF_TWI0->ERRORSRC;
            return false;
        }
    }
    *event = 0U;
    if (timeout == 0U) m_last_error = 0x80000000UL;
    return timeout != 0U;
}

/* 错误退出也必须释放总线；不让一次NACK/超时污染后续事务。 */
static bool abort_transfer(void)
{
    uint32_t timeout = I2C_TIMEOUT;
    NRF_TWI0->SHORTS = 0U;
    NRF_TWI0->EVENTS_STOPPED = 0U;
    NRF_TWI0->TASKS_RESUME = 1U;
    NRF_TWI0->TASKS_STOP = 1U;
    while ((NRF_TWI0->EVENTS_STOPPED == 0U) && (--timeout != 0U)) { }
    NRF_TWI0->ENABLE = TWI_ENABLE_ENABLE_Disabled;
    NRF_TWI0->EVENTS_ERROR = 0U;
    NRF_TWI0->ERRORSRC = NRF_TWI0->ERRORSRC;
    NRF_TWI0->ENABLE = TWI_ENABLE_ENABLE_Enabled;
    return false;
}

static void prepare_transfer(void)
{
    NRF_TWI0->SHORTS = 0U;
    NRF_TWI0->EVENTS_ERROR = 0U;
    NRF_TWI0->ERRORSRC = NRF_TWI0->ERRORSRC;
    NRF_TWI0->EVENTS_TXDSENT = 0U;
    NRF_TWI0->EVENTS_RXDREADY = 0U;
    NRF_TWI0->EVENTS_STOPPED = 0U;
    NRF_TWI0->TASKS_RESUME = 1U;
}

static bool write_register(uint8_t reg, uint8_t value)
{
    /* 一次寄存器写入的总线顺序：START + 地址(W) + 寄存器号 + 数据 + STOP。 */
    NRF_TWI0->ADDRESS = LTR390_ADDRESS;
    prepare_transfer();
    /* Nordic事件寄存器不会自动清零，开始新事务前必须手动清零。 */
    NRF_TWI0->EVENTS_TXDSENT = 0;
    NRF_TWI0->EVENTS_STOPPED = 0;
    NRF_TWI0->TASKS_STARTTX = 1; /* TASK寄存器写1，硬件产生START并进入发送。 */

    NRF_TWI0->TXD = reg; /* 先告诉传感器接下来要访问哪个内部寄存器。 */
    if (!wait_event(&NRF_TWI0->EVENTS_TXDSENT)) return abort_transfer();
    NRF_TWI0->TXD = value; /* 再发送写入该寄存器的配置值。 */
    if (!wait_event(&NRF_TWI0->EVENTS_TXDSENT)) return abort_transfer();

    NRF_TWI0->TASKS_STOP = 1;
    if (!wait_event(&NRF_TWI0->EVENTS_STOPPED)) return abort_transfer();
    return true;
}

static bool read_registers(uint8_t reg, uint8_t * data, uint8_t count)
{
    uint8_t index;
    if ((data == NULL) || (count == 0U)) return false;
    prepare_transfer();
    NRF_TWI0->ADDRESS = LTR390_ADDRESS;
    NRF_TWI0->EVENTS_TXDSENT = 0;
    NRF_TWI0->TASKS_STARTTX = 1;
    NRF_TWI0->TXD = reg; /* 先用写方向设置传感器的内部地址指针。 */
    if (!wait_event(&NRF_TWI0->EVENTS_TXDSENT)) return abort_transfer();

    NRF_TWI0->EVENTS_RXDREADY = 0;
    NRF_TWI0->EVENTS_STOPPED = 0;
    /* SHORTS必须在STARTRX前设置，避免BLE中断延迟使第一个字节越界。 */
    NRF_TWI0->SHORTS = (count == 1U) ? TWI_SHORTS_BB_STOP_Msk :
                                                   TWI_SHORTS_BB_SUSPEND_Msk;
    /* 重复起始后切换读方向，传感器开始返回数据。 */
    NRF_TWI0->TASKS_STARTRX = 1;
    for (index = 0; index < count; index++)
    {
        /* SHORTS让硬件在收到一个字节后自动SUSPEND，最后一个字节自动STOP。 */
        if (!wait_event(&NRF_TWI0->EVENTS_RXDREADY)) return abort_transfer();
        data[index] = (uint8_t)NRF_TWI0->RXD;
        if (index + 1U < count)
        {
            NRF_TWI0->SHORTS = (index + 2U == count) ? TWI_SHORTS_BB_STOP_Msk :
                                                       TWI_SHORTS_BB_SUSPEND_Msk;
            NRF_TWI0->TASKS_RESUME = 1U;
        }
    }
    if (!wait_event(&NRF_TWI0->EVENTS_STOPPED)) return abort_transfer();
    NRF_TWI0->SHORTS = 0;
    return true;
}

bool LTR390_Init(void)
{
    uint8_t part_id = 0;
    bool released;

    m_last_error = 0U;
    released = clear_bus();
    NRF_TWI0->ENABLE = TWI_ENABLE_ENABLE_Disabled;
    /* 参照SDK nrfx_twi：开漏S0D1、接入输入缓冲；内部上拉不能替代PCB上拉。 */
    nrf_gpio_cfg(UV_PIN_I2C_SDA, NRF_GPIO_PIN_DIR_INPUT,
                 NRF_GPIO_PIN_INPUT_CONNECT, NRF_GPIO_PIN_PULLUP,
                 NRF_GPIO_PIN_S0D1, NRF_GPIO_PIN_NOSENSE);
    nrf_gpio_cfg(UV_PIN_I2C_SCL, NRF_GPIO_PIN_DIR_INPUT,
                 NRF_GPIO_PIN_INPUT_CONNECT, NRF_GPIO_PIN_PULLUP,
                 NRF_GPIO_PIN_S0D1, NRF_GPIO_PIN_NOSENSE);

    /* PSELSDA/PSELSCL把TWI0外设连接到PCB使用的P0.26/P0.27。 */
    NRF_TWI0->PSELSDA = UV_PIN_I2C_SDA;
    NRF_TWI0->PSELSCL = UV_PIN_I2C_SCL;

    /* 100 kHz standard mode gives more margin for board capacitance and pullups. */
    NRF_TWI0->FREQUENCY = TWI_FREQUENCY_FREQUENCY_K100;
    NRF_TWI0->ENABLE = TWI_ENABLE_ENABLE_Enabled;
    if (!released)
    {
        m_last_error = 0x40000000UL;
        NRF_LOG_WARNING("I2C held low after bus clear SDA=%u SCL=%u",
            nrf_gpio_pin_read(UV_PIN_I2C_SDA), nrf_gpio_pin_read(UV_PIN_I2C_SCL));
        return false;
    }

    /* PART_ID必须为0xB2；否则可能是地址、焊接或器件问题。 */
    if (!read_registers(LTR390_REG_PART_ID, &part_id, 1U) ||
        part_id != LTR390_EXPECTED_PART_ID)
    {
        /* ERRORSRC bit1=地址NACK，bit2=数据NACK；0x80000000是本驱动超时。 */
        NRF_LOG_WARNING("LTR init fail id=0x%02x err=0x%08lx pins=%lu/%lu",
            part_id, (unsigned long)m_last_error,
            (unsigned long)nrf_gpio_pin_read(UV_PIN_I2C_SDA),
            (unsigned long)nrf_gpio_pin_read(UV_PIN_I2C_SCL));
        return false;
    }

    NRF_LOG_INFO("LTR390 identified id=0x%02x at 100kHz", part_id);

    /* 先停止测量，再设置18位/100ms、3倍增益，最后开启UV模式。 */
    return write_register(LTR390_REG_MAIN_CTRL, 0x00U) &&
           write_register(LTR390_REG_MEAS_RATE, LTR390_RATE_18BIT_100MS) &&
           write_register(LTR390_REG_GAIN, LTR390_GAIN_X3) &&
           write_register(LTR390_REG_MAIN_CTRL, LTR390_CTRL_UVS);
}

bool LTR390_ReadUV(uint32_t * raw)
{
    uint8_t data[3];
    if (!read_registers(LTR390_REG_UVS_DATA, data, 3U)) return false;
    /* LTR-390低字节在前：data[0]是bit7..0，data[2]是bit23..16。 */
    *raw = data[0] | ((uint32_t)data[1] << 8) |
           ((uint32_t)data[2] << 16);
    return true;
}

bool LTR390_ReadALS(uint32_t * raw)
{
    uint8_t data[3];
    if (!read_registers(LTR390_REG_ALS_DATA, data, 3U)) return false;
    *raw = data[0] | ((uint32_t)data[1] << 8) |
           ((uint32_t)data[2] << 16);
    return true;
}

bool LTR390_ReadRegisters(uint8_t * ctrl, uint8_t * rate, uint8_t * gain,
                          uint8_t * id, uint8_t * status)
{
    return read_registers(LTR390_REG_MAIN_CTRL, ctrl, 1U) &&
           read_registers(LTR390_REG_MEAS_RATE, rate, 1U) &&
           read_registers(LTR390_REG_GAIN, gain, 1U) &&
           read_registers(LTR390_REG_PART_ID, id, 1U) &&
           read_registers(LTR390_REG_STATUS, status, 1U);
}

bool LTR390_SetALS(bool als)
{
    return write_register(LTR390_REG_MAIN_CTRL,
                          als ? LTR390_CTRL_ALS : LTR390_CTRL_UVS);
}

bool LTR390_Standby(void)
{
    return write_register(LTR390_REG_MAIN_CTRL, 0x00U);
}
