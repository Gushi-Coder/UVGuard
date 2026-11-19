# UVGuard | 无线紫外线监测胸针 / 手环

基于 nRF52832 的胸针 / 手环二合一设备，配套 Android App。设备业务固件提供紫外线监测、离线灯环预警、电量采集、BLE 数据交互及安全 DFU 升级接入。硬件采用四层 PCB；本仓库暂不包含 PCB 工程。

## 功能与架构
- **1 Hz 采样闭环**：LTR390 紫外线采集、SAADC 电池测量、分级灯光提示。
- **PWM + EasyDMA**：驱动 12 颗 RGB 灯珠，支持调光、三色自检。
- **GATT 通信**：实时 / 控制 / 诊断三路特征，14 B 与 20 B 数据帧，CRC-8 校验。
- **绑定与安全**：LESC + Peer Manager，物理配对窗口、单用户绑定、长按解除绑定；业务收发检查安全状态。Just Works 首次配对不提供主动中间人认证，应在可信环境配对。
- **Secure DFU**：集成 Nordic 官方 Bootloader / DFU，实现 buttonless 升级入口、双区配置、签名验证和版本检查。官方 SDK 内核不作为独立自研实现。
- **低功耗策略**：源码包含断连 180 s 后 System OFF 与按键唤醒逻辑；整机电流尚无已核实实测值。
- **Android**：BLE 采集显示、DFU 客户端、每日统计和 AI 提醒界面；API 密钥由使用者在本机配置，不随仓库提供。

## 目录
```
UVGuard_All.uvmpw          Keil 双工程工作区
firmware/nrf5_sdk/examples/
  ble_peripheral/uvguard_secure/  应用固件、驱动、安全和配置
  dfu/uvguard_bootloader/         Bootloader 入口、公钥和配置
android/                         Android Java / 资源 / Gradle 工程
BUILD.md                         环境、编译、分区与版本状态
```

## 代码阅读顺序
1. 应用 `main.c`：初始化、BLE 事件、主循环、升级交接和休眠。
2. `app_uvguard.c`：采样、预警、命令解析和帧封装。
3. `ltr390.c`、`battery.c`、`led_ring.c`、`bsp_uvguard.c`：外设驱动。
4. `ble_uvguard.c`、`uvguard_security.c`：GATT 服务及绑定权限。
5. Bootloader `main.c`、`sdk_config.h`、`dfu_public_key.c`：启动与 DFU 集成。
6. Android `BleService.java`、`Protocol.java`、`UvDfuService.java`：手机通信与升级。

## 当前状态
应用源码为 build307；最近已记录的板端版本为306。历史统一工程中 Bootloader 构建通过，但应用在 MDK Lite 的 32 KB 链接限制处失败。本仓库是源码整理版本，移植后的相对路径工程尚未完成全量重编译和真机验收，不包含可直接烧录的307整机包。

## 依赖与权利说明
依赖 Nordic nRF5 SDK 17.1.0、S132 7.2.0 及 Nordic Android DFU 库。完整 Nordic SDK、SoftDevice 二进制、工具链均需自行安装，保留各自许可。原文件版权声明保留；本仓库未额外授予开源许可。

没有收录签名私钥、Android keystore、Flash/UICR 恢复备份、绑定数据、账号凭据或本机路径配置。现有 `dfu_public_key.c` 是可分发的验签公钥，不是私钥。
