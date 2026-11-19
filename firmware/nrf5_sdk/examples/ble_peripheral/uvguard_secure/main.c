/*
 * UV-Guard Keil5 主程序
 *
 * 从复位到运行的调用顺序：
 * Reset_Handler -> SystemInit -> C运行库 -> main()
 * main()完成定时器、SoftDevice、GAP、GATT、业务硬件和广播初始化，
 * 然后进入永久主循环。
 *
 * SoftDevice是Nordic提供的BLE协议栈，负责射频、中断和链路时序；
 * 本项目负责告诉它“叫什么名字、有哪些服务、要发送哪些数据”。
 */
#include <stdint.h>
#include <string.h>
#include "app_error.h"
#include "app_timer.h"
#include "ble_advdata.h"
#include "ble_advertising.h"
#include "ble_conn_params.h"
#include "nrf_log.h"
#include "nrf_log_ctrl.h"
#include "nrf_log_default_backends.h"
#include "nrf_ble_gatt.h"
#include "nrf_ble_qwr.h"
#include "nrf_pwr_mgmt.h"
#include "nrf_sdh.h"
#include "nrf_sdh_ble.h"
#include "app_uvguard.h"
#include "ble_uvguard.h"
#include "uvguard_security.h"
#include "ble_dfu.h"
#include "nrf_dfu_ble_svci_bond_sharing.h"
#include "nrf_gpio.h"
#include "nrf_fstorage.h"
#include "bsp_uvguard.h"
#include "led_ring.h"
#include "ltr390.h"
#include "idle_power_policy.h"

#define DEVICE_NAME           "UV-Guard" /* 手机扫描列表显示的名称。 */
#define APP_BLE_CONN_CFG_TAG  1U /* SoftDevice连接配置编号，初始化/广播必须一致。 */
/*
 * 这里必须写成裸数字3，不能写3U。
 * NRF_SDH_BLE_OBSERVER会把该参数直接拼进链接段名；写3U会生成
 * sdh_ble_observers3U，SoftDevice只遍历sdh_ble_observers0..7，导致
 * 连接事件回调永远不执行，connection_handle一直保持0xFFFF。
 */
#define APP_BLE_OBSERVER_PRIO 3 /* BLE事件回调优先级，数值越小越早执行。 */
#define APP_ADV_INTERVAL      400U /* 快速阶段：400*0.625=250ms，更容易重新发现。 */
#define APP_ADV_DURATION     3000U /* 单位10ms：快速广播30秒后切换到慢速。 */
#define APP_ADV_SLOW_INTERVAL 1600U /* 慢速阶段：1000ms，减少长时间等待的功耗。 */
#define IDLE_POWER_OFF_MS     180000UL /* 连续无BLE连接3分钟后System OFF。 */

/* 这些宏创建并初始化SDK模块需要的静态控制结构。 */
NRF_BLE_GATT_DEF(m_gatt);          /* GATT层管理实例。 */
NRF_BLE_QWR_DEF(m_qwr);            /* Queued Write实例。 */
BLE_ADVERTISING_DEF(m_advertising);/* 广播状态机实例。 */
static ble_uvguard_t m_uv_service;
static ble_uuid_t m_adv_uuid;
static uint32_t m_connection_count;
static volatile bool m_adv_retry;
static volatile uint32_t m_adv_retry_tick;
static volatile bool m_entering_dfu;
static volatile bool m_power_connected;
static volatile uint32_t m_disconnected_at;
static bool m_wake_button_released;

static void dfu_event(ble_dfu_buttonless_evt_type_t event)
{
    if (event == BLE_DFU_EVT_BOOTLOADER_ENTER_PREPARE)
    {
        ble_adv_modes_config_t config = m_advertising.adv_modes_config;
        m_entering_dfu = true;
        m_adv_retry = false;
        config.ble_adv_on_disconnect_disabled = true;
        ble_advertising_modes_config_set(&m_advertising, &config);
        /* Bonded backend persists peer keys and Service Changed before resetting. */
        if (m_uv_service.connection_handle != BLE_CONN_HANDLE_INVALID)
            (void)sd_ble_gap_disconnect(m_uv_service.connection_handle,
                                       BLE_HCI_REMOTE_USER_TERMINATED_CONNECTION);
        NRF_LOG_INFO("DFU preparing bonded bootloader");
    }
    else if (event == BLE_DFU_EVT_BOOTLOADER_ENTER_FAILED ||
             event == BLE_DFU_EVT_RESPONSE_SEND_ERROR)
    {
        NRF_LOG_ERROR("DFU entry failed; resetting safely to app");
        NVIC_SystemReset();
    }
}

