// JNI bridge between Kotlin (LlamaNative) and llama.cpp.
//
// The chat entry point takes an OpenAI chat-completions request body (UTF-8 JSON),
// formats it with the model's own Jinja chat template (incl. tools), generates,
// streams content deltas back through a callback, and returns the final parsed
// message (content / reasoning / tool calls) as UTF-8 JSON.

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "json.h"
#include "llama.h"
#include "sampling.h"

#define TAG "LocalLLM-native"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using json = common_json;

namespace {

constexpr int N_BATCH = 512;

struct Engine {
    llama_model *             model = nullptr;
    llama_context *           ctx   = nullptr;
    const llama_vocab *       vocab = nullptr;
    common_chat_templates_ptr tmpls;
    std::vector<llama_token>  cached_tokens;  // tokens currently held in the KV cache (seq 0)
    int                       n_ctx = 0;
};

void android_log_callback(ggml_log_level level, const char * text, void * /*user*/) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: break;
    }
    __android_log_write(prio, "llama.cpp", text);
}

std::string jbytes_to_string(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    const jsize len = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray string_to_jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

std::string jstring_to_string(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Number of trailing bytes that form an incomplete UTF-8 sequence.
size_t utf8_incomplete_tail(const std::string & s) {
    for (size_t i = 1; i <= 4 && i <= s.size(); ++i) {
        const unsigned char c = static_cast<unsigned char>(s[s.size() - i]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return need > i ? i : 0;
    }
    return 0;
}

// Length of the longest suffix of `text` that is a prefix of any stop string.
size_t partial_stop_len(const std::string & text, const std::vector<std::string> & stops) {
    size_t best = 0;
    for (const auto & stop : stops) {
        const size_t max_len = std::min(stop.size() - 1, text.size());
        for (size_t len = max_len; len > best; --len) {
            if (text.compare(text.size() - len, len, stop, 0, len) == 0) {
                best = len;
                break;
            }
        }
    }
    return best;
}

std::string random_id(const std::string & prefix) {
    static const char chars[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static std::mt19937 rng{std::random_device{}()};
    std::uniform_int_distribution<int> dist(0, sizeof(chars) - 2);
    std::string s = prefix;
    for (int i = 0; i < 24; ++i) s += chars[dist(rng)];
    return s;
}

bool decode_tokens(Engine * e, const llama_token * tokens, int n) {
    for (int i = 0; i < n; i += N_BATCH) {
        const int n_cur = std::min(N_BATCH, n - i);
        common_batch batch = common_batch_get_one(e->ctx, tokens + i, n_cur);
        if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            return false;
        }
    }
    return true;
}

json tool_calls_to_json(const std::vector<common_chat_tool_call> & calls) {
    json arr = json::array();
    for (const auto & tc : calls) {
        json j = json::object();
        j["id"]        = tc.id;
        j["name"]      = tc.name;
        j["arguments"] = tc.arguments;
        arr.push_back(j);
    }
    return arr;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_localllm_server_data_inference_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring jlib_dir) {
    llama_log_set(android_log_callback, nullptr);
    const std::string lib_dir = jstring_to_string(env, jlib_dir);
    ggml_backend_load_all_from_path(lib_dir.c_str());
    llama_backend_init();
    LOGi("backend initialised from %s", lib_dir.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_localllm_server_data_inference_LlamaNative_nativeSystemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_localllm_server_data_inference_LlamaNative_nativeLoadModel(
        JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    const std::string path = jstring_to_string(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGe("failed to load model %s", path.c_str());
        return 0;
    }

    int ctx_size = n_ctx > 0 ? n_ctx : 4096;
    const int n_ctx_train = llama_model_n_ctx_train(model);
    if (n_ctx_train > 0 && ctx_size > n_ctx_train) ctx_size = n_ctx_train;

    int threads = n_threads;
    if (threads <= 0) {
        const int n = (int) sysconf(_SC_NPROCESSORS_ONLN);
        threads = std::max(1, std::min(8, n <= 4 ? n : n - 2));
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = ctx_size;
    cparams.n_batch         = N_BATCH;
    cparams.n_ubatch        = N_BATCH;
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGe("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto * e  = new Engine();
    e->model  = model;
    e->ctx    = ctx;
    e->vocab  = llama_model_get_vocab(model);
    e->n_ctx  = (int) llama_n_ctx(ctx);
    try {
        e->tmpls = common_chat_templates_init(model, "");
    } catch (const std::exception & ex) {
        LOGw("chat template init failed (%s), falling back to chatml", ex.what());
        e->tmpls = common_chat_templates_init(model, "chatml");
    }
    LOGi("model loaded: n_ctx=%d threads=%d", e->n_ctx, threads);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_localllm_server_data_inference_LlamaNative_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    e->tmpls.reset();
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT jstring JNICALL
Java_com_localllm_server_data_inference_LlamaNative_nativeModelInfo(JNIEnv * env, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return env->NewStringUTF("{}");
    char desc[256];
    llama_model_desc(e->model, desc, sizeof(desc));
    json j = json::object();
    j["description"] = std::string(desc);
    j["n_params"]    = (int64_t) llama_model_n_params(e->model);
    j["size_bytes"]  = (int64_t) llama_model_size(e->model);
    j["n_ctx"]       = e->n_ctx;
    j["n_ctx_train"] = llama_model_n_ctx_train(e->model);
    auto caps = common_chat_templates_get_caps(e->tmpls.get());
    j["supports_tools"] = caps["supports_tools"];
    return env->NewStringUTF(j.dump().c_str());
}

// requestJson: OpenAI chat completion request body (UTF-8)
// callback:    object with `boolean onDelta(byte[] json)`; return false to abort
// returns:     UTF-8 JSON {content, reasoning_content, tool_calls, finish_reason, usage..., error?}
JNIEXPORT jbyteArray JNICALL
Java_com_localllm_server_data_inference_LlamaNative_nativeChat(
        JNIEnv * env, jobject, jlong handle, jbyteArray jrequest, jobject callback) {
    auto * e = reinterpret_cast<Engine *>(handle);
    json result = json::object();
    auto fail = [&](const std::string & msg) {
        LOGe("chat error: %s", msg.c_str());
        json err = json::object();
        err["error"] = msg;
        return string_to_jbytes(env, err.dump());
    };
    if (!e) return fail("model not loaded");

    jclass    cb_class = env->GetObjectClass(callback);
    jmethodID on_delta = env->GetMethodID(cb_class, "onDelta", "([B)Z");
    if (!on_delta) return fail("invalid callback");

    try {
        const json body = json::parse(jbytes_to_string(env, jrequest));

        // ---- chat template ----
        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(body.at("messages"));
        const bool has_tools = body.contains("tools") && body.at("tools").is_array() && body.at("tools").size() > 0;
        if (has_tools) {
            inputs.tools = common_chat_tools_parse_oaicompat(body.at("tools"));
            if (body.contains("tool_choice")) {
                const json & tc = body.at("tool_choice");
                if (tc.is_string()) {
                    inputs.tool_choice = common_chat_tool_choice_parse_oaicompat(tc.get<std::string>());
                } else if (tc.is_object()) {
                    inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_REQUIRED;
                }
            }
            inputs.parallel_tool_calls = body.value("parallel_tool_calls", false);
        }
        if (body.contains("response_format") && body.at("response_format").is_object()) {
            const json & rf = body.at("response_format");
            const std::string type = rf.value("type", std::string("text"));
            json schema;
            if (type == "json_object") {
                schema = rf.value("schema", json::object());
            } else if (type == "json_schema") {
                schema = rf.value("json_schema", json::object()).value("schema", json::object());
            }
            if (schema.is_object()) {
                if (schema.size() == 0) schema["type"] = "object";
                inputs.json_schema = schema.dump();
            }
        }
        inputs.use_jinja        = true;
        inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        inputs.enable_thinking  = true;
        if (body.contains("reasoning_effort") && body.value("reasoning_effort", std::string("")) == "none") {
            inputs.enable_thinking = false;
        }

        common_chat_params chat_params;
        try {
            chat_params = common_chat_templates_apply(e->tmpls.get(), inputs);
        } catch (const std::exception & ex) {
            LOGw("jinja template failed (%s), retrying with legacy template", ex.what());
            inputs.use_jinja = false;
            inputs.tools.clear();
            inputs.json_schema.clear();
            chat_params = common_chat_templates_apply(e->tmpls.get(), inputs);
        }

        // ---- sampling ----
        common_params_sampling sparams;
        sparams.temp           = body.value("temperature", 0.7);
        sparams.top_p          = body.value("top_p", 0.95);
        sparams.top_k          = body.value("top_k", 40);
        sparams.min_p          = body.value("min_p", 0.05);
        sparams.penalty_repeat = body.value("repeat_penalty", 1.0);
        if (body.contains("seed") && body.at("seed").is_number_integer()) {
            sparams.seed = (uint32_t) body.at("seed").get<int64_t>();
        }
        if (!chat_params.grammar.empty()) {
            sparams.grammar = common_grammar(
                has_tools ? COMMON_GRAMMAR_TYPE_TOOL_CALLS : COMMON_GRAMMAR_TYPE_OUTPUT_FORMAT,
                chat_params.grammar);
            sparams.grammar_lazy = chat_params.grammar_lazy;
        }
        for (const auto & t : chat_params.preserved_tokens) {
            auto ids = common_tokenize(e->vocab, t, false, true);
            if (ids.size() == 1) sparams.preserved_tokens.insert(ids[0]);
        }
        for (const auto & trig : chat_params.grammar_triggers) {
            if (trig.type == COMMON_GRAMMAR_TRIGGER_TYPE_WORD) {
                auto ids = common_tokenize(e->vocab, trig.value, false, true);
                if (ids.size() == 1 && sparams.preserved_tokens.count(ids[0])) {
                    common_grammar_trigger t;
                    t.type  = COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN;
                    t.value = trig.value;
                    t.token = ids[0];
                    sparams.grammar_triggers.push_back(t);
                    continue;
                }
            }
            sparams.grammar_triggers.push_back(trig);
        }
        sparams.generation_prompt = chat_params.generation_prompt;

        // ---- parser ----
        common_chat_parser_params pparams(chat_params);
        pparams.reasoning_format  = inputs.reasoning_format;
        pparams.generation_prompt = common_chat_input_tokenize(e->vocab, chat_params.generation_prompt);
        pparams.parse_tool_calls  = has_tools && inputs.tool_choice != COMMON_CHAT_TOOL_CHOICE_NONE;
        if (!chat_params.parser.empty()) {
            pparams.parser.load(chat_params.parser);
        }

        // ---- stop strings ----
        std::vector<std::string> stops = chat_params.additional_stops;
        if (body.contains("stop")) {
            const json & s = body.at("stop");
            if (s.is_string()) {
                stops.push_back(s.get<std::string>());
            } else if (s.is_array()) {
                for (size_t i = 0; i < s.size(); ++i) {
                    if (s[i].is_string()) stops.push_back(s[i].get<std::string>());
                }
            }
        }
        stops.erase(std::remove_if(stops.begin(), stops.end(), [](const std::string & x) { return x.empty(); }), stops.end());

        // ---- prompt processing (with KV prefix reuse) ----
        const auto t_start = std::chrono::steady_clock::now();
        std::vector<llama_token> prompt = common_tokenize(e->ctx, chat_params.prompt, true, true);
        if (prompt.empty()) return fail("empty prompt");
        const int n_prompt = (int) prompt.size();
        if (n_prompt >= e->n_ctx - 4) {
            return fail("prompt is too long (" + std::to_string(n_prompt) + " tokens, context size is " +
                        std::to_string(e->n_ctx) + ")");
        }

        size_t n_keep = 0;
        while (n_keep < e->cached_tokens.size() && n_keep < prompt.size() &&
               e->cached_tokens[n_keep] == prompt[n_keep]) {
            n_keep++;
        }
        if (n_keep == prompt.size()) n_keep--;  // always re-evaluate the last token to get logits

        llama_memory_t mem = llama_get_memory(e->ctx);
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) n_keep, -1)) {
            llama_memory_clear(mem, true);
            n_keep = 0;
        }
        e->cached_tokens.resize(n_keep);

        if (!decode_tokens(e, prompt.data() + n_keep, n_prompt - (int) n_keep)) {
            llama_memory_clear(mem, true);
            e->cached_tokens.clear();
            return fail("llama_decode failed while processing the prompt");
        }
        e->cached_tokens = prompt;
        const auto t_prompt_done = std::chrono::steady_clock::now();

        // ---- generation ----
        common_sampler_ptr smpl(common_sampler_init(e->model, sparams));
        if (!smpl) return fail("failed to initialise sampler (invalid grammar?)");

        int max_tokens = body.value("max_tokens", body.value("max_completion_tokens", -1));
        const int room = e->n_ctx - n_prompt - 1;
        if (max_tokens <= 0 || max_tokens > room) max_tokens = room;

        common_chat_input generated;
        std::string       finish_reason = "length";
        int               n_gen         = 0;
        common_chat_msg   prev_msg;
        std::vector<std::string> tool_call_ids;
        auto gen_id = []() { return random_id("call_"); };
        bool aborted = false;

        auto emit_diffs = [&](const common_chat_msg & msg) -> bool {
            auto diffs = common_chat_msg_diff::compute_diffs(prev_msg, msg);
            prev_msg   = msg;
            for (const auto & d : diffs) {
                if (d.content_delta.empty() && d.reasoning_content_delta.empty()) continue;
                json dj = json::object();
                if (!d.content_delta.empty()) dj["content"] = d.content_delta;
                if (!d.reasoning_content_delta.empty()) dj["reasoning_content"] = d.reasoning_content_delta;
                jbyteArray arr = string_to_jbytes(env, dj.dump());
                jboolean   ok  = env->CallBooleanMethod(callback, on_delta, arr);
                env->DeleteLocalRef(arr);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    return false;
                }
                if (!ok) return false;
            }
            return true;
        };

        while (n_gen < max_tokens) {
            const llama_token id = common_sampler_sample(smpl.get(), e->ctx, -1);
            common_sampler_accept(smpl.get(), id, true);

            if (llama_vocab_is_eog(e->vocab, id)) {
                finish_reason = "stop";
                break;
            }
            n_gen++;

            const bool special = sparams.preserved_tokens.count(id) > 0;
            generated.append(common_token_to_piece(e->ctx, id, special), id);

            if (!decode_tokens(e, &id, 1)) {
                finish_reason = "error";
                break;
            }
            e->cached_tokens.push_back(id);

            // full stop string match -> truncate and finish
            bool stopped = false;
            for (const auto & stop : stops) {
                const size_t pos = generated.text.find(stop);
                if (pos != std::string::npos) {
                    generated.truncate(pos);
                    stopped = true;
                    break;
                }
            }
            if (stopped) {
                finish_reason = "stop";
                break;
            }

            // stream: hold back partial UTF-8 and partial stop strings
            const size_t hold = std::max(utf8_incomplete_tail(generated.text), partial_stop_len(generated.text, stops));
            common_chat_input visible = hold > 0 ? generated.substr(0, generated.size() - hold) : generated;
            try {
                common_chat_msg msg = common_chat_parse(visible, true, pparams);
                msg.set_tool_call_ids(tool_call_ids, gen_id);
                if (!emit_diffs(msg)) {
                    aborted = true;
                    break;
                }
            } catch (const std::exception &) {
                // partial output not parseable yet
            }
        }

        // ---- final parse ----
        common_chat_msg final_msg;
        try {
            final_msg = common_chat_parse(generated, false, pparams);
        } catch (const std::exception & ex) {
            LOGw("final parse failed (%s), returning raw text", ex.what());
            final_msg = common_chat_msg();
            final_msg.role    = "assistant";
            final_msg.content = generated.text;
        }
        final_msg.set_tool_call_ids(tool_call_ids, gen_id);
        if (!aborted) emit_diffs(final_msg);

        if (aborted) finish_reason = "abort";
        else if (!final_msg.tool_calls.empty()) finish_reason = "tool_calls";

        const auto t_end = std::chrono::steady_clock::now();
        const double prompt_ms = std::chrono::duration<double, std::milli>(t_prompt_done - t_start).count();
        const double gen_ms    = std::chrono::duration<double, std::milli>(t_end - t_prompt_done).count();

        result["content"]           = final_msg.content;
        result["reasoning_content"] = final_msg.reasoning_content;
        result["tool_calls"]        = tool_calls_to_json(final_msg.tool_calls);
        result["finish_reason"]     = finish_reason;
        result["prompt_tokens"]     = n_prompt;
        result["cached_tokens"]     = (int) n_keep;
        result["completion_tokens"] = n_gen;
        result["prompt_ms"]         = prompt_ms;
        result["generation_ms"]     = gen_ms;
        LOGi("chat done: prompt=%d (cached %d) gen=%d finish=%s pp=%.0fms tg=%.0fms",
             n_prompt, (int) n_keep, n_gen, finish_reason.c_str(), prompt_ms, gen_ms);
        return string_to_jbytes(env, result.dump_safe());
    } catch (const std::exception & ex) {
        return fail(ex.what());
    }
}

}  // extern "C"
