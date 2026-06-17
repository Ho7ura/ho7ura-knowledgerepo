# OV3660 摄像头调试总结

## 问题现象

ESP32-S3 启动后摄像头初始化失败，无限重启：

```
E (2177) camera: Detected camera not supported.
E (2178) camera: Camera probe failed with error 0x106(ESP_ERR_NOT_SUPPORTED)
[CAM] init failed: 0x106
[CAM] init failed, reboot...
```

## 排查过程

### 1. 确认 WiFi 正常
ESP32 成功连接热点 `i_hate_esp`，获取 IP `172.20.10.2`。WiFi 层面无问题。

### 2. 传感器驱动编译状态
- 初始配置 `CAMERA_MODEL_XIAO_ESP32S3` 仅预期 OV2640
- `platformio.ini` 添加了 `build_flags` 启用所有常见传感器驱动：
  ```
  -DCONFIG_OV2640_SUPPORT=1
  -DCONFIG_OV3660_SUPPORT=1
  -DCONFIG_OV5640_SUPPORT=1
  -DCONFIG_OV7670_SUPPORT=1
  -DCONFIG_OV7725_SUPPORT=1
  ```
- **但** PlatformIO 使用的 esp32-camera 库是**预编译的 `.a` 文件**，位于：
  ```
  ~/.platformio/packages/framework-arduinoespressif32-libs/esp32s3/lib/libespressif__esp32-camera.a
  ```
  `lib_deps` 中的源码不会被重新编译。`build_flags` 对预编译库无效。

### 3. 验证预编译库内容
用 `xtensa-esp-elf-nm` 检查 `.a` 文件，确认 **OV3660 已经编译进去**：

| 符号 | 状态 |
|------|------|
| `esp32_camera_ov3660_detect` | T (已定义) |
| `esp32_camera_ov3660_init` | T (已定义) |
| `esp32_camera_ov2640_detect` | T (已定义) |
| `esp32_camera_ov2640_init` | T (已定义) |

**软件层面不需要额外配置**——OV3660 支持已经在库里。

### 4. I2C 扫描（手动调试）
在 `main.cpp` 的 `setup()` 中加入 XCLK + Wire I2C 扫描代码，扫描 GPIO40(SDA)/GPIO39(SCL)：
- 结果：**没有发现任何 I2C 设备**
- 可能原因：手动开启的 XCLK 配置与库不完全一致，或 I2C 端口冲突

### 5. 代码复位
所有调试代码已清除，`main.cpp` 恢复原样。`platformio.ini` 的 `build_flags` 保留（无害）。

## 根本原因分析

`esp_camera_init()` 内部流程：

1. `CAMERA_ENABLE_OUT_CLOCK` — 开启 XCLK（GPIO10, 20MHz）
2. `SCCB_Init(40, 39)` — 初始化摄像头 I2C 总线
3. `SCCB_Probe()` — 扫描已知传感器地址，**找到了设备**（否则报 `ESP_ERR_NOT_FOUND`）
4. 遍历 `g_sensors[]` 调用各 detect 函数 → **全部失败**
5. 报错 `ESP_ERR_NOT_SUPPORTED`

SCCB_Probe 能发现设备（I2C ACK 正常），但 OV3660 detect 读取 PID 寄存器失败。可能原因：

| 原因 | 可能性 |
|------|--------|
| OV3660 模块实际 I2C 地址不是 0x3C（可能是 0x30） | 高 |
| FPC 排线接触不良，I2C 读寄存器时数据错误 | 高 |
| 摄像头模块损坏 | 中 |
| 摄像头型号不是 OV3660（标签错误） | 低 |
| XCLK 频率不对导致寄存器读时序错误 | 低 |

## 硬件排查建议

1. **FPC 排线检查** — 拔下排线，橡皮擦或酒精擦拭金手指，重新插到底，黑色卡扣压紧
2. **排线方向** — OV3660 模块排线金属触点面可能需要反过来插
3. **供电** — 换一个 USB 口或带独立供电的 USB Hub 给 XIAO 供电
4. **交叉测试** — 如果有其他 ESP32 摄像头开发板，交叉验证模块是否完好

## 当前文件状态

| 文件 | 状态 |
|------|------|
| `platformio.ini` | 已添加传感器 build_flags，无需回退 |
| `compile/src/main.cpp` | 已复位，无调试代码残留 |
| `compile/compile.ino` | 未修改（与 main.cpp 内容相同） |
| `compile/.pio/libdeps/.../ov3660.c` | 曾修改 detect 函数，对预编译库无效 |
| 预编译库 `.a` | 已恢复（重新安装了 framework-libs 包） |

## 网络配置

| 配置项 | 值 |
|--------|-----|
| WiFi SSID | `i_hate_esp` |
| WiFi 密码 | `11111111` |
| 服务器地址 | `172.20.10.3` |
| WebSocket CAM | `172.20.10.3:8081/ws/camera` |
| WebSocket AUD | `172.20.10.3:8081/ws_audio` |
| HTTP 音频流 | `172.20.10.3:8081/stream.wav` |
| IMU/UDP | `172.20.10.3:12345` |
