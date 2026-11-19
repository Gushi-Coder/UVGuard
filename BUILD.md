# 编译与部署

## 固件
1. 准备 Nordic nRF5 SDK **17.1.0**，把 SDK 内容放在 `firmware/nrf5_sdk` 下，使 `components`、`modules`、`external` 与本仓库 `examples` 同级。不要覆盖本仓库的 UVGuard 源码。
2. 使用 Keil MDK 5 / ARM Compiler 5，打开根目录 `UVGuard_All.uvmpw`。工程中的 SDK 路径已改为相对路径，Android 文件在独立工程阅读。
3. 分别编译 Application 与 Bootloader。此前本机 Bootloader 构建成功；应用代码 65,628 字节，超出 MDK Lite 32 KB 限制，需合法可用的完整工具链。本次未声称重新编译通过。
4. 本仓库未打包官方 SDK 与工具链。CMSIS 头文件引用 SDK 的 `components/toolchain/cmsis/include`，启动文件引用 `modules/nrfx/mdk`。

## Flash 布局与签名
| 区域 | 起点 |
|---|---|
| Application | 0x26000 |
| FDS 绑定数据 | 0x73000 |
| Bootloader | 0x76000 |
| MBR 参数页 | 0x7E000 |
| Bootloader settings | 0x7F000 |

SoftDevice 使用 S132 7.2.0。烧录前备份设备，核对实际链接图、分区和版本；需按新应用生成匹配的 Bootloader settings，不能把任意应用与旧 settings 混合。307 的唤醒策略需要对应 Bootloader 配置，不能只烧录其中一个模块就宣称升级完成。

原设备的 OTA 包必须由与设备验签公钥匹配的私钥签名；私钥不上传。新设备使用自己的密钥对并替换公钥。双区 DFU 不等于已实现应用运行失败的自动回滚，仓库不作此承诺。

## Android
- JDK 17、Gradle 8.7、Android SDK Platform 34；Android Gradle Plugin 8.5.2。
- 用 Android Studio 打开 `android`，或在目录中使用本机 Gradle：`gradle assembleDebug`。
- 通过 `ANDROID_HOME` 或本机 `local.properties` 指定 SDK。依赖 `no.nordicsemi.android:dfu:2.5.0`。
- 上传副本已移除原本的 development.keystore 签名设置；Debug 使用工具生成的本机调试签名，正式发布需自行配置安全保管的签名。
- 原安装包与新调试包签名可能不同，不保证直接覆盖安装。不要为安装而直接删除用户数据，应先确认备份。

## 仓库校验
已检查源码文件范围、工程 XML 及常见敏感信息模式；未包含固件产物、私钥、恢复镜像、构建缓存。上传整理不等于新增真机验证记录。
