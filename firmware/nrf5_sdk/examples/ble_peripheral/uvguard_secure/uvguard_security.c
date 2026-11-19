/* UVGuard security: SDK Peer Manager + LE Secure Connections, no RTOS.
 * No display/keyboard => Just Works. This does NOT authenticate the first
 * pairing against an active MITM. Pair near the device in a trusted place.
 * Only one owner. Hold SW1 >=8 seconds to erase ownership locally.
 */
#include "uvguard_security.h"
#include "app_error.h"
#include "app_timer.h"
#include "nrf_gpio.h"
#include "nrf_log.h"
#include "nrf_ble_lesc.h"
#include "peer_manager.h"
#include "peer_manager_handler.h"
#include "nrf_sdh_ble.h"

static ble_gap_sec_params_t m_params;
static uint16_t m_conn = BLE_CONN_HANDLE_INVALID;
static bool m_secure, m_window, m_deleting, m_pressed, m_hold_done;
static uint32_t m_window_start, m_press_start, m_connected_at;

static bool no_owner(void) { return pm_next_peer_id_get(PM_PEER_ID_INVALID) == PM_PEER_ID_INVALID; }
static void pairing_window_open(void)
{
    m_window_start = app_timer_cnt_get();
    m_window = true;
    NRF_LOG_INFO("PAIR window open 120 seconds");
}
static void disconnect_untrusted(void)
{
    m_secure = false;
    if (m_conn != BLE_CONN_HANDLE_INVALID)
        (void)sd_ble_gap_disconnect(m_conn, BLE_HCI_REMOTE_USER_TERMINATED_CONNECTION);
}
static void pm_event(pm_evt_t const * e)
{
    pm_handler_on_pm_evt(e);
    pm_handler_disconnect_on_sec_failure(e);
    pm_handler_flash_clean(e);
    switch (e->evt_id)
    {
        case PM_EVT_CONN_SEC_PARAMS_REQ:
        {
            /* Reject legacy pairing and any new owner outside the physical window. */
            bool allow = m_window && no_owner() && !m_deleting &&
                         e->params.conn_sec_params_req.p_peer_params->lesc &&
                         e->params.conn_sec_params_req.p_peer_params->bond;
            APP_ERROR_CHECK(pm_conn_sec_params_reply(e->conn_handle,
                allow ? &m_params : NULL, e->params.conn_sec_params_req.p_context));
            break;
        }
        case PM_EVT_CONN_SEC_CONFIG_REQ:
        {
            /* A phone that lost its keys must use physical owner reset, not silently replace them. */
            pm_conn_sec_config_t cfg = { .allow_repairing = false };
            pm_conn_sec_config_reply(e->conn_handle, &cfg);
            break;
        }
        case PM_EVT_CONN_SEC_SUCCEEDED:
        {
            pm_conn_sec_status_t status;
            APP_ERROR_CHECK(pm_conn_sec_status_get(e->conn_handle, &status));
            m_secure = status.encrypted && status.bonded && status.lesc && !m_deleting;
            NRF_LOG_INFO("SEC encrypted=%u bonded=%u lesc=%u", status.encrypted, status.bonded, status.lesc);
            if (!m_secure) disconnect_untrusted();
            else m_window = false;
            break;
        }
        case PM_EVT_PEERS_DELETE_SUCCEEDED:
            m_deleting = false;
            pairing_window_open();
            break;
        case PM_EVT_PEERS_DELETE_FAILED:
            m_deleting = false;
            m_window = false;
            NRF_LOG_ERROR("Owner erase failed; hold SW1 again");
            break;
        default: break;
    }
}
static void ble_event(ble_evt_t const * e, void * context)
{
    (void)context;
    if (e->header.evt_id == BLE_GAP_EVT_CONNECTED)
    {
        m_conn = e->evt.gap_evt.conn_handle;
        m_secure = false;
        m_connected_at = app_timer_cnt_get();
        /* Central initiates pairing/encryption. Disconnect idle/untrusted links after 30 s. */
    }
    else if (e->header.evt_id == BLE_GAP_EVT_DISCONNECTED)
    {
        m_conn = BLE_CONN_HANDLE_INVALID;
        m_secure = false;
    }
}
NRF_SDH_BLE_OBSERVER(m_security_observer, 3, ble_event, NULL);

void uvguard_security_init(void)
{
    APP_ERROR_CHECK(pm_init());
    m_params.bond = 1;
    m_params.lesc = 1; /* P-256 ECDH session key agreement; AES-CCM handled by S132. */
    m_params.mitm = 0;
    m_params.io_caps = BLE_GAP_IO_CAPS_NONE;
    m_params.min_key_size = 16;
    m_params.max_key_size = 16;
    m_params.kdist_own.enc = m_params.kdist_own.id = 1;
    m_params.kdist_peer.enc = m_params.kdist_peer.id = 1;
    APP_ERROR_CHECK(pm_sec_params_set(&m_params));
    APP_ERROR_CHECK(pm_register(pm_event));
    /* PM_LESC_ENABLED makes pm_init initialize the SDK LESC module. */
    nrf_gpio_cfg_input(11, NRF_GPIO_PIN_PULLUP);
    if (no_owner()) pairing_window_open();
}
bool uvguard_security_ready(uint16_t connection)
{
    return m_secure && !m_deleting && connection == m_conn && m_conn != BLE_CONN_HANDLE_INVALID;
}
void uvguard_security_process(void)
{
    uint32_t now = app_timer_cnt_get();
    APP_ERROR_CHECK(nrf_ble_lesc_request_handler());
    if (m_window && app_timer_cnt_diff_compute(now, m_window_start) >= APP_TIMER_TICKS(120000))
    {
        m_window = false;
        NRF_LOG_INFO("PAIR window closed; hold SW1 3 seconds to reopen if unowned");
    }
    if (m_conn != BLE_CONN_HANDLE_INVALID && !m_secure &&
        app_timer_cnt_diff_compute(now, m_connected_at) >= APP_TIMER_TICKS(30000))
        disconnect_untrusted();
    if (nrf_gpio_pin_read(11) == 0)
    {
        if (!m_pressed) { m_pressed = true; m_press_start = now; m_hold_done = false; }
        if (!m_hold_done && app_timer_cnt_diff_compute(now, m_press_start) >= APP_TIMER_TICKS(8000))
        {
            m_hold_done = true;
            m_deleting = true;
            m_window = false;
            disconnect_untrusted();
            APP_ERROR_CHECK(pm_peers_delete());
        }
    }
    else if (m_pressed)
    {
        if (!m_hold_done && no_owner() &&
            app_timer_cnt_diff_compute(now, m_press_start) >= APP_TIMER_TICKS(3000)) pairing_window_open();
        m_pressed = false;
    }
}
