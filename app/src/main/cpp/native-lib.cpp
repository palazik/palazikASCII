#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>

#define LOG_TAG "palazikASCII"

static std::vector<uint8_t> g_yPlane;
static int g_width = 0, g_height = 0;
static std::mutex g_mutex;

static constexpr char kRamp[] = " .:-=+*#%@";
static constexpr int  kRampLen = sizeof(kRamp) - 1;

extern "C" JNIEXPORT void JNICALL
Java_dev_palazik_palazikascii_MainActivity_feedFrame(
        JNIEnv* env, jobject,
        jbyteArray yBytes, jint width, jint height) {
    jsize len = env->GetArrayLength(yBytes);
    std::lock_guard<std::mutex> lock(g_mutex);
    g_yPlane.resize(len);
    env->GetByteArrayRegion(yBytes, 0, len, reinterpret_cast<jbyte*>(g_yPlane.data()));
    g_width  = width;
    g_height = height;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_palazik_palazikascii_MainActivity_getLatestAsciiFrame(
        JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_yPlane.empty()) {
        return env->NewStringUTF("[ waiting for camera... ]");
    }

    const int cols = 80;
    const int rows = 40;
    const int cellW = g_width  / cols;
    const int cellH = g_height / rows;
    if (cellW == 0 || cellH == 0) return env->NewStringUTF("[ low res ]");

    std::string out;
    out.reserve(rows * (cols + 1));

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            uint32_t sum = 0;
            int count = 0;
            for (int dy = 0; dy < cellH; dy++) {
                int y = r * cellH + dy;
                if (y >= g_height) break;
                for (int dx = 0; dx < cellW; dx++) {
                    int x = c * cellW + dx;
                    if (x >= g_width) break;
                    sum += static_cast<uint8_t>(g_yPlane[y * g_width + x]);
                    count++;
                }
            }
            uint8_t luma = count ? (sum / count) : 0;
            out += kRamp[(luma * (kRampLen - 1)) / 255];
        }
        out += '\n';
    }
    return env->NewStringUTF(out.c_str());
}
