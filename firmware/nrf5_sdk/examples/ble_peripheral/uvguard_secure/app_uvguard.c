/*
 * UV-Guard业务层
 *
 * 每秒执行的完整数据流：
 * RTC1/app_timer到期 -> m_sample_due=true -> app_uvguard_process()
 * -> I2C读取LTR-390 -> SAADC读取电池 -> 判断报警/充电
 * -> 更新灯环 -> 按协议打包 -> FF01/FF03 Notify给手机。
 */
#include <stdbool.h>
#include <string.h>
#include "app_error.h"
#include "app_timer.h"
#include "nrf_log.h"
#include "app_uvguard.h"
#include "battery.h"
#include "bsp_uvguard.h"
#include "led_ring.h"
#include "ltr390.h"

/* FF01状态字节的位定义；一个uint8_t可以同时携带多个状态。 */
#define FLAG_SENSOR_ERROR     (1U << 0)
#define FLAG_ALERT_ACTIVE     (1U << 1)
#define FLAG_CHARGING         (1U << 2)
#define FLAG_ALERTS_ENABLED   (1U << 3)
#define FLAG_ALS_ACTIVE       (1U << 4) /* 新App据此暂停显示UVI。 */

/* FF03诊断字节的位定义，与手机/电脑解析程序保持一致。 */
#define DIAG_ALS_TEST         (1U << 3)
#define DIAG_ALARM_TEST       (1U << 4)

#define SAMPLE_PERIOD_MS      1000U
#define DEFAULT_THRESHOLD_X10 30U   /* 30代表UVI 3.0，避免浮点数。 */
#define CAUTION_UVI_X10       10U   /* 10代表UVI 1.0。 */
#define INVALID_UVI_X10       0xFFFFU
#define ALS_TEST_SECONDS      15U
#define ALARM_TEST_SECONDS    10U
#define LED_TEST_SECONDS      9U  /* 红、绿、蓝各显示3秒，便于确认三色通道。 */
#define BOOT_LED_SECONDS      0U /* 0=关闭上电持续红灯；单位为秒。 */

/*
 * LTR-390当前配置为18位、100ms积分、3倍增益。
 * 标称灵敏度2300 counts/UVI对应18倍增益、20位(400ms)。
 * 当前3倍、18位(100ms)：每UVI计数=2300*(3/18)*(100/400)=2300/24。
 * 所以UVI*10=raw*240/2300。窗口、光谱和安装角度仍须实物标定。
 */
#define UV_RAW_TO_UVI_X10_NUMERATOR 240UL
#define UV_RAW_TO_UVI_X10_DENOMINATOR 2300UL

/* Nordic宏创建一个app_timer控制块；底层由RTC1驱动。 */
APP_TIMER_DEF(m_sample_timer);

static volatile bool m_sample_due;
static ble_uvguard_t * m_ble;
static bool m_sensor_ok;
static bool m_alerts_enabled = true;
static bool m_direct_pin_test;
static bool m_als_test;
static uint8_t m_als_seconds;
static uint8_t m_alarm_seconds;
static uint8_t m_led_test_seconds;
static uint16_t m_boot_led_seconds;
static bool m_button_was_pressed;
static uint16_t m_threshold_x10 = DEFAULT_THRESHOLD_X10;
static uint32_t m_sample_count;
static uint8_t m_sensor_retry_div;
static uint8_t m_diag_send_div;
static volatile bool m_als_request;
static bool m_sensor_settling;
static volatile uint8_t m_requested_brightness = 10U;
static volatile bool m_brightness_request;

/* 当前USE_SCHEDULER=0，回调可在中断内运行，只置标志。 */
static void sample_timer_handler(void * context)
{
    (void)context;
    m_sample_due = true;
}

/* CRC-8/ATM：多项式0x07，初值0；手机用同一算法检测传输错误。 */
static uint8_t crc8_atm(uint8_t const * data, uint8_t length)
{
    uint8_t crc = 0U;
    uint8_t index;
    uint8_t bit;

    for (index = 0U; index < length; index++)
    {
        crc ^= data[index];
        for (bit = 0U; bit < 8U; bit++)
        {
            crc = (crc & 0x80U) ?
                  (uint8_t)((crc << 1) ^ 0x07U) :
                  (uint8_t)(crc << 1);
        }
    }
    return crc;
}

