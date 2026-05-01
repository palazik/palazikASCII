#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "palazikASCII"

// ── Frame buffers ─────────────────────────────────────────────────────────────
static std::vector<uint8_t> g_yPlane;
static std::vector<uint8_t> g_uvPlane;
static int g_width    = 0;
static int g_height   = 0;
static int g_rotation = 0;
static std::mutex g_mutex;

// ── ASCII ramp (dark → bright) ────────────────────────────────────────────────
static constexpr char    kRamp[]   = " .:-=+*#%@";
static constexpr int     kRampLen  = sizeof(kRamp) - 1;

// ── Grid resolution ───────────────────────────────────────────────────────────
// Portrait: 80×150 ≈ 12 000   Landscape: 150×80 ≈ 12 000
static constexpr int kColsPortrait  = 80;
static constexpr int kRowsPortrait  = 150;
static constexpr int kColsLandscape = 150;
static constexpr int kRowsLandscape = 80;

// ── Helpers ───────────────────────────────────────────────────────────────────
static inline uint8_t clamp_u8(int v) {
    return (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
}

// ── feedFrame: store Y + UV planes ───────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_dev_palazik_palazikascii_MainActivity_feedFrame(
        JNIEnv* env, jobject,
        jbyteArray yBytes, jbyteArray uvBytes,
        jint width, jint height, jint rotation) {

    jsize yLen  = env->GetArrayLength(yBytes);
    jsize uvLen = env->GetArrayLength(uvBytes);

    std::lock_guard<std::mutex> lock(g_mutex);
    g_yPlane.resize(yLen);
    g_uvPlane.resize(uvLen);
    env->GetByteArrayRegion(yBytes,  0, yLen,  reinterpret_cast<jbyte*>(g_yPlane.data()));
    env->GetByteArrayRegion(uvBytes, 0, uvLen, reinterpret_cast<jbyte*>(g_uvPlane.data()));
    g_width    = width;
    g_height   = height;
    g_rotation = rotation;
}

// ── getLatestColorFrame: returns int[] packed as 0xCCRRGGBB ──────────────────
//   CC = ASCII ramp index (0-9), RR/GG/BB = colour
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_palazik_palazikascii_MainActivity_getLatestColorFrame(
        JNIEnv* env, jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_yPlane.empty()) {
        jintArray sentinel = env->NewIntArray(1);
        return sentinel;
    }

    bool isPortrait = (g_rotation == 90 || g_rotation == 270);
    int frameW = isPortrait ? g_height : g_width;
    int frameH = isPortrait ? g_width  : g_height;

    int cols = isPortrait ? kColsPortrait  : kColsLandscape;
    int rows = isPortrait ? kRowsPortrait  : kRowsLandscape;

    int cellW = frameW / cols;
    int cellH = frameH / rows;

    if (cellW == 0 || cellH == 0) {
        jintArray sentinel = env->NewIntArray(1);
        return sentinel;
    }

    int total = cols * rows;
    jintArray result = env->NewIntArray(total);
    if (!result) return nullptr;

    std::vector<jint> buf(total);

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {

            uint32_t sumY = 0, sumU = 0, sumV = 0;
            int count = 0;

            for (int dy = 0; dy < cellH; dy++) {
                for (int dx = 0; dx < cellW; dx++) {
                    int srcX = c * cellW + dx;
                    int srcY = r * cellH + dy;

                    int rawX = srcX, rawY = srcY;
                    if (g_rotation == 90) {
                        rawX = srcY;
                        rawY = g_height - 1 - srcX;
                    } else if (g_rotation == 270) {
                        rawX = g_width - 1 - srcY;
                        rawY = srcX;
                    } else if (g_rotation == 180) {
                        rawX = g_width  - 1 - srcX;
                        rawY = g_height - 1 - srcY;
                    }

                    if (rawX < 0 || rawX >= g_width || rawY < 0 || rawY >= g_height)
                        continue;

                    sumY += g_yPlane[rawY * g_width + rawX];

                    if (!g_uvPlane.empty()) {
                        int uvRow = rawY / 2;
                        int uvCol = (rawX / 2) * 2;
                        int uvIdx = uvRow * g_width + uvCol;
                        if (uvIdx + 1 < (int)g_uvPlane.size()) {
                            sumU += g_uvPlane[uvIdx];
                            sumV += g_uvPlane[uvIdx + 1];
                        }
                    }
                    count++;
                }
            }

            if (count == 0) { buf[r * cols + c] = 0; continue; }

            uint8_t Y = (uint8_t)(sumY / count);
            uint8_t U = (uint8_t)(sumU / count);
            uint8_t V = (uint8_t)(sumV / count);

            // BT.601 YUV → RGB (integer math, no floats)
            int Yf = (int)Y - 16;
            int Uf = (int)U - 128;
            int Vf = (int)V - 128;

            uint8_t R = clamp_u8((298 * Yf + 409 * Vf + 128) >> 8);
            uint8_t G = clamp_u8((298 * Yf - 100 * Uf - 208 * Vf + 128) >> 8);
            uint8_t B = clamp_u8((298 * Yf + 516 * Uf + 128) >> 8);

            int charIdx = (Y * (kRampLen - 1)) / 255;

            buf[r * cols + c] = (jint)(((uint32_t)charIdx << 24)
                                      | ((uint32_t)R       << 16)
                                      | ((uint32_t)G       <<  8)
                                      |  (uint32_t)B);
        }
    }

    env->SetIntArrayRegion(result, 0, total, buf.data());
    return result;
}

// ── Legacy stub (unused) ──────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_dev_palazik_palazikascii_MainActivity_getLatestAsciiFrame(
        JNIEnv* env, jobject) {
    return env->NewStringUTF("[ use getLatestColorFrame ]");
}
