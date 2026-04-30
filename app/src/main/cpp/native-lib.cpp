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

    // If the phone is upright, width and height are swapped for the UI
    bool isPortrait = (g_rotation == 90 || g_rotation == 270);
    int frameW = isPortrait ? g_height : g_width;
    int frameH = isPortrait ? g_width : g_height;

    // Portrait-optimized grid (64 columns, 128 rows fits most phones perfectly)
    int cols = isPortrait ? 64 : 100;
    int rows = isPortrait ? 128 : 50;

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
                    
                    // Rotate coordinates to fix the "perevornuto" issue
                    if (g_rotation == 90) { // Back camera
                        rawX = srcY;
                        rawY = g_height - 1 - srcX;
                    } else if (g_rotation == 270) { // Front camera
                        rawX = g_width - 1 - srcY;
                        rawY = srcX;
                    }

                    // Prevent crashing if a pixel goes out of bounds
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