/* BLE协议规定本项目多字节整数使用Little Endian（低字节先发）。 */
static void put_u16_le(uint8_t * destination, uint16_t value)
{
    destination[0] = (uint8_t)value;
    destination[1] = (uint8_t)(value >> 8);
}

static void put_u32_le(uint8_t * destination, uint32_t value)
{
    destination[0] = (uint8_t)value;
    destination[1] = (uint8_t)(value >> 8);
    destination[2] = (uint8_t)(value >> 16);
    destination[3] = (uint8_t)(value >> 24);
}

void app_uvguard_init(ble_uvguard_t * service)
{
    /* service由main.c创建，保存后每秒用它发送FF01/FF03。 */
    m_ble = service;

    BSP_UVGuard_Init(); /* GPIO：按键、充电状态、灯数据脚。 */
    LedRing_Init();     /* PWM0：准备SK6805的800kHz波形。 */
    /* 上电先发送熄灯帧，清除灯珠可能保留的旧颜色。 */
    m_boot_led_seconds = BOOT_LED_SECONDS;
    LedRing_Show(0U, 0U, 0U);
    Battery_Init();     /* SAADC：AIN0、电池分压、12位。 */
    m_sensor_ok = LTR390_Init(); /* TWI0：配置并检查PART_ID。 */
    m_sensor_settling = m_sensor_ok;

    APP_ERROR_CHECK(app_timer_create(&m_sample_timer,
                                      APP_TIMER_MODE_REPEATED,
                                      sample_timer_handler));
    APP_ERROR_CHECK(app_timer_start(m_sample_timer,
                                     APP_TIMER_TICKS(SAMPLE_PERIOD_MS),
                                     NULL));

    /* 不等第一个1秒周期，上电后立即采样一次。 */
    m_sample_due = true;
}

void app_uvguard_command(uint8_t const * data, uint16_t length)
{
    uint16_t requested_threshold;

    if ((data == NULL) || (length == 0U)) return;

    /* data[0]是命令号，其余字节是该命令的参数。 */
    switch (data[0])
    {
        case 0x10: /* 参数：uint16小端，报警阈值UVI*10。 */
            if (length == 3U)
            {
                requested_threshold = data[1] | ((uint16_t)data[2] << 8);
                if ((requested_threshold > 0U) && (requested_threshold <= 150U))
                    m_threshold_x10 = requested_threshold;
            }
            break;

        case 0x11: /* 参数：0=静音，1=允许报警。 */
            if ((length == 2U) && (data[1] <= 1U))
                m_alerts_enabled = data[1] != 0U;
            break;

        case 0x12: /* 灯环三色自检：红、绿、蓝各3秒。 */
            m_led_test_seconds = LED_TEST_SECONDS;
            break;

        case 0x16: /* 强制报警测试10秒。 */
            m_alarm_seconds = ALARM_TEST_SECONDS;
            break;

        case 0x13: /* P0.15直接输出高电平，供万用表诊断。 */
            m_direct_pin_test = true;
            break;

        case 0x14: /* 退出P0.15直接高电平，恢复PWM灯环控制。 */
            m_direct_pin_test = false;
            break;

        case 0x15: /* LTR-390切到可见光ALS模式15秒。 */
            /* BLE回调可能在中断内，只传递请求，避免与主循环抢同一I2C外设。 */
            m_als_request = true;
            break;

        case 0x17: /* 新版App：参数0~100，统一灯环亮度百分比。 */
            if ((length == 2U) && (data[1] <= 100U))
            {
                m_requested_brightness = data[1];
                m_brightness_request = true;
            }
            break;

        default:
            break;
    }
}