/* 协商/断开期间的状态变化是正常现象，不能因此触发APP_ERROR_CHECK重启。 */
static void ble_runtime_result(uint32_t error)
{
    if ((error != NRF_SUCCESS) &&
        (error != NRF_ERROR_INVALID_STATE) &&
        (error != BLE_ERROR_INVALID_CONN_HANDLE) &&
        (error != NRF_ERROR_BUSY))
    {
        NRF_LOG_WARNING("BLE runtime error=%lu", (unsigned long)error);
    }
}

static void advertising_error_handler(uint32_t error)
{
    NRF_LOG_WARNING("ADV restart error=%lu", (unsigned long)error);
    m_adv_retry_tick = app_timer_cnt_get();
    m_adv_retry = true;
}

static void advertising_event_handler(ble_adv_evt_t event)
{
    if ((event == BLE_ADV_EVT_FAST) || (event == BLE_ADV_EVT_SLOW))
    {
        NRF_LOG_INFO("ADV running");
        m_adv_retry = false;
    }
    else if (event == BLE_ADV_EVT_IDLE)
    {
        m_adv_retry_tick = app_timer_cnt_get();
        m_adv_retry = true;
    }
}

static void log_connection_params(ble_gap_conn_params_t const * params)
{
    /* 日志记录协议原始单位：interval*1.25ms、timeout*10ms。 */
    NRF_LOG_INFO("LINK interval_units=%u latency=%u timeout_units=%u",
                 params->max_conn_interval, params->slave_latency,
                 params->conn_sup_timeout);
}

static void qwr_error_handler(uint32_t error)
{
    /* APP_ERROR_HANDLER会记录错误并进入SDK统一错误处理。 */
    APP_ERROR_HANDLER(error);
}

static void ble_event_handler(ble_evt_t const * event, void * context)
{
    (void)context;
    switch (event->header.evt_id)
    {
        case BLE_GAP_EVT_CONNECTED:
            m_power_connected = true; /* 任何实际BLE连接期间均不自动关机。 */
            NRF_LOG_INFO("CONNECTED count=%lu handle=%u",
                         (unsigned long)++m_connection_count,
                         event->evt.gap_evt.conn_handle);
            log_connection_params(&event->evt.gap_evt.params.connected.conn_params);
            /* conn_handle相当于本次连接的“编号”，后续Notify必须使用它。 */
            APP_ERROR_CHECK(nrf_ble_qwr_conn_handle_assign(&m_qwr,
                            event->evt.gap_evt.conn_handle));
            ble_uvguard_set_connection(&m_uv_service, event->evt.gap_evt.conn_handle);
            break;
        case BLE_GAP_EVT_DISCONNECTED:
            m_disconnected_at = app_timer_cnt_get(); /* 每次断开重新计满180秒。 */
            m_power_connected = false;
            /* 0x08=链路超时；0x13=手机主动断开；0x16=本机主动断开。 */
            NRF_LOG_WARNING("DISCONNECTED reason=0x%02x",
                            event->evt.gap_evt.params.disconnected.reason);
            ble_uvguard_set_connection(&m_uv_service, BLE_CONN_HANDLE_INVALID);
            /* ble_advertising.c also observes this event and restarts the
               configured advertising mode. Do not start it twice here. */
            break;
        /* Peer Manager restores bonded CCCDs and system attributes. */
        case BLE_GAP_EVT_CONN_PARAM_UPDATE:
            log_connection_params(&event->evt.gap_evt.params.conn_param_update.conn_params);
            break;
        case BLE_GAP_EVT_PHY_UPDATE_REQUEST:
        {
            /* 按Nordic示例应答PHY请求，让双方协商支持的无线速率。 */
            ble_gap_phys_t const phys = { BLE_GAP_PHY_AUTO, BLE_GAP_PHY_AUTO };
            ble_runtime_result(sd_ble_gap_phy_update(event->evt.gap_evt.conn_handle,
                                                     &phys));
            break;
        }
        /* Peer Manager handles pairing; do not reply a second time here. */
        case BLE_GATTS_EVT_TIMEOUT:
            /* ATT事务已超时，这条GATT链路不能继续使用，需要重新连接。 */
            NRF_LOG_WARNING("GATTS timeout: reconnect required");
            ble_runtime_result(sd_ble_gap_disconnect(event->evt.gatts_evt.conn_handle,
                                 BLE_HCI_REMOTE_USER_TERMINATED_CONNECTION));
            break;
        case BLE_GATTC_EVT_TIMEOUT:
            NRF_LOG_WARNING("GATTC timeout: reconnect required");
            ble_runtime_result(sd_ble_gap_disconnect(event->evt.gattc_evt.conn_handle,
                                 BLE_HCI_REMOTE_USER_TERMINATED_CONNECTION));
            break;
        default:
            break;
    }
}
NRF_SDH_BLE_OBSERVER(m_ble_observer, APP_BLE_OBSERVER_PRIO,
                     ble_event_handler, NULL);

