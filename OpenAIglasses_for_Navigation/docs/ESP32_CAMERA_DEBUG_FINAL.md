# ESP32 摄像头调试最终总结

> 日期：2026-05-25

## 当前状态

| 项目 | 状态 |
|------|------|
| WiFi 连接 | 正常 (172.20.10.2) |
| 服务器通信 | 正常 (172.20.10.3:8081) |
| 传感器驱动 | OV3660 已编译在预编译库中 |
| build_flags | 已配置，无影响 |
| 摄像头初始化 | **失败 (0x106)** |

## 问题

```
E (2177) camera: Detected camera not supported.
E (2178) camera: Camera probe failed with error 0x106(ESP_ERR_NOT_SUPPORTED)
[CAM] init failed, reboot...
```

- `SCCB_Probe()` 能在 I2C 上找到设备（否则报 `ESP_ERR_NOT_FOUND`）
- 但 OV3660 detect 函数读取 PID 寄存器失败，数据与 0x3660 不匹配

## 排查结论

1. **预编译库已包含 OV3660** — `xtensa-esp-elf-nm` 验证符号 `esp32_camera_ov3660_detect` (T) 在 `libespressif__esp32-camera.a` 中
2. **build_flags 对预编译库无效** — PlatformIO 使用 `framework-arduinoespressif32-libs/esp32s3/lib/` 下的 `.a` 文件，不重新编译 `lib_deps` 中的源码
3. **main.cpp 已复位** — 无调试代码残留

## 可能原因

| 原因 | 可能性 |
|------|--------|
| OV3660 I2C 地址不是 0x3C（可能是 0x30） | 较高 |
| FPC 排线金手指氧化或接触不良 | 较高 |
| OV3660 PWDN/RESET 处理与 OV2640 不同 | 中等 |
| 模块个体损坏 | 中低 |
| 模块非 OV3660（标签错误） | 低 |

## 已验证过的尝试

- 添加所有常见传感器 CONFIG flags — 无效（预编译库已有）
- 手动 XCLK + Wire I2C 扫描 — Wire 与库 SCCB 端口冲突
- 修改 ov3660_detect 接受地址 0x30 — 库源码未参与编译
- 删除预编译 .a 强制源码编译 — 链接失败
- 复制源码到 lib/ — 需配合平台重装

## 网络配置

| 配置项 | 值 |
|--------|-----|
| WiFi SSID | `i_hate_esp` |
| WiFi 密码 | `11111111` |
| ESP IP | 172.20.10.2 |
| 服务器地址 | 172.20.10.3 |
| WebSocket CAM | `:8081/ws/camera` |
| WebSocket AUD | `:8081/ws_audio` |
| HTTP 音频流 | `:8081/stream.wav` |
| IMU UDP | `:12345` |

## 下一步

1. 重新拔插 FPC 排线，擦拭金手指
2. 用 OV2640 模块交叉验证
3. 确认此 OV3660 模块是否专为 XIAO 设计