void app_uvguard_process(void)
{
    uint32_t raw_sensor = 0U;
    uint16_t uvi_x10 = INVALID_UVI_X10;
    uint16_t battery_mv;
    int16_t adc_raw;
    uint8_t battery_percent;
    uint8_t status_flags = 0U;
    uint8_t realtime_frame[14];
    uint8_t diagnostic_frame[20];
    uint8_t ctrl = 0U, rate = 0U, gain = 0U, id = 0U, status = 0U;

    /* 所有传感器访问都留在主循环，不能在BLE事件中断里等待I2C。 */
    if (m_brightness_request)
    {
        m_brightness_request = false;
        LedRing_SetBrightness(m_requested_brightness);
    }
    if (m_als_request)
    {
        m_als_request = false;
        if (m_sensor_ok && LTR390_SetALS(true))
        {
            m_als_test = true;
            m_als_seconds = ALS_TEST_SECONDS;
            m_sensor_settling = true; /* 切换后丢弃一次周期，等待新积分结果。 */
        }
    }

    /* 主循环运行很快；没有1秒标志就立即返回，避免重复采样。 */
    if (!m_sample_due) return;
    m_sample_due = false;

    /* SW1低电平有效；按住约1秒可启动灯环三色自检。 */
    if (BSP_ButtonPressed())
    {
        if (!m_button_was_pressed)
            m_led_test_seconds = LED_TEST_SECONDS;
        m_button_was_pressed = true;
    }
    else
    {
        m_button_was_pressed = false;
    }

	/* 初始化失败后每10秒重试，避免传感器缺失时持续空转。 */
	if (!m_sensor_ok)
	{
		if (++m_sensor_retry_div >= 10U)
		{
			m_sensor_retry_div = 0U;
			m_sensor_ok = LTR390_Init();
            if (m_sensor_ok)
            {
                if (m_als_test) m_sensor_ok = LTR390_SetALS(true);
                m_sensor_settling = m_sensor_ok;
            }
		}
	}

    if (m_sensor_ok && m_sensor_settling)
    {
        m_sensor_settling = false;
    }
    else if (m_sensor_ok &&
             (m_als_test ? LTR390_ReadALS(&raw_sensor) : LTR390_ReadUV(&raw_sensor)))
    {
        if (!m_als_test)
            uvi_x10 = (uint16_t)((raw_sensor * UV_RAW_TO_UVI_X10_NUMERATOR +
                                 UV_RAW_TO_UVI_X10_DENOMINATOR / 2U) /
                                 UV_RAW_TO_UVI_X10_DENOMINATOR);
    }
    else
    {
        m_sensor_ok = false;
        status_flags |= FLAG_SENSOR_ERROR;
    }

    battery_percent = Battery_Read(&battery_mv, &adc_raw);
    if ((m_sample_count < 5U) || (m_sample_count % 10U == 0U))
        NRF_LOG_INFO("BAT raw=%d mV=%u percent=%u", adc_raw, battery_mv, battery_percent);

    if (BSP_IsCharging()) status_flags |= FLAG_CHARGING;
    if (m_alerts_enabled) status_flags |= FLAG_ALERTS_ENABLED;
    if (m_als_test) status_flags |= FLAG_ALS_ACTIVE;

    if ((m_alarm_seconds > 0U) ||
        (m_alerts_enabled && (uvi_x10 != INVALID_UVI_X10) &&
         (uvi_x10 >= m_threshold_x10)))
    {
        status_flags |= FLAG_ALERT_ACTIVE;
    }

    /* 状态优先级：诊断高电平 > 传感器错误 > 报警 > 注意 > 充电 > 正常。 */
    if (m_direct_pin_test)
        BSP_LedPinHigh();
    else if (m_boot_led_seconds > 0U)
        LedRing_Show(48U, 0U, 0U);  /* 上电自检：高亮红色。 */
    else if (m_led_test_seconds > 6U)
        LedRing_Show(24U, 0U, 0U);  /* 自检第1段：红。 */
    else if (m_led_test_seconds > 3U)
        LedRing_Show(0U, 24U, 0U);  /* 自检第2段：绿。 */
    else if (m_led_test_seconds > 0U)
        LedRing_Show(0U, 0U, 24U);  /* 自检第3段：蓝。 */
    else if (status_flags & FLAG_SENSOR_ERROR)
        LedRing_Show(12U, 0U, 12U); /* 紫色：传感器错误。 */
    else if (status_flags & FLAG_ALERT_ACTIVE)
        LedRing_Show(24U, 0U, 0U);  /* 红色：紫外线报警。 */
    else if ((uvi_x10 != INVALID_UVI_X10) && (uvi_x10 >= CAUTION_UVI_X10))
        LedRing_Show(20U, 12U, 0U); /* 橙色：注意防晒。 */
    else if (status_flags & FLAG_CHARGING)
        LedRing_Show(0U, 0U, 16U);  /* 蓝色：正在充电。 */
    else
        LedRing_Show(0U, 0U, 0U);  /* 熄灯 */
//LedRing_Show(0U, 0U, 24U);   /* R=0, G=0, B=24 → 纯蓝 */
    /*
     * FF01实时帧，共14字节：
     * A5 | 01 | 10 | sample(4) | UVIx10(2) | raw低16位(2)
     * | battery%(1) | flags(1) | CRC(1)
     */
    realtime_frame[0] = 0xA5U;
    realtime_frame[1] = 0x01U;
    realtime_frame[2] = 10U; /* 有效负载长度，不含A5/命令/长度/CRC。 */
    put_u32_le(&realtime_frame[3], m_sample_count);
    put_u16_le(&realtime_frame[7], uvi_x10);
    put_u16_le(&realtime_frame[9],
               raw_sensor > 65535U ? 65535U : (uint16_t)raw_sensor);
    realtime_frame[11] = battery_percent;
    realtime_frame[12] = status_flags;
    realtime_frame[13] = crc8_atm(realtime_frame, 13U);
	(void)ble_uvguard_send_realtime(m_ble, realtime_frame, sizeof(realtime_frame));
     /* 诊断帧每 5 秒发一次即可（原来每秒发，数据变化很慢） */
	
 

    /* 读回关键寄存器，帮助判断“写配置成功但传感器数据异常”的问题。 */
    if (m_sensor_ok)
        (void)LTR390_ReadRegisters(&ctrl, &rate, &gain, &id, &status);
    /* 每10秒保留一条实测记录，便于区分“数据为0”和“I2C访问失败”。
     * ok只说明本轮驱动读取成功，不代表光学标定已经完成。 */
    if ((m_sample_count < 5U) || (m_sample_count % 10U == 0U))
        NRF_LOG_INFO("SENSOR ok=%u als=%u raw=%lu uvi_x10=%u id=0x%02x status=0x%02x",
                     m_sensor_ok, m_als_test, (unsigned long)raw_sensor,
                     uvi_x10, id, status);

    /* FF03诊断帧正好20字节，适配默认BLE通知载荷。 */
    memset(diagnostic_frame, 0, sizeof(diagnostic_frame));
    diagnostic_frame[0] = 0xA5U;
    diagnostic_frame[1] = 0x02U;
    diagnostic_frame[2] = 16U;
    put_u16_le(&diagnostic_frame[3], (uint16_t)m_sample_count++);
    diagnostic_frame[5] = ctrl;
    diagnostic_frame[6] = rate;
    diagnostic_frame[7] = gain;
    diagnostic_frame[8] = id;
    diagnostic_frame[9] = status;
    put_u32_le(&diagnostic_frame[10], raw_sensor);
    put_u16_le(&diagnostic_frame[14], (uint16_t)adc_raw);
    put_u16_le(&diagnostic_frame[16], battery_mv);
    diagnostic_frame[18] = (m_als_test ? DIAG_ALS_TEST : 0U) |
                           ((m_alarm_seconds || m_led_test_seconds) ? DIAG_ALARM_TEST : 0U) |
                           0x80U; /* bit7标识配套v2固件，支持正确ALS和0x17亮度。 */
    diagnostic_frame[19] = crc8_atm(diagnostic_frame, 19U);
    if (++m_diag_send_div >= 5U)
    {
        m_diag_send_div = 0U;
        (void)ble_uvguard_send_diagnostic(m_ble, diagnostic_frame,
                                           sizeof(diagnostic_frame));
    }

    if (m_alarm_seconds > 0U) m_alarm_seconds--;
    if (m_led_test_seconds > 0U) m_led_test_seconds--;
    if (m_boot_led_seconds > 0U) m_boot_led_seconds--;

    if (m_als_test && (m_als_seconds > 0U))
    {
        m_als_seconds--;
        if (m_als_seconds == 0U)
        {
            m_als_test = false;
            m_sensor_ok = LTR390_SetALS(false); /* 恢复UVS并等待新积分结果。 */
            m_sensor_settling = m_sensor_ok;
        }
    }
}