static void ble_stack_init(void)
{
    uint32_t ram_start = 0;

    /* 请求启动S132 SoftDevice：启用BLE所需时钟、中断和内存区域。 */
    APP_ERROR_CHECK(nrf_sdh_enable_request());

    /* 根据sdk_config.h计算BLE协议栈要占多少RAM，结果写入ram_start。 */
    APP_ERROR_CHECK(nrf_sdh_ble_default_cfg_set(APP_BLE_CONN_CFG_TAG, &ram_start));

    /* 使用上述RAM起始地址真正启用BLE协议栈。 */
    APP_ERROR_CHECK(nrf_sdh_ble_enable(&ram_start));
}

static void gap_init(void)
{
    ble_gap_conn_sec_mode_t security;
    ble_gap_conn_params_t params;
    /* GAP负责设备名称、广播和连接参数；OPEN表示无需配对即可访问。 */
    BLE_GAP_CONN_SEC_MODE_SET_OPEN(&security);
    APP_ERROR_CHECK(sd_ble_gap_device_name_set(&security,
                    (uint8_t const *)DEVICE_NAME, strlen(DEVICE_NAME)));
    memset(&params, 0, sizeof(params));
    /* 排障阶段先求稳定：30~60ms、零跳过；最终实际参数由手机协商决定。 */
    params.min_conn_interval = MSEC_TO_UNITS(30, UNIT_1_25_MS);
    params.max_conn_interval = MSEC_TO_UNITS(60, UNIT_1_25_MS);
    params.slave_latency     = 0U;
    params.conn_sup_timeout  = MSEC_TO_UNITS(6000, UNIT_10_MS);
    APP_ERROR_CHECK(sd_ble_gap_ppcp_set(&params));
}

static void conn_params_event_handler(ble_conn_params_evt_t * event)
{
    if (event->evt_type == BLE_CONN_PARAMS_EVT_FAILED)
    {
        NRF_LOG_WARNING("Connection params not accepted; keep existing link");
    }
}

static void conn_params_init(void)
{
    ble_conn_params_init_t init = {0};
    /* GAP首选参数只是声明；SDK模块在连接5秒后实际发起协商。 */
    init.first_conn_params_update_delay = APP_TIMER_TICKS(5000);
    init.next_conn_params_update_delay = APP_TIMER_TICKS(30000);
    init.max_conn_params_update_count = 3U;
    init.start_on_notify_cccd_handle = BLE_GATT_HANDLE_INVALID;
    init.disconnect_on_fail = false; /* 手机不同意参数也保留有效连接。 */
    init.evt_handler = conn_params_event_handler;
    init.error_handler = ble_runtime_result;
    APP_ERROR_CHECK(ble_conn_params_init(&init));
}

