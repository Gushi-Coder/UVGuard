#include <stdbool.h>
#include <string.h>
#include "ble_uvguard.h"
#include "nrf_sdh_ble.h"
#include "uvguard_security.h"

/* 全局保存服务地址，供SoftDevice事件回调找到对应实例。 */
static ble_uvguard_t * m_service;

/*
 * 手机向FF02执行Write时，SoftDevice产生 BLE_GATTS_EVT_WRITE 事件。
 * e来自SoftDevice事件队列，c是注册观察者时传入的上下文，本项目未使用。
 */
static void write_handler(ble_evt_t const * event, void * context)
{
    ble_gatts_evt_write_t const * write;
    (void)context;

    if ((m_service == NULL) ||
        (event->header.evt_id != BLE_GATTS_EVT_WRITE))
    {
        return;
    }

    write = &event->evt.gatts_evt.params.write;
    if (!uvguard_security_ready(event->evt.gatts_evt.conn_handle)) return;

    /* value_handle唯一标识FF02的数据值；只处理写入FF02的内容。 */
    if ((write->handle == m_service->command_handles.value_handle) &&
        (m_service->command_handler != NULL))
    {
        m_service->command_handler(write->data, write->len);
    }
}

/* 优先级2注册BLE事件观察者；数字越小，事件处理越早。 */
NRF_SDH_BLE_OBSERVER(m_uv_observer, 2, write_handler, NULL);

/*
 * 给FF00服务添加一个特征值。
 * notify=true：允许手表主动推送，需要CCCD；
 * write=true ：允许手机写入；
 * handles    ：SoftDevice返回生成的value_handle、cccd_handle等。
 */
static uint32_t uvguard_characteristic_add(ble_uvguard_t * service,
                                           uint16_t short_uuid,
                                           bool notify,
                                           bool write,
                                           ble_gatts_char_handles_t * handles)
{
    ble_uuid_t uuid;
    ble_gatts_char_md_t characteristic_metadata;
    ble_gatts_attr_md_t attribute_metadata;
    ble_gatts_attr_md_t cccd_metadata;
    ble_gatts_attr_t attribute;

    memset(&characteristic_metadata, 0, sizeof(characteristic_metadata));
    memset(&attribute_metadata, 0, sizeof(attribute_metadata));
    memset(&cccd_metadata, 0, sizeof(cccd_metadata));
    memset(&attribute, 0, sizeof(attribute));

    characteristic_metadata.char_props.notify = notify ? 1U : 0U;
    characteristic_metadata.char_props.write = write ? 1U : 0U;
    characteristic_metadata.char_props.write_wo_resp = write ? 1U : 0U;

    if (notify)
    {
        /* CCCD subscription requires an encrypted BLE link. */
        BLE_GAP_CONN_SEC_MODE_SET_OPEN(&cccd_metadata.read_perm);
        BLE_GAP_CONN_SEC_MODE_SET_ENC_NO_MITM(&cccd_metadata.write_perm);
        cccd_metadata.vloc = BLE_GATTS_VLOC_STACK;
        characteristic_metadata.p_cccd_md = &cccd_metadata;
    }

    uuid.type = service->uuid_type;
    uuid.uuid = short_uuid;

    /* 数据由SoftDevice保管；vlen=1表示长度可以变化，最大20字节。 */
    BLE_GAP_CONN_SEC_MODE_SET_ENC_NO_MITM(&attribute_metadata.read_perm);
    BLE_GAP_CONN_SEC_MODE_SET_ENC_NO_MITM(&attribute_metadata.write_perm);
    attribute_metadata.vloc = BLE_GATTS_VLOC_STACK;
    attribute_metadata.vlen = 1U;

    attribute.p_uuid = &uuid;
    attribute.p_attr_md = &attribute_metadata;
    attribute.init_len = 0U;
    attribute.max_len = 20U; /* 默认ATT MTU=23，去掉3字节头后最大20字节。 */

    return sd_ble_gatts_characteristic_add(service->service_handle,
                                            &characteristic_metadata,
                                            &attribute,
                                            handles);
}

uint32_t ble_uvguard_init(ble_uvguard_t * service,
                          ble_uvguard_command_handler_t command_handler)
{
    ble_uuid_t service_uuid;
    uint32_t error;

    memset(service, 0, sizeof(*service));
    service->connection_handle = BLE_CONN_HANDLE_INVALID;
    service->command_handler = command_handler;

    /* FF00使用Bluetooth SIG基础UUID，所以使用内置BLE_UUID_TYPE_BLE。 */
    service->uuid_type = BLE_UUID_TYPE_BLE;
    service_uuid.type = service->uuid_type;
    service_uuid.uuid = UVGUARD_SERVICE_UUID;

    error = sd_ble_gatts_service_add(BLE_GATTS_SRVC_TYPE_PRIMARY,
                                     &service_uuid,
                                     &service->service_handle);
    if (error != NRF_SUCCESS) return error;

    error = uvguard_characteristic_add(service, UVGUARD_REALTIME_UUID,
                                       true, false,
                                       &service->realtime_handles);
    if (error != NRF_SUCCESS) return error;

    error = uvguard_characteristic_add(service, UVGUARD_COMMAND_UUID,
                                       false, true,
                                       &service->command_handles);
    if (error != NRF_SUCCESS) return error;

    error = uvguard_characteristic_add(service, UVGUARD_DIAGNOSTIC_UUID,
                                       true, false,
                                       &service->diagnostic_handles);
    if (error == NRF_SUCCESS) m_service = service;
    return error;
}

void ble_uvguard_set_connection(ble_uvguard_t * service,
                                uint16_t connection_handle)
{
    service->connection_handle = connection_handle;
}

/* 两个Notify接口共用的底层发送函数。 */
static uint32_t notify(ble_uvguard_t * service,
                       uint16_t value_handle,
                       uint8_t const * data,
                       uint16_t length)
{
    ble_gatts_hvx_params_t parameters;

    if (service->connection_handle == BLE_CONN_HANDLE_INVALID)
        return NRF_ERROR_INVALID_STATE;
    /* Restored CCCDs alone must never allow plaintext notification after reconnect. */
    if (!uvguard_security_ready(service->connection_handle)) return NRF_ERROR_INVALID_STATE;

    memset(&parameters, 0, sizeof(parameters));
    parameters.handle = value_handle;
    parameters.type = BLE_GATT_HVX_NOTIFICATION;
    parameters.p_data = data;
    parameters.p_len = &length;

    /* HVX=Handle Value Notification/Indication，由SoftDevice排入无线发送队列。 */
    return sd_ble_gatts_hvx(service->connection_handle, &parameters);
}

uint32_t ble_uvguard_send_realtime(ble_uvguard_t * service,
                                   uint8_t const * data, uint16_t length)
{
    return notify(service, service->realtime_handles.value_handle,
                  data, length);
}

uint32_t ble_uvguard_send_diagnostic(ble_uvguard_t * service,
                                     uint8_t const * data, uint16_t length)
{
    return notify(service, service->diagnostic_handles.value_handle,
                  data, length);
}
