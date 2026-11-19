#ifndef BLE_UVGUARD_H
#define BLE_UVGUARD_H

#include <stdint.h>
#include "ble.h"
#include "ble_srv_common.h"

/*
 * 手机 App 与手表约定的 GATT UUID。
 * 完整形式为 0000xxxx-0000-1000-8000-00805F9B34FB。
 * FF00是服务，FF01/FF02/FF03是服务里面的三个特征值。
 */
#define UVGUARD_SERVICE_UUID      0xFF00U
#define UVGUARD_REALTIME_UUID     0xFF01U /* 手表 -> 手机，Notify实时数据。 */
#define UVGUARD_COMMAND_UUID      0xFF02U /* 手机 -> 手表，Write命令。 */
#define UVGUARD_DIAGNOSTIC_UUID   0xFF03U /* 手表 -> 手机，Notify诊断数据。 */

/*
 * 函数指针类型：BLE层收到手机命令后，不直接处理业务，
 * 而是回调 app_uvguard_command(data,length)。这样BLE和业务解耦。
 */
typedef void (*ble_uvguard_command_handler_t)(uint8_t const * data,
                                               uint16_t length);

/* 保存一个UV-Guard GATT服务运行时需要的句柄和回调。 */
typedef struct
{
    uint8_t uuid_type;                   /* SoftDevice中的UUID类型。 */
    uint16_t service_handle;             /* FF00服务句柄。 */
    uint16_t connection_handle;          /* 当前连接；无连接时为INVALID。 */
    ble_gatts_char_handles_t realtime_handles;
    ble_gatts_char_handles_t command_handles;
    ble_gatts_char_handles_t diagnostic_handles;
    ble_uvguard_command_handler_t command_handler;
} ble_uvguard_t;

uint32_t ble_uvguard_init(ble_uvguard_t * service,
                          ble_uvguard_command_handler_t command_handler);
void ble_uvguard_set_connection(ble_uvguard_t * service,
                                uint16_t connection_handle);
uint32_t ble_uvguard_send_realtime(ble_uvguard_t * service,
                                   uint8_t const * data, uint16_t length);
uint32_t ble_uvguard_send_diagnostic(ble_uvguard_t * service,
                                     uint8_t const * data, uint16_t length);

#endif
