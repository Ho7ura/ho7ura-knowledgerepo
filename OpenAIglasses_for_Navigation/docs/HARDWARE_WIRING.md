# 硬件接线图 — AI 导航眼镜 (ESP32-S3)

**主控**: Seeed XIAO ESP32S3 Sense

---

## 1. OV2640 摄像头 — DVP 8-bit 并行接口

摄像头通过 XIAO ESP32S3 Sense 的 **专用摄像头 FPC 排线座** 连接，无需手动接线。若使用独立 OV2640 模块，引脚对应如下：

| OV2640 信号 | ESP32 GPIO | 说明 |
|---|---|---|
| XCLK | **GPIO 10** | 20MHz 主时钟 |
| PCLK | **GPIO 13** | 像素时钟输入 |
| VSYNC | **GPIO 38** | 帧同步 |
| HREF | **GPIO 47** | 行同步 |
| SIOD (SDA) | **GPIO 40** | SCCB I2C 数据 |
| SIOC (SCL) | **GPIO 39** | SCCB I2C 时钟 |
| Y2 (D0) | **GPIO 15** | 数据位 0 |
| Y3 (D1) | **GPIO 17** | 数据位 1 |
| Y4 (D2) | **GPIO 18** | 数据位 2 |
| Y5 (D3) | **GPIO 16** | 数据位 3 |
| Y6 (D4) | **GPIO 14** | 数据位 4 |
| Y7 (D5) | **GPIO 12** | 数据位 5 |
| Y8 (D6) | **GPIO 11** | 数据位 6 |
| Y9 (D7) | **GPIO 48** | 数据位 7 |
| PWDN | **悬空** | -1 (未用) |
| RESET | **悬空** | -1 (未用) |

---

## 2. MAX98357A 喇叭放大器 — I2S

| MAX98357A | ESP32 GPIO | 说明 |
|---|---|---|
| BCLK | **GPIO 7** | 位时钟 |
| LRCLK (WS) | **GPIO 8** | 左右时钟/帧同步 |
| DIN | **GPIO 9** | I2S 数据输入 |
| GAIN | **悬空或 GND** | 增益选择 (SD 模式) |
| SD | **悬空或 3.3V** | 关断控制 (低有效) |
| VIN | **5V** | 供电 |
| GND | **GND** | 地 |

喇叭接 MAX98357A 输出端 (SPK+ / SPK-)。

---

## 3. ICM42688-P IMU — SPI

| ICM42688-P | ESP32 GPIO | 说明 |
|---|---|---|
| SCK | **GPIO 1** | SPI 时钟 |
| MOSI (SDI) | **GPIO 2** | 主出从入 |
| MISO (SDO) | **GPIO 3** | 主入从出 |
| CS | **GPIO 4** | 片选 |
| INT | **悬空** | 中断 (未用) |
| VDD | **3.3V** | 供电 |
| GND | **GND** | 地 |

---

## 4. PDM MEMS 麦克风 — 板载

**无需接线**。XIAO ESP32S3 Sense 板载 PDM 麦克风，内部已连接到 GPIO 42 (CLK) 和 GPIO 41 (DATA)。

---

## 总览图

```
                    XIAO ESP32S3 Sense
   ┌──────────────────────────────────────────────────────┐
   │                                                      │
   │  [摄像头 FPC 座]  ←── OV2640 摄像头 (排线直插)        │
   │                                                      │
   │  [板载 PDM 麦克风] ←── GPIO 41(DATA), GPIO 42(CLK)    │
   │                                                      │
   │  GPIO 1  ─── SCK  ───┐                              │
   │  GPIO 2  ─── MOSI ───┤                              │
   │  GPIO 3  ─── MISO ───┼── ICM42688-P IMU 模块         │
   │  GPIO 4  ─── CS   ───┘                              │
   │                                                      │
   │  GPIO 7  ─── BCLK  ───┐                              │
   │  GPIO 8  ─── LRCLK ───┼── MAX98357A 放大器 ── 喇叭    │
   │  GPIO 9  ─── DIN   ───┘                              │
   │                                                      │
   │  5V    ──── MAX98357A VIN                            │
   │  GND   ──── MAX98357A GND + IMU GND                  │
   │  3.3V  ──── IMU VDD                                  │
   │                                                      │
   └──────────────────────────────────────────────────────┘
```

**电源**: 整个系统通过 XIAO ESP32S3 Sense 的 USB-C 口 5V 供电，MAX98357A 由 5V 引脚供电，IMU 由 3.3V 引脚供电。

---

## GPIO 占用总表

| GPIO | 外设 | 信号 |
|---|---|---|
| 1 | ICM42688-P (SPI) | SCK |
| 2 | ICM42688-P (SPI) | MOSI |
| 3 | ICM42688-P (SPI) | MISO |
| 4 | ICM42688-P (SPI) | CS |
| 7 | MAX98357A (I2S) | BCLK |
| 8 | MAX98357A (I2S) | LRCLK |
| 9 | MAX98357A (I2S) | DIN |
| 10 | OV2640 (DVP) | XCLK |
| 11 | OV2640 (DVP) | Y8 (D6) |
| 12 | OV2640 (DVP) | Y7 (D5) |
| 13 | OV2640 (DVP) | PCLK |
| 14 | OV2640 (DVP) | Y6 (D4) |
| 15 | OV2640 (DVP) | Y2 (D0) |
| 16 | OV2640 (DVP) | Y5 (D3) |
| 17 | OV2640 (DVP) | Y3 (D1) |
| 18 | OV2640 (DVP) | Y4 (D2) |
| 38 | OV2640 (DVP) | VSYNC |
| 39 | OV2640 (SCCB) | SIOC |
| 40 | OV2640 (SCCB) | SIOD |
| 41 | PDM 麦克风 (I2S) | DATA |
| 42 | PDM 麦克风 (I2S) | CLK |
| 47 | OV2640 (DVP) | HREF |
| 48 | OV2640 (DVP) | Y9 (D7) |

---

## 注意事项

- **摄像头优先用 FPC 排线直插**，不要手动飞线 — 8-bit DVP 并口 20MHz 对信号完整性要求高
- MAX98357A 的 **5V 供电** 与喇叭共地，电源纹波大会影响音频质量
- IMU 的 SPI 线尽量短 (GPIO 1-4 都在同一排)
- GPIO 41/42 已被板载麦克风占用，不要再复用
