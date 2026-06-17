##  摄像头 SCCB（I2C 控制总线）—— 同一核心但跨任务

涉及三条执行路径：

| 执行上下文 | 核心 | 优先级 | 操作 |
|-----------|------|--------|------|
| WS 消息回调 (loop) | Core 1 | prio 1 | `s->set_framesize()` / `s->set_quality()` |
| cam_cap 任务 | Core 1 | prio 4 | `esp_camera_fb_get()` → 可能读传感器寄存器 |
| SNAP:HQ 路径 | Core 1 | prio 1 | 临时改分辨率/画质，抓拍后恢复 |

虽然 SNAP:HQ 期间用 `snapshot_in_progress` 标志让 `cam_cap` 主动跳过采集，但如果 `cam_cap`（prio 4）抢占了正在改参数的 loop（prio 1）：

```
loop (prio 1):  s->set_framesize(...)  ──→  被 cam_cap 抢占
cam_cap (prio 4):  esp_camera_fb_get()   ←  此时传感器寄存器处于半修改状态
```

loop 的 SCCB 操作被中断一半，传感器可能进入非法状态。

摄像头 SCCB 冲突双方：

一方：cam_cap (prio 4) — 读传感器
compile.ino:172


camera_fb_t* fb = esp_camera_fb_get();  // 底层走 SCCB 取帧
另一方：loop() WS 回调 (prio 1) — 写传感器
compile.ino:931-934


s->set_quality(s, q);  // 动态画质，走 SCCB 写寄存器
compile.ino:950-953 — SNAP:HQ 更狠：


s->set_framesize(s, target_fs);  // SCCB 写
s->set_quality(s, 18);           // SCCB 写
"保护"：snapshot_in_progress 标志
compile.ino:169


if (snapshot_in_progress) { vTaskDelay(pdMS_TO_TICKS(5)); continue; }
为什么护不住
和喇叭一样——标志只表达了意图，没有强制力。cam_cap 优先级 4，loop 优先级 1。loop 设完 snapshot_in_progress = true 之后、开始写 SCCB 之前的那一瞬间，cam_cap 完全可以抢进来读帧，loop 根本没机会跑。

这就是同核抢占的场景：不是跨核 cache 问题，而是②层——读-改-写/多步操作被高优先级打断。喇叭那两个是同优先级时间片交替，摄像头这个是高优先级直接抢。保护方式都一样脆弱——全靠 volatile flag 约定，没有真正的锁。