# ESP 固件问题修复方案

---

## 问题 1：摄像头 SCCB 同核抢占（严重）

**根因**：`loop()` (Core 1, prio 1) 写 SCCB 寄存器时，被 `cam_cap` (Core 1, prio 4) 抢占，`cam_cap` 调用 `esp_camera_fb_get()` 发起新的 SCCB 事务，总线进入半截状态。

**影响位置**：
- [main.cpp:931-932](compile/src/main.cpp#L931-L932) — `SET:QUALITY` 动态改画质
- [main.cpp:950-951](compile/src/main.cpp#L950-L951) — `SNAP:HQ` 改分辨率+画质
- [main.cpp:167-170](compile/src/main.cpp#L167-L170) — `cam_cap` 调用 `esp_camera_fb_get()`

**修复**：用 `vTaskSuspendAll()/xTaskResumeAll()` 包裹 loop 中的 SCCB 操作，阻止调度器在 SCCB 写事务期间切到 `cam_cap`。

```cpp
// 1) SET:QUALITY 路径 (line 928-933) 改为：
else if (cmd.startsWith("SET:QUALITY=")) {
    int q = cmd.substring(strlen("SET:QUALITY=")).toInt();
    q = constrain(q, 5, 40);
    sensor_t* s = esp_camera_sensor_get();
    if (s) {
        vTaskSuspendAll();
        s->set_quality(s, q);
        xTaskResumeAll();
        Serial.printf("[CAM] quality=%d\n", q);
    }
}

// 2) SNAP:HQ 路径中 SCCB 写保护 (line 950-951) 包裹：
if (s) {
    vTaskSuspendAll();
    s->set_framesize(s, target_fs);
    s->set_quality(s, 18);
    xTaskResumeAll();
}
// …抓拍完成后恢复 (line 966-967) 同样包裹：
if (s) {
    vTaskSuspendAll();
    s->set_framesize(s, old_fs);
    s->set_quality(s, old_q);
    xTaskResumeAll();
}
```

`vTaskSuspendAll()` 不关中断，I2C 时序不受影响，只是推迟调度。

---

## 问题 3：跨核 cache 不可见（严重，已影响运行）

**根因**：`volatile bool` 不刷硬件 L1 cache，Core 1 写入的值 Core 0 可能看不到。

**修复**：将 4 个跨核变量从 `volatile bool` 改为 `std::atomic<bool>`。ESP32 Xtensa 的 `std::atomic` 走 `S32C1I` 指令，硬件保证跨核可见。

```cpp
// 文件头部新增：
#include <atomic>

// === 替换以下声明 ===

// line 71:  volatile bool cam_ws_ready = false;        // ← 同核，可保留 volatile
// line 72:  volatile bool aud_ws_ready = false;        // ← 跨核
// line 73:  volatile bool snapshot_in_progress = false; // ← 同核，可保留 volatile
// line 87:  volatile bool tts_playing = false;          // ← 跨核
// line 91:  volatile bool run_audio_stream = false;     // ← 跨核
// line 465: static volatile bool http_play_running = false; // ← 跨核

// 改为：

// cam_ws_ready → 同核 (Core 1 ↔ Core 1)，保留 volatile（或也改为 atomic 统一管理）
volatile bool cam_ws_ready       = false;
volatile bool snapshot_in_progress = false;

// 以下 4 个跨核变量改用 atomic
std::atomic<bool> aud_ws_ready{false};
std::atomic<bool> run_audio_stream{false};
std::atomic<bool> tts_playing{false};
static std::atomic<bool> http_play_running{false};
```

`std::atomic<bool>` 支持 `=` 赋值和直接 `if()` 判断，调用代码无需修改：

```cpp
// 写操作（无需改动语法）：
aud_ws_ready   = true;      // ← 等价于 .store(true, seq_cst)
run_audio_stream = false;   // ← 等价于 .store(false, seq_cst)
http_play_running = false;

// 读操作（无需改动语法）：
if (aud_ws_ready) { ... }   // ← 等价于 .load(seq_cst)
if (run_audio_stream && aud_ws_ready) { ... }
if (!tts_playing) { ... }
while (http_play_running) { ... }
```

**涉及修改的行**：
- `aud_ws_ready`: 声明 (line 72)、WS 回调写入 (line 975/977)、mic_cap 读取 (line 279)、mic_upl 读取 (line 301)
- `run_audio_stream`: 声明 (line 91)、loop 写入 (line 987/1005)、mic_cap 读取 (line 279)、mic_upl 读取 (line 301)
- `http_play_running`: 声明 (line 465)、taskHttpPlay 写入 (line 468)、stopStreamWav 写入 (line 686)、taskHttpPlay 读取 (line 551/663)
- `tts_playing`: 声明 (line 87)、taskTTSPlay 读取 (line 698)

---

## 问题 2：喇叭 I2S 同核软互斥（当前未触发）

**根因**：`taskHttpPlay` 和 `taskTTSPlay` 都用 `i2sOut.write()`，仅靠两个独立 flag 约定互斥，无强制力。

**修复**：加 FreeRTOS 互斥锁。同核同优先级用 mutex 保护 I2S 写入即可。

```cpp
// 全局新增（near line 86）：
static SemaphoreHandle_t i2s_out_mutex = nullptr;

// setup() 中初始化（near line 887）：
i2s_out_mutex = xSemaphoreCreateMutex();

// taskHttpPlay 中 i2sOut.write() 包裹 (line 664)：
if (xSemaphoreTake(i2s_out_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
    size_t wrote = i2sOut.write((uint8_t*)outLR + off, bytes - off);
    xSemaphoreGive(i2s_out_mutex);
    if (wrote == 0) vTaskDelay(pdMS_TO_TICKS(1));
    else off += wrote;
}

// taskTTSPlay 中 i2sOut.write() 包裹 (line 715/725)：
if (xSemaphoreTake(i2s_out_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
    size_t wrote = i2sOut.write((uint8_t*)stereo32Buf + off, bytes - off);
    xSemaphoreGive(i2s_out_mutex);
    if (wrote == 0) vTaskDelay(pdMS_TO_TICKS(1));
    else off += wrote;
}
```

---

## 问题 4：stopStreamWav 悬空指针

**根因**：`taskHttpPlayHandle = nullptr` 时任务可能尚未退出，再次 `startStreamWav` 会创建重复任务。

**修复**：设 flag 后等待任务实际退出，带超时。

```cpp
void stopStreamWav(){
  if (!taskHttpPlayHandle) return;
  http_play_running.store(false);   // atomic，跨核可见
  // 等待任务自行 vTaskDelete，最长等 300ms
  TaskHandle_t h = taskHttpPlayHandle;
  for (int i = 0; i < 30; i++) {
    if (eTaskGetState(h) == eDeleted) break;
    vTaskDelay(pdMS_TO_TICKS(10));
  }
  taskHttpPlayHandle = nullptr;
  Serial.println("[AUDIO] http_wav task stopped");
}
```

---

## 问题 5：SNAP:HQ 阻塞 loop（中等）

**根因**：`wsCam.onMessage` 回调中的 SNAP:HQ 处理包含 `vTaskDelay(500)` 和 `esp_camera_fb_get()`，阻塞整个 `loop()`，期间音频 WS 心跳和重连全停。

**修复**：将 SNAP:HQ 的抓拍逻辑移到独立的一次性任务中，回调只设标志+取参。

```cpp
// 新增标志：
volatile bool snap_requested = false;

// onMessage 回调中 SNAP:HQ 分支 (line 940-970) 简化为：
else if (cmd == "SNAP:HQ") {
    if (!snapshot_in_progress && !snap_requested) {
        snap_requested = true;  // 仅标记，立即返回
    }
}

// loop() 末尾（wsAud.poll() 之后）轮询执行：
void loop() {
  // ... 现有 WS 重连 + poll 逻辑 ...

  // 在 poll 之外处理 SNAP（不阻塞 poll）：
  if (snap_requested) {
    snap_requested = false;
    handle_snap_hq();  // ← 函数体即原来的 SNAP:HQ 逻辑
  }
  delay(2);
}
```

如果 `handle_snap_hq()` 中的 500ms delay 仍然影响后续循环，可改为 FreeRTOS 一次性任务，但上述方案已可保证 WS poll 不被长期阻塞。

---

## 问题 6：UDP 无错误检查（低）

```cpp
// line 882:
udp.begin(0);  // 当前无错误检查

// 改为：
if (!udp.begin(0)) {
  Serial.println("[UDP] begin failed");
} else {
  Serial.println("[UDP] ready");
}

// line 854-857 发送也建议加返回值检查：
if (udp.beginPacket(UDP_HOST, UDP_PORT)) {
  udp.write((const uint8_t*)buf, n);
  if (!udp.endPacket()) {
    // 静默失败，不阻塞 IMU 循环
  }
}
```

---

## 修复优先级

| 顺序 | 问题 | 理由 |
|---|---|---|
| 1 | 跨核 cache 不可见 | 已在影响喇叭关不掉、麦克风关不掉 |
| 2 | SCCB 同核抢占 | 概率性传感器死锁，需要硬件复位恢复 |
| 3 | stopStreamWav 悬空指针 | 快速开关音频流时可能重复创建任务 |
| 4 | SNAP:HQ 阻塞 loop | 抓拍时其他服务短暂停顿 |
| 5 | I2S 软互斥 | TTS 未启用，暂无影响 |
| 6 | UDP 错误检查 | 纯防御性编程 |