static void services_init(void)
{
    nrf_ble_qwr_init_t qwr = {0};
    ble_dfu_buttonless_init_t dfu = {0};
    qwr.error_handler = qwr_error_handler;
    APP_ERROR_CHECK(nrf_ble_qwr_init(&m_qwr, &qwr));
    /* 把业务命令函数作为回调交给BLE层，手机写FF02时会调用它。 */
    APP_ERROR_CHECK(ble_uvguard_init(&m_uv_service, app_uvguard_command));
    m_adv_uuid.uuid = UVGUARD_SERVICE_UUID;
    m_adv_uuid.type = m_uv_service.uuid_type;
    dfu.evt_handler = dfu_event;
    APP_ERROR_CHECK(ble_dfu_buttonless_init(&dfu));
}

static void advertising_init(void)
{
    ble_advertising_init_t init;
    memset(&init, 0, sizeof(init));
    init.evt_handler = advertising_event_handler;
    init.error_handler = advertising_error_handler;
    /* 广播包放完整设备名；Flags声明这是仅BLE、可通用发现的设备。 */
    init.advdata.name_type = BLE_ADVDATA_FULL_NAME;
    init.advdata.flags = BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE;
    /* 扫描响应中公布FF00服务，方便调试工具识别设备能力。 */
    init.srdata.uuids_complete.uuid_cnt = 1;
    init.srdata.uuids_complete.p_uuids = &m_adv_uuid;
    init.config.ble_adv_fast_enabled = true;
    init.config.ble_adv_fast_interval = APP_ADV_INTERVAL;
    init.config.ble_adv_fast_timeout = APP_ADV_DURATION;
    init.config.ble_adv_slow_enabled = true;
    init.config.ble_adv_slow_interval = APP_ADV_SLOW_INTERVAL;
    init.config.ble_adv_slow_timeout = 0U; /* 慢速广播不因超时而结束。 */
    APP_ERROR_CHECK(ble_advertising_init(&m_advertising, &init));
    ble_advertising_conn_cfg_tag_set(&m_advertising, APP_BLE_CONN_CFG_TAG);
}

/* 只在主循环调用，不在定时器/蓝牙中断里做I2C或关机。
 * SW1按住时延后关机，避免低电平SENSE造成刚关机就立即唤醒。 */
static void idle_power_process(void)
{
    /* 先取断开时间，再读当前时间；避免中断更新断开时间后出现负差值回绕。 */
    uint32_t disconnected_at = m_disconnected_at;
    uint32_t now = app_timer_cnt_get();
    uint32_t error;
    uint32_t pin;
    if (!idle_power_due(app_timer_cnt_diff_compute(now, disconnected_at),
                        APP_TIMER_TICKS(IDLE_POWER_OFF_MS),
                        m_power_connected, m_entering_dfu,
                        BSP_ButtonPressed(), nrf_fstorage_is_busy(NULL))) return;

    /* 成功停止广播后不再接受新连接；若连接刚建立导致INVALID_STATE，
     * 本轮取消，先让主循环处理连接事件，不能误关正在连接的设备。 */
    error = sd_ble_gap_adv_stop(m_advertising.adv_handle);
    if (error != NRF_SUCCESS)
    {
        if (error != NRF_ERROR_INVALID_STATE) ble_runtime_result(error);
        return;
    }
    if (m_power_connected || m_entering_dfu || BSP_ButtonPressed() ||
        nrf_fstorage_is_busy(NULL) || m_disconnected_at != disconnected_at)
    {
        advertising_error_handler(NRF_ERROR_BUSY);
        return;
    }
    m_adv_retry = false;
    NRF_LOG_INFO("POWER OFF: disconnected >=180s; SW1 wakes device");
    LedRing_Show(0U, 0U, 0U); /* 发送熄灯帧，而不只是停止PWM。 */
    if (!LTR390_Standby()) NRF_LOG_WARNING("Sensor standby not confirmed");
    NRF_TWI0->ENABLE = TWI_ENABLE_ENABLE_Disabled;
    NRF_SAADC->ENABLE = SAADC_ENABLE_ENABLE_Disabled;
    /* 清除旧SENSE配置，只保留SW1为System OFF唤醒源。 */
    for (pin = 0U; pin < 32U; pin++)
        nrf_gpio_cfg_sense_set(pin, NRF_GPIO_PIN_NOSENSE);
    nrf_gpio_cfg_sense_input(UV_PIN_BUTTON, NRF_GPIO_PIN_PULLUP,
                             NRF_GPIO_PIN_SENSE_LOW);
    NRF_LOG_FINAL_FLUSH();
    error = sd_power_system_off(); /* SoftDevice启用时使用SVC，不直接写POWER。 */
    /* 接着J-Link时可能是模拟System OFF，不能据此测真实关机电流。
     * 防止意外返回后继续采样；GPIO唤醒正常情况下会走完整复位启动。 */
    if (error != NRF_SUCCESS) NRF_LOG_ERROR("System OFF returned=%lu", (unsigned long)error);
    for (;;) { __WFE(); }
}

