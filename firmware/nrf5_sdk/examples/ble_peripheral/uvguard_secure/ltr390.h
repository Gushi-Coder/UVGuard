#ifndef LTR390_H
#define LTR390_H
#include <stdbool.h>
#include <stdint.h>

/* 初始化 nRF52832 TWI0 和 LTR-390，成功返回 true。 */
bool LTR390_Init(void);

/* 读取 UVS_DATA 三个寄存器，并组合成24位原始紫外线数据。 */
bool LTR390_ReadUV(uint32_t * raw);
/* ALS模式应读0x0D~0x0F，不能继续读UV数据寄存器。 */
bool LTR390_ReadALS(uint32_t * raw);

/* 读取关键寄存器，用于电脑/手机诊断传感器是否配置正确。 */
bool LTR390_ReadRegisters(uint8_t *ctrl,uint8_t *rate,uint8_t *gain,uint8_t *id,uint8_t *status);

/* als=true切换可见光模式；false恢复紫外线模式。 */
bool LTR390_SetALS(bool als);
/* 关机前写MAIN_CTRL=0，停止积分，减少传感器静态耗电。 */
bool LTR390_Standby(void);
#endif
