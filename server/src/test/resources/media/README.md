# 富媒体测试资源

E2E 测试用的真实媒体文件（可用 ffmpeg/播放器渲染，非最小字节流）。

## 文件清单

| 文件 | 格式 | 大小 | 用途 |
|------|------|------|------|
| `test_image.png` | PNG 300x300 | ~83K | 图片消息测试（渐变色 + TeamTalk 文字） |
| `test_image.jpg` | JPG | ~11K | JPG 兼容性测试 |
| `test_voice.mp3` | MP3 128kbps | ~32K | 语音消息测试（440Hz 正弦波，2秒） |
| `test_video.mp4` | MP4 H.264 | ~23K | 视频消息测试（testsrc 动画，3秒 640x360） |
| `test_doc.txt` | TXT | ~113B | 文件消息测试 |

## 使用方式

TestPeer 通过 `-Dpeer.arg=<type>` 生成测试文件到临时路径：

```bash
# 图片
./gradlew :server:test --tests "*.TestPeer" -Dpeer.test=generateTestFile -Dpeer.arg=png
# 语音
-Dpeer.arg=mp3
# 视频
-Dpeer.arg=mp4
# 文档
-Dpeer.arg=txt
```

`TestPeer.generateTestFile` 优先从 classpath 读取本目录资源，找不到则 fallback
到 `createMinimalXxx`（最小合法字节流，不可渲染）。

## 重新生成

```bash
cd server/src/test/resources/media
# 图片（PNG，渐变+文字）
ffmpeg -y -f lavfi -i "nullsrc=s=300x300:d=1" \
  -vf "geq=r='40+X*0.7':g='100+Y*0.5':b='200-X*0.3',drawtext=text='TeamTalk':fontcolor=white:fontsize=36:x=(w-text_w)/2:y=(h-text_h)/2:box=1:boxcolor=blue@0.5" \
  -frames:v 1 test_image.png
# 语音（440Hz 正弦波 2秒）
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=2" -codec:a libmp3lame -b:a 128k test_voice.mp3
# 视频（testsrc 动画 3秒）
ffmpeg -y -f lavfi -i "testsrc=duration=3:size=640x360:rate=24" \
  -vf "drawtext=text='TeamTalk Test':fontcolor=white:fontsize=24:x=(w-text_w)/2:y=10" \
  -codec:v libx264 -pix_fmt yuv420p -movflags +faststart test_video.mp4
```
