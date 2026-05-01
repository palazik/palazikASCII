#include <jni.h>
#include <vector>
#include <mutex>
#include <android/log.h>

#define LOG_TAG "palazikASCII"

// ── Frame buffers ─────────────────────────────────────────────────────────────
static std::vector<uint8_t> g_yPlane;
static std::vector<uint8_t> g_uvPlane;
static int g_width      = 0;
static int g_height     = 0;
static int g_rotation   = 0;
static bool g_isFront   = false;

// Screen size — set from Kotlin so we can compute correct grid
static int g_screenW    = 1080;
static int g_screenH    = 2400;

static std::mutex g_mutex;

// ── ASCII ramp (dark → bright) ────────────────────────────────────────────────
static constexpr char kRamp[]  = " .:-=+*#%@";
static constexpr int  kRampLen = sizeof(kRamp) - 1;

// Monospace font glyph aspect ratio (width / height).
// Typical monospace on Android ≈ 0.55–0.60. We use 0.55 as a safe default.
static constexpr float kGlyphAspect = 0.55f;

// Max columns — controls detail level. Rows are derived from screen AR + glyph AR.
static constexpr int kMaxCols = 80;

static inline uint8_t clamp_u8(int v) {
    return (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
}

// ── Compute grid so glyphs appear square on screen ───────────────────────────
// cellW_screen = screenW / cols
// cellH_screen = screenH / rows
// For square appearance: cellW_screen / cellH_screen == kGlyphAspect
// → rows = cols * (screenH / screenW) / kGlyphAspect
static void computeGrid(int& outCols, int& outRows) {
    // Use actual screen orientation
    int sw = g_screenW, sh = g_screenH;
    if (sw > sh) { // landscape
        sw = g_screenH; sh = g_screenW; // normalize to portrait math, swap after
        outCols = kMaxCols;
        outRows = (int)(outCols * ((float)sw / sh) / kGlyphAspect);
    } else {
        outCols = kMaxCols;
        outRows = (int)(outCols * ((float)sh / sw) / kGlyphAspect);
    }
    if (outRows < 1) outRows = 1;
}

// ── setScreenSize: call once from Kotlin with actual display px ───────────────
extern "C" JNIEXPORT void JNICALL
Java_dev_palazik_palazikascii_MainActivity_setScreenSize(
        JNIEnv*, jobject, jint screenW, jint screenH) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_screenW = screenW;
    g_screenH = screenH;
}

// ── feedFrame ─────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_dev_palazik_palazikascii_MainActivity_feedFrame(
        JNIEnv* env, jobject,
        jbyteArray yBytes, jbyteArray uvBytes,
        jint width, jint height, jint rotation, jboolean isFront) {

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
    g_isFront  = (bool)isFront;
}

// ── getGridSize: lets Kotlin know current cols/rows ───────────────────────────
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_palazik_palazikascii_MainActivity_getGridSize(
        JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    int cols, rows;
    computeGrid(cols, rows);
    jintArray r = env->NewIntArray(2);
    jint data[2] = { cols, rows };
    env->SetIntArrayRegion(r, 0, 2, data);
    return r;
}

// ── getLatestColorFrame ───────────────────────────────────────────────────────
// Returns int[] packed as 0xCC_RR_GG_BB (CC = ramp index, RGB = colour).
// First two ints are [cols, rows] as a header so Kotlin always knows the grid.
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_palazik_palazikascii_MainActivity_getLatestColorFrame(
        JNIEnv* env, jobject) {

    std::lock_guard<std::mutex> lock(g_mutex);

    int cols, rows;
    computeGrid(cols, rows);

    if (g_yPlane.empty() || g_width == 0 || g_height == 0) {
        // Return header-only sentinel
        jintArray s = env->NewIntArray(2);
        jint h[2] = { cols, rows };
        env->SetIntArrayRegion(s, 0, 2, h);
        return s;
    }

    bool isPortrait = (g_rotation == 90 || g_rotation == 270);
    int frameW = isPortrait ? g_height : g_width;
    int frameH = isPortrait ? g_width  : g_height;

    int cellW = frameW / cols;
    int cellH = frameH / rows;
    if (cellW < 1) cellW = 1;
    if (cellH < 1) cellH = 1;

    int total = cols * rows;
    // +2 for header
    std::vector<jint> buf(total + 2);
    buf[0] = cols;
    buf[1] = rows;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {

            // Mirror column for front camera
            int drawCol = (g_isFront) ? (cols - 1 - c) : c;

            uint32_t sumY = 0, sumU = 0, sumV = 0;
            int count = 0;

            for (int dy = 0; dy < cellH; dy++) {
                for (int dx = 0; dx < cellW; dx++) {
                    int srcX = drawCol * cellW + dx;
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

            if (count == 0) { buf[2 + r * cols + c] = 0; continue; }

            uint8_t Y = (uint8_t)(sumY / count);
            uint8_t U = (uint8_t)(sumU / count);
            uint8_t V = (uint8_t)(sumV / count);

            int Yf = (int)Y - 16;
            int Uf = (int)U - 128;
            int Vf = (int)V - 128;

            uint8_t R = clamp_u8((298 * Yf + 409 * Vf + 128) >> 8);
            uint8_t G = clamp_u8((298 * Yf - 100 * Uf - 208 * Vf + 128) >> 8);
            uint8_t B = clamp_u8((298 * Yf + 516 * Uf + 128) >> 8);

            int charIdx = (Y * (kRampLen - 1)) / 255;

            buf[2 + r * cols + c] = (jint)(((uint32_t)charIdx << 24)
                                          | ((uint32_t)R       << 16)
                                          | ((uint32_t)G       <<  8)
                                          |  (uint32_t)B);
        }
    }

    jintArray result = env->NewIntArray((jsize)buf.size());
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, (jsize)buf.size(), buf.data());
    return result;
}

// ── Legacy stub ───────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_dev_palazik_palazikascii_MainActivity_getLatestAsciiFrame(
        JNIEnv* env, jobject) {
    return env->NewStringUTF("[ use getLatestColorFrame ]");
}
