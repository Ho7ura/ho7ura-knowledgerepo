# 代码文件总览

## 需要编译器吗？

**不需要。** 除了 `compile/` 里的 ESP32 固件（C++/Arduino，需 PlatformIO），项目根目录所有代码都是 Python 脚本，只需 Python 3.9-3.11 + `pip install -r requirements.txt` 即可运行。需要 NVIDIA GPU（CUDA 11.8+）做模型推理，需要阿里云 DashScope API Key 做语音识别和多模态对话。

## 整体架构

这是一个 AI 盲人导航眼镜的**服务器端**：眼镜上的 ESP32 把视频/音频/IMU 数据通过 WiFi 发给电脑，电脑跑这些 Python 代码做 AI 推理，然后把语音指令返回给眼镜。

```
ESP32 眼镜 ──WiFi──> 电脑(本项目的 Python 代码) ──语音指令──> ESP32 眼镜
                         │
                         ├── DashScope API (ASR + Qwen-Omni)
                         ├── 本地 GPU 推理 (YOLO, MediaPipe)
                         └── Web 监控页面 (浏览器访问 :8081)
```

---

## 各文件作用

### 入口与调度

| 文件 | 作用 |
|---|---|
| `app_main.py` | FastAPI 主服务入口。WebSocket 路由管理、模型加载、全局状态协调、音视频流分发 |
| `navigation_master.py` | 导航统领器（状态机）。管理五种模式切换：IDLE → CHAT / BLINDPATH_NAV / CROSSING / TRAFFIC_LIGHT_DETECTION / ITEM_SEARCH |

### 三大工作流

| 文件 | 作用 |
|---|---|
| `workflow_blindpath.py` | **盲道导航**。YOLO 盲道分割 + 障碍物检测 + 转弯检测 + Lucas-Kanade 光流稳定 + 方向语音引导。内部状态：ONBOARDING → NAVIGATING → MANEUVERING_TURN → AVOIDING_OBSTACLE |
| `workflow_crossstreet.py` | **过马路导航**。斑马线检测与方向对齐、红绿灯状态感知、引导用户对准斑马线中心直行通过 |
| `yolomedia.py` | **物品查找**。用户说"帮我找红牛"，系统用 YOLO-E 开放词汇检测 + ByteTrack 追踪 + MediaPipe 手部检测，引导用户伸手靠近并抓取目标物品 |

### 语音模块

| 文件 | 作用 |
|---|---|
| `asr_core.py` | 阿里云 DashScope Paraformer 实时语音识别。带 VAD（语音活动检测），识别结果回调给导航系统 |
| `omni_client.py` | Qwen-Omni-Turbo 多模态对话客户端。输入图像+文本，流式输出语音回复 |
| `qwen_extractor.py` | 中文物品名→英文标签映射（"红牛"→"Red_Bull"），供 YOLO-E 检测使用。优先本地词典，未命中时调千问 API |
| `qwenturbo_template.py` | 千问 Turbo API 调用示例/模板，独立文件不参与系统运行 |
| `audio_player.py` | 统一音频播放管理。TTS 语音播放、多路音频混音、音量控制、线程安全 |
| `audio_stream.py` | 音频流管道。将合成音频以 HTTP 推流给 ESP32 播放 |
| `audio_compressor.py` | 音频压缩（PCM16→μ-law），带宽减半，降低 WiFi 传输压力 |

### 视觉/模型推理

| 文件 | 作用 |
|---|---|
| `yoloe_backend.py` | YOLO-E 开放词汇检测后端。支持任意文本提示的实时目标检测与分割，配合 ByteTrack 做目标追踪 |
| `trafficlight_detection.py` | 红绿灯检测。双保险策略：YOLO 模型检测 → HSV 颜色分类兜底。输出红灯/绿灯/黄灯/未知 |
| `obstacle_detector_client.py` | 障碍物检测。白名单类别过滤 + 路径掩码内检测 + 面积/位置/危险度计算 |
| `crosswalk_awareness.py` | 斑马线感知监控。基于画面占比判断阶段：远处发现(1%) → 靠近(8%) → 很近(18%) → 到达可通行(25%) |
| `models.py` | cv、torch 模型、MediaPipe 初始化相关的数据类定义 |
| `utils.py` | 物品名中英文映射表（`ITEM_TO_CLASS_MAP`）、障碍物类别中文名映射、辅助工具函数 |

### 视频/IO

| 文件 | 作用 |
|---|---|
| `bridge_io.py` | 线程安全帧缓冲（生产者-消费者模式）。解耦 ESP32 JPEG 接收与 AI 推理处理，支持多客户端订阅 |
| `sync_recorder.py` | 音视频同步录制。自动时间戳命名，保存到 `recordings/` 目录 |
| `local_cam.py` | 本地 USB 摄像头模拟 ESP32 推流。调试时无需连接硬件，直接 `python local_cam.py` 推流到服务器 |

### Web 前端（监控页面）

| 文件 | 作用 |
|---|---|
| `templates/index.html` | Web 监控主界面。视频流显示、状态面板、IMU 3D 可视化 |
| `static/main.js` | 主 JS 逻辑。WebSocket 连接管理、UI 更新、事件处理 |
| `static/vision.js` | 视频流接收与 Canvas 渲染，FPS 计算 |
| `static/vision_renderer.js` | 检测框、分割蒙版、引导标注的 Canvas 绘制 |
| `static/visualizer.js` | IMU 数据 Three.js 3D 可视化，实时渲染设备姿态 |
| `static/vision.css` | 监控页面样式表 |

---

## 资源目录

| 目录 | 内容 |
|---|---|
| `model/` | AI 模型权重文件：`yolo-seg.pt`(盲道分割)、`yoloe-11l-seg.pt`(开放词汇)、`shoppingbest5.pt`(物品识别)、`trafficlight.pt`(红绿灯)、`hand_landmarker.task`(手部关键点) |
| `music/` | 系统提示音 WAV 文件（方向指令："向前""向左""已对中"等） |
| `voice/` | 预录导航语音库，77 个 WAV 覆盖全部导航场景（避障、转弯、斑马线、红绿灯、物品引导等） |
| `recordings/` | 运行时自动保存的音视频录制（`video_*.avi`, `audio_*.wav`），按时间戳命名 |
| `docs/` | 硬件接线说明、I2C 冲突、跨核任务等参考文档 |

### 大型二进制文件

| 文件 | 说明 |
|---|---|
| `mobileclip_blt.ts` (~600MB) | MobileCLIP 模型权重，供开放词汇视觉检测使用 |
| `hand_landmarker.task` (~8MB) | MediaPipe 手部关键点检测模型（根目录和 model/ 各一份） |

---

## 配置与部署文件

| 文件 | 作用 |
|---|---|
| `.env` | API Key 等敏感环境变量 |
| `configuration.json` | `{"task":"video-object-segmentation"}` |
| `requirements.txt` | Python 依赖声明 |
| `Dockerfile` | 基于 CUDA 11.8 的 Docker 镜像 |
| `docker-compose.yml` | Docker Compose 编排 |
| `setup.sh` | Linux/macOS 一键安装脚本 |
| `setup.bat` | Windows 一键安装脚本 |
| `CHANGELOG.md` | 版本更新日志 |
| `LICENSE` | MIT 许可证 |

---

## 启动方式

```bash
pip install -r requirements.txt
python app_main.py
# 浏览器打开 http://localhost:8081 查看监控页面
```

如需本地摄像头调试（不连 ESP32）：

```bash
python local_cam.py    # 另开终端，模拟摄像头推流
```
