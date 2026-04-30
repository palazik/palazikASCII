#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>

#define LOG_TAG "palazikASCII"

static std::vector<uint8_t> g_yPlane;
static int g_width = 0, g_height = 0;
static int g_rotation = 0;
static std::mutex g_mutex;

static constexpr char kRamp[] = " .:-=+*#%@";
static constexpr int  kRampLen = sizeof(kRamp) - 1;

extern "C" JNIEXPORT void JNICALL
Java_dev_palazik_palazikascii_MainActivity_feedFrame(
        JNIEnv* env, jobject,
        jbyteArray yBytes, jint width, jint height, jint rotation) { // <-- ADDED ROTATION
    jsize len = env->GetArrayLength(yBytes);
    std::lock_guard<std::mutex> lock(g_mutex);
    g_yPlane.resize(len);
    env->GetByteArrayRegion(yBytes, 0, len, reinterpret_cast<jbyte*>(g_yPlane.data()));
    g_width  = width;
    g_height = height;
    g_rotation = rotation;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_palazik_palazikascii_MainActivity_getLatestAsciiFrame(
        JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_yPlane.empty()) return env->NewStringUTF("[ waiting for camera... ]");

    bool isPortrait = (g_rotation == 90 || g_rotation == 270);
    int frameW = isPortrait ? g_height : g_width;
    int frameH = isPortrait ? g_width : g_height;

    // HIGH RES UPGRADE: 120 columns instead of 64
    int cols = isPortrait ? 120 : 200;
    int rows = isPortrait ? 240 : 100;

    int cellW = frameW / cols;
    int cellH = frameH / rows;

    if (cellW == 0 || cellH == 0) return env->NewStringUTF("[ low res ]");

    std::string out;
    out.reserve(rows * (cols + 1));

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            uint32_t sum = 0;
            int count = 0;
            
            for (int dy = 0; dy < cellH; dy++) {
                for (int dx = 0; dx < cellW; dx++) {
                    int srcX = c * cellW + dx;
                    int srcY = r * cellH + dy;
                    
                    int rawX = srcX;
                    int rawY = srcY;
                    
                    if (g_rotation == 90) { 
                        rawX = srcY;
                        rawY = g_height - 1 - srcX;
                    } else if (g_rotation == 270) { 
                        rawX = g_width - 1 - srcY;
                        rawY = srcX;
                    }

                    if (rawX >= 0 && rawX < g_width && rawY >= 0 && rawY < g_height) {
                        sum += g_yPlane[rawY * g_width + rawX];
                        count++;
                    }
                }
            }
            uint8_t luma = count ? (sum / count) : 0;
            out += kRamp[(luma * (kRampLen - 1)) / 255];
        }
        out += '\n';
    }
    return env->NewStringUTF(out.c_str());
}
