"""
用本地摄像头模拟 ESP32，推流到 AI 眼镜服务器
"""
import asyncio
import cv2
import websockets

SERVER = "ws://localhost:8081/ws/camera"

async def main():
    print(f"[CAM] 连接 {SERVER} ...")
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        print("[CAM] 错误：找不到摄像头")
        return

    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
    cap.set(cv2.CAP_PROP_FPS, 15)

    async with websockets.connect(SERVER, max_size=2**26, ping_interval=None) as ws:
        print("[CAM] 已连接，开始推流")
        while True:
            ret, frame = cap.read()
            if not ret:
                await asyncio.sleep(0.01)
                continue
            _, jpeg = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 70])
            try:
                await ws.send(jpeg.tobytes())
                await asyncio.sleep(0.001)
            except websockets.ConnectionClosed:
                print("[CAM] 连接断开")
                break
    cap.release()

if __name__ == "__main__":
    asyncio.run(main())
