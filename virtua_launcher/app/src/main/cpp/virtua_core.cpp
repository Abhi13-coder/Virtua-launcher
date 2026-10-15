#include <jni.h>
#include <string>
#include <android/log.h>
#include <map>
#include <mutex>
#include <vector>
#include <cstdlib>
#include <climits>

#define LOG_TAG "virtua_core"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::mutex g_mu;
static std::map<std::string, bool> g_enabled;

extern "C" JNIEXPORT jstring JNICALL
Java_com_virtua_launcher_nativeapi_VirtuaNative_nativeBackendName(JNIEnv *env, jobject) {
    // Placeholder: real build probes NNAPI / GPU.
    return env->NewStringUTF("NPU>GPU>CPU");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_virtua_launcher_nativeapi_VirtuaNative_nativePipelineInit(JNIEnv *, jobject) {
    LOGI("pipeline init (stub core; load streamed .so at runtime)");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_virtua_launcher_nativeapi_VirtuaNative_nativeSetFeatureEnabled(
        JNIEnv *env, jobject, jstring featureId, jboolean enabled) {
    const char *id = env->GetStringUTFChars(featureId, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_mu);
        g_enabled[id ? id : ""] = enabled == JNI_TRUE;
    }
    LOGI("feature %s -> %d", id, (int) enabled);
    if (id) env->ReleaseStringUTFChars(featureId, id);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_virtua_launcher_nativeapi_VirtuaNative_nativeProcessFrameStub(
        JNIEnv *env, jobject, jint width, jint height) {
    // Order: depth? → compactflow → vpr+fsrcnn → rays? → hdr+
    std::string path = "depth>flow>vpr>fsrcnn>rays>hdrplus";
    char buf[192];
    snprintf(buf, sizeof(buf), "%s@%dx%d", path.c_str(), width, height);
    return env->NewStringUTF(buf);
}

// Real dense motion estimator: SAD block matching over a small search
// window. Stands in for CompactFlowNet (arXiv 2412.13273), which has no
// public code or weights as of Oct 2026 — this is CPU-only and much less
// accurate than a trained net, but it is a real, working motion estimate,
// not a stub. Swap this out once real CompactFlow (or an equivalent trained
// model) is available.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_virtua_launcher_nativeapi_VirtuaNative_nativeComputeMotionField(
        JNIEnv *env, jobject,
        jbyteArray prevLuma, jbyteArray currLuma,
        jint width, jint height, jint blockSize, jint searchRadius) {

    jbyte *prev = env->GetByteArrayElements(prevLuma, nullptr);
    jbyte *curr = env->GetByteArrayElements(currLuma, nullptr);

    int bs = blockSize > 0 ? blockSize : 16;
    int radius = searchRadius > 0 ? searchRadius : 4;
    int blocksX = width / bs;
    int blocksY = height / bs;
    if (blocksX < 1) blocksX = 1;
    if (blocksY < 1) blocksY = 1;

    std::vector<float> out(static_cast<size_t>(blocksX) * blocksY * 3, 0.0f);

    auto sample = [&](jbyte *buf, int x, int y) -> int {
        if (x < 0) x = 0;
        if (x >= width) x = width - 1;
        if (y < 0) y = 0;
        if (y >= height) y = height - 1;
        return buf[y * width + x] & 0xFF;
    };

    for (int by = 0; by < blocksY; by++) {
        for (int bx = 0; bx < blocksX; bx++) {
            int cx = bx * bs + bs / 2;
            int cy = by * bs + bs / 2;

            int bestDx = 0, bestDy = 0;
            long bestSad = LONG_MAX;
            long worstSad = 0;

            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    long sad = 0;
                    for (int oy = -bs / 2; oy < bs / 2; oy++) {
                        for (int ox = -bs / 2; ox < bs / 2; ox++) {
                            int pv = sample(prev, cx + ox, cy + oy);
                            int cv = sample(curr, cx + ox + dx, cy + oy + dy);
                            sad += std::abs(pv - cv);
                        }
                    }
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestDx = dx;
                        bestDy = dy;
                    }
                    if (sad > worstSad) worstSad = sad;
                }
            }

            float confidence = worstSad > 0
                    ? 1.0f - static_cast<float>(bestSad) / static_cast<float>(worstSad)
                    : 0.0f;

            size_t idx = (static_cast<size_t>(by) * blocksX + bx) * 3;
            out[idx + 0] = static_cast<float>(bestDx);
            out[idx + 1] = static_cast<float>(bestDy);
            out[idx + 2] = confidence;
        }
    }

    env->ReleaseByteArrayElements(prevLuma, prev, JNI_ABORT);
    env->ReleaseByteArrayElements(currLuma, curr, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}
