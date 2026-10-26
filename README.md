# LocalLLM Server

Run a GGUF language model **on your Android phone** with [llama.cpp](https://github.com/ggml-org/llama.cpp) and serve it to your LAN through an **OpenAI-compatible API** (`http://<phone-ip>:8080/v1`). Built for using a phone as the model backend for [Hermes Agent](https://github.com/NousResearch/hermes-agent) on a laptop, and for Telegram via Hermes Gateway.

- On-device inference via llama.cpp (JNI, arm64 NEON/i8mm/dotprod CPU variants picked at runtime)
- `GET /v1/models`, `POST /v1/chat/completions` (incl. `stream: true` SSE and `tools` / `tool_calls`), `GET /health`
- Prompts are built with the model's own Jinja chat template; prompt-prefix cache reuse between turns
- Foreground service + partial wake lock + Wi-Fi lock, so the server keeps running in the background and with the screen off
- Model catalog with resumable downloads (system DownloadManager), custom URL download, or import a local `.gguf`
- Jetpack Compose UI: Server status / Models / Logs / Settings
- Optional API key (`Authorization: Bearer <key>`)

## Requirements

- Android 8.0+ (API 26). Recommended: 64-bit ARM phone with **8 GB+ RAM** for 3-4B models (6 GB works for ≤1.5B models)
- Phone and laptop on the same Wi-Fi (or laptop on the phone's hotspot)

## Install

Download `LocalLLMServer.apk` and install it (allow "Install unknown apps"), or build it:

```bash
# Requires JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease            # app/build/outputs/apk/release/app-release.apk (arm64-v8a + x86_64)
./gradlew assembleDebug -Pabis=arm64-v8a   # faster: phone-only debug build
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build is signed with the debug key so it installs directly; use your own keystore for distribution.

## Use

1. Open the app → **Models** → tap **Get** next to a model (Phi-4-mini Q4_K_M ≈ 2.5 GB is the default recommendation; Qwen2.5-0.5B ≈ 0.4 GB is good for a quick test). Or paste a direct `.gguf` URL, or **Import file…**.
2. Select the model (radio button) → **Server** tab → **Start server**. The first load takes a few seconds to ~1 min.
3. Copy the **Base URL** shown on the Server tab, e.g. `http://192.168.1.42:8080/v1`.
4. Optional but recommended: Settings → **Disable battery optimization**, so Android does not throttle the service.

The model id is always `local` (any model name in requests is accepted).

### Verify from the laptop

```bash
PHONE=192.168.1.42
curl http://$PHONE:8080/health
curl http://$PHONE:8080/v1/models
curl http://$PHONE:8080/v1/chat/completions -H 'Content-Type: application/json' -d '{
  "model": "local",
  "messages": [{"role": "user", "content": "Say hello in one sentence."}],
  "max_tokens": 64
}'
# streaming
curl -N http://$PHONE:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"local","stream":true,"messages":[{"role":"user","content":"Count to 5"}]}'
```

If you set an API key, add `-H "Authorization: Bearer <key>"`.

## Hermes Agent (laptop)

```bash
hermes model        # choose "Custom endpoint"
#   Base URL: http://<phone-ip>:8080/v1
#   API key:  anything (or the key you set in the app)
#   Model:    local
```

or in `~/.hermes/config.yaml`:

```yaml
model:
  provider: custom
  base_url: http://192.168.1.42:8080/v1
  api_key: local          # or your app API key
  default: local
  context_length: 4096    # match the app's Context size setting
```

Tool calling works with models whose chat template supports tools (Phi-4-mini, Qwen2.5, FunctionGemma). Agents send large system prompts + tool schemas, so raise **Context size** in Settings to 8192 if you have the RAM (≥8 GB) and expect the first prompt of a session to take a while on a phone CPU; later turns reuse the cached prefix.

## Telegram via Hermes Gateway

1. Create a bot with [@BotFather](https://t.me/BotFather) and copy the token; get your user id from [@userinfobot](https://t.me/userinfobot).
2. On the laptop: `hermes gateway setup` → choose Telegram → paste the token and allowed user id (or set `TELEGRAM_BOT_TOKEN` / `TELEGRAM_ALLOWED_USERS` in `~/.hermes/.env`).
3. `hermes gateway` (keep it running). Messages to your bot → Hermes on the laptop → your phone's model → reply in Telegram.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Port | 8080 | |
| Bind address | 0.0.0.0 | `127.0.0.1` = only apps on the phone |
| Context size | 4096 | KV cache memory grows with it |
| Threads | 0 (auto) | auto = performance cores |
| Default max output tokens | 1024 | used when the request has no `max_tokens` |
| Default temperature | 0.7 | used when the request has no `temperature` |
| API key | empty | empty = no auth |
| Auto-start / Keep screen on / Start on boot | on / off / off | |

## Project layout

```
app/src/main/cpp/llama_jni.cpp     JNI bridge: load model, chat templates, sampling, streaming, tool-call parsing
app/src/main/cpp/llama.cpp/        llama.cpp source (tag b11500)
app/src/main/java/com/localllm/server/
  data/inference/                  LlamaEngine (thread-safe Kotlin wrapper)
  data/repository/                 models (download/import), settings (DataStore), logs
  server/                          Ktor CIO server + OpenAI routes
  service/                         foreground service, boot receiver
  ui/                              Compose screens
```

## Known limitations

- CPU-only inference (no GPU/NPU offload). Expect roughly 5-15 tok/s for a 3-4B Q4 model on a recent flagship, slower on mid-range phones; prompt processing of long agent prompts can take tens of seconds.
- One request is processed at a time; concurrent requests queue.
- Some OEMs (Xiaomi, Huawei, Samsung "deep sleep") kill background apps aggressively — disable battery optimization and allow background activity for the app.
- Plain HTTP on the LAN. Use the API key and only trusted networks.
