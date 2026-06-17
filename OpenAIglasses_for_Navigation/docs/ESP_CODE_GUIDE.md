# ESP32 固件代码速读指南

## 一句话概括

ESP32 同时采集**视频/音频/IMU**三种数据，通过 WiFi 实时发到电脑服务器；同时从服务器拉 TTS 语音播出来。

---

## 数据流向（3 条上行 + 1 条下行）

```
[摄像头] ──JPEG──▶ ESP32 ──WebSocket──▶ 服务器 /ws/camera    (视频上行)
[麦克风] ──PCM───▶ ESP32 ──WebSocket──▶ 服务器 /ws_audio     (音频上行)
[IMU]    ──SPI───▶ ESP32 ──UDP───────▶ 服务器 :12345        (姿态上行)
[服务器] ──HTTP───▶ ESP32 ──I2S───────▶ 喇叭                 (TTS播报下行)
```

---

## 7 个 FreeRTOS 任务 = 7 条流水线

```
Core 0 (协议核)                  Core 1 (应用核)
├─ mic_cap   prio2  麦克风采集     ├─ cam_cap   prio4  摄像头采集  ← 最高优先级
├─ imu_loop  prio2  IMU读取发送    ├─ cam_snd   prio3  摄像头发送
├─ tts_play  prio2  TTS播放(预留)  ├─ mic_upl   prio2  音频上传
└─ http_wav  prio2  HTTP音频播放   └─ loop()    prio1  WiFi重连+WS消息
```

**为什么这样分**：视频最吃资源，独占 Core 1；音频和传感器放 Core 0，互不干扰。

---

## 逐模块代码对应

### ① 摄像头（最复杂）

| 步骤 | 代码位置 | 做什么 |
|---|---|---|
| 引脚定义 | `camera_pins.h` | DVP 8-bit 并口的 16 个 GPIO |
| 初始化 | `init_camera()` L104-145 | 配 OV2640：VGA/JPEG/手动曝光/水平镜像 |
| 采集任务 | `taskCamCapture()` L162-198 | 死循环调 `esp_camera_fb_get()` 抓帧 → 扔进队列 |
| 发送任务 | `taskCamSend()` L200-263 | 从队列取帧 → `wsCam.sendBinary()` 发出 |
| 队列 | `qFrames` (深度3) | 采集→发送之间的缓冲 |
| 限帧 | L211-217 | 如果服务器要求固定 FPS，自动丢帧节流 |

**关键点**：
- `CAMERA_GRAB_LATEST` 模式：只取最新帧，旧帧丢弃
- JPEG 质量 17（偏低，保流畅）
- `snapshot_in_progress` 标志控制抓拍时暂停采集

### ② 麦克风（PDM → I2S → WebSocket）

| 步骤 | 代码位置 | 做什么 |
|---|---|---|
| 初始化 | `init_i2s_in()` L267-274 | GPIO 42/41，PDM RX，16kHz 16bit 单声道 |
| 采集任务 | `taskMicCapture()` L276-297 | 每 20ms 攒 640 字节 → 扔进队列 |
| 上传任务 | `taskMicUpload()` L299-310 | 从队列取数据 → `wsAud.sendBinary()` |

**启动/停止**：`run_audio_stream` 标志控制，服务器发 `RESTART` 命令来重启音频流。

### ③ IMU（SPI → UDP）

| 步骤 | 代码位置 | 做什么 |
|---|---|---|
| 引脚 | L59-62 | GPIO 1-4，硬件 SPI |
| 初始化 | `imu_init_spi()` L777-790 | 读 WHO_AM_I 验证 → 写 PWR_MGMT0 进低噪声模式 |
| 读取 | `imu_read_once()` L792-817 | Burst 读 14 字节（温度+加速度+陀螺仪） |
| 滤波 | L820-821 | EMA 低通（α=0.20），平滑加速度 |
| 发送 | `taskImuLoop()` L824-860 | 50Hz 循环，JSON 格式 UDP 发到 :12345 |
| 量程 | L748-752 | ±16g 加速度，±2000dps 陀螺仪 |

### ④ 喇叭（HTTP 拉流 → I2S 播放）

| 步骤 | 代码位置 | 做什么 |
|---|---|---|
| 初始化 | `init_i2s_out()` L315-322 | GPIO 7/8/9，STD TX，16kHz 32bit 立体声 |
| 任务创建 | `startStreamWav()` L679-683 | 动态创建 `taskHttpPlay` |
| 播放循环 | `taskHttpPlay()` L467-677 | HTTP GET `/stream.wav` → 解析 WAV 头 → 循环读 PCM 写 I2S |
| 音频转换 | `mono16_to_stereo32_msb()` L333-340 | 服务器下发的 16bit mono 转成 MAX98357A 要的 32bit stereo |

**关键点**：
- 支持 chunked transfer 和 content-length 两种 HTTP 模式
- 采样率自适应（8k/12k/16k）
- `stopStreamWav()` 控制停止

### ⑤ WiFi 和服务器连接（loop）

| 步骤 | 代码位置 | 做什么 |
|---|---|---|
| 连接 | `setup()` L869-878 | STA 模式，关省电，19.5dBm 发射功率 |
| 重连 | `loop()` L994-1013 | 每轮检查 WS 是否断开，断了就重连 |
| 摄像头 WS | L996-998 | 连 `/ws/camera`，连上后采集才开始 |
| 音频 WS | L1001-1008 | 连 `/ws_audio`，连上后发 `START` 启动音频流 |
| 消息处理 | `wsCam.onMessage` L915-972 | 处理服务器指令 |

---

## 服务器能发给 ESP32 的控制指令（WebSocket 文本消息）

| 指令 | 代码位置 | 作用 |
|---|---|---|
| `SET:FRAMESIZE=VGA/SVGA/XGA` | L918-927 | 改摄像头分辨率 |
| `SET:QUALITY=5~40` | L928-933 | 改 JPEG 画质 |
| `SET:FPS=5~60` 或 `0` | L934-938 | 限制发送帧率（0=不限） |
| `SNAP:HQ` | L940-970 | 高分辨率抓拍一张 |
| `RESTART` (音频 WS) | L986-988 | 重启音频流 |

---

## 关键全局变量速查

| 变量 | 作用 |
|---|---|
| `cam_ws_ready` | 摄像头 WS 已连接 → 才采集 |
| `aud_ws_ready` | 音频 WS 已连接 → 才采集 |
| `run_audio_stream` | 控制音频流启停 |
| `snapshot_in_progress` | 抓拍中 → 暂停实时采集 |
| `g_target_fps` | 发送帧率上限 |
| `http_play_running` | 控制 HTTP 音频播放 |
| `tts_playing` | 控制 TTS 播放（预留） |

---

## 快速定位表

| 要改什么 | 去哪里 |
|---|---|
| WiFi 账号密码 | L19-20 |
| 服务器 IP/端口 | L21-22 |
| 摄像头分辨率/画质 | L31-32, L136-142 |
| 麦克风采样率 | L46 |
| 喇叭音量 | L334（gain 参数，默认 0.7）或 L659（0.8） |
| IMU 发送频率 | L858（改 delay） |
| 添加新外设 | `setup()` L865 附近加初始化 |