int main(void)
{
    uint32_t reset_reason = NRF_POWER->RESETREAS;
    NRF_POWER->RESETREAS = reset_reason; /* 写1清除已记录的复位来源。 */
    APP_ERROR_CHECK(NRF_LOG_INIT(NULL));
    NRF_LOG_DEFAULT_BACKENDS_INIT();
    /* Must initialize bootloader SVC forwarding before timer/SoftDevice interrupts. */
    APP_ERROR_CHECK(ble_dfu_buttonless_async_svci_init());
    /* 每次重启都有BOOT；SREQ=软件复位，DOG=看门狗，LOCKUP=CPU锁死。 */
    NRF_LOG_INFO("BOOT UVGuard secure build307; RESETREAS=0x%08lx",
                 (unsigned long)reset_reason);
    /* app_timer使用RTC1产生软件定时器，是1秒采样节拍的底座。 */
    APP_ERROR_CHECK(app_timer_init());

    /* 电源管理在空闲时执行WFE，让CPU睡眠等待中断，降低功耗。 */
    APP_ERROR_CHECK(nrf_pwr_mgmt_init());
    ble_stack_init();
    uvguard_security_init();
    gap_init();
    /* 初始化标准GATT管理层；NULL表示不额外处理MTU变化事件。 */
    APP_ERROR_CHECK(nrf_ble_gatt_init(&m_gatt, NULL));
    services_init();
    conn_params_init();
    advertising_init();
    m_disconnected_at = app_timer_cnt_get(); /* 开机未连接也计时。 */
    m_wake_button_released = (reset_reason & POWER_RESETREAS_OFF_Msk) == 0U;
    /* 先开始广播：后续灯环/传感器初始化失败也不阻止手机发现设备。 */
    APP_ERROR_CHECK(ble_advertising_start(&m_advertising, BLE_ADV_MODE_FAST));
    app_uvguard_init(&m_uv_service);

    for (;;)
    {
        /* SDK先自动重播；只有其报告失败/空闲时才延时重试，避免双重启动。 */
        /* 唤醒的第一次按下不计作长按清主人；先松开，下一次再按才计时。 */
        if (!m_wake_button_released && !BSP_ButtonPressed()) m_wake_button_released = true;
        if (m_wake_button_released) uvguard_security_process();
        idle_power_process();
        if (!m_entering_dfu && m_adv_retry &&
            (m_uv_service.connection_handle == BLE_CONN_HANDLE_INVALID) &&
            (app_timer_cnt_diff_compute(app_timer_cnt_get(), m_adv_retry_tick) >=
             APP_TIMER_TICKS(1000)))
        {
            uint32_t error;
            m_adv_retry_tick = app_timer_cnt_get();
            error = ble_advertising_start(&m_advertising, BLE_ADV_MODE_FAST);
            if (error == NRF_SUCCESS) m_adv_retry = false;
            else NRF_LOG_WARNING("ADV retry error=%lu", (unsigned long)error);
        }
        /* 有1秒标志时采样/发BLE；没任务时进入低功耗等待中断。 */
        if (!m_entering_dfu) app_uvguard_process();
        if (!NRF_LOG_PROCESS())
        {
            nrf_pwr_mgmt_run();
        }
    }
}
