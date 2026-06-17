# 接线对照表

## XIAO ESP32S3 Sense ←→ 各外设

### 摄像头 OV2640（FPC 排线，插入板子摄像头座）

| 摄像头 FPC 座 | 说明 |
|---|---|
| 直插 | 无需飞线，排线扣上即可 |

---

### IMU ICM42688-P（4 根杜邦线）

| ICM42688-P | XIAO ESP32S3 Sense |
|---|---|
| SCK | **D0 (GPIO 1)** |
| MOSI / SDI | **D1 (GPIO 2)** |
| MISO / SDO | **D2 (GPIO 3)** |
| CS | **D3 (GPIO 4)** |
| VCC | **3.3V** |
| GND | **GND** |

---

### 喇叭 MAX98357A（3 根信号线 + 电源）

| MAX98357A | XIAO ESP32S3 Sense |
|---|---|
| BCLK | **D7 (GPIO 7)** |
| LRCLK / WS | **D8 (GPIO 8)** |
| DIN | **D9 (GPIO 9)** |
| VIN | **5V** |
| GND | **GND** |
| GAIN | GND（默认增益） |
| SD | 悬空或 3.3V |

喇叭焊到 MAX98357A 的 SPK+ / SPK- 输出端。

---

## 引脚总览（查重用）

```
XIAO ESP32S3 Sense 引脚占用：

 VIN (5V) ─── MAX98357A VIN
 3.3V      ─── ICM42688-P VCC
 GND       ─── ICM42688-P GND + MAX98357A GND

 D0  (GPIO 1)  ─── ICM42688-P SCK
 D1  (GPIO 2)  ─── ICM42688-P MOSI
 D2  (GPIO 3)  ─── ICM42688-P MISO
 D3  (GPIO 4)  ─── ICM42688-P CS
 D7  (GPIO 7)  ─── MAX98357A BCLK
 D8  (GPIO 8)  ─── MAX98357A LRCLK
 D9  (GPIO 9)  ─── MAX98357A DIN

 GPIO 41/42    ─── 板载 PDM 麦克风（内部占用，勿复用）
 摄像头 FPC 座 ─── GPIO 10~18, 38~40, 47, 48（内部占用，勿复用）
```
