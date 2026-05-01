#include <jni.h>
#include <vector>
#include <mutex>

// ── Config ────────────────────────────────────────────────────────────────────
static constexpr int   kMaxCols     = 20;       // larger symbols
static constexpr float kGlyphAspect = 0.60f;    // monospace width/height ratio
static constexpr char  kRamp[]      = " .:-=+*#%@";
static constexpr int   kRampLen     = sizeof(kRamp) - 1;

// ── State ─────────────────────────────────────────────────────────────────────
static std::mutex           g_mutex;
static std::vector<uint8_t> g_yPlane;
static std::vector<uint8_t> g_uvPlane;
static int   g_camW     = 0;
static int   g_camH     = 0;
static int   g_rotation = 0;
static bool  g_isFront  = false;
static int   g_screenW  = 1080;
static int   g_screenH  = 2400;

// Pre-computed result pushed by feedFrame, read by getLatestColorFrame
// Layout: [cols, rows, pixel0, pixel1, ...]  packed as 0xCC_RR_GG_BB
static std::vector<int32_t> g_result;

// ── Helpers ───────────────────────────────────────────────────────────────────
static inline uint8_t clamp_u8(int v) {
    return (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
}

static void computeGrid(int& outCols, int& outRows) {
    float sw = (float)g_screenW, sh = (float)g_screenH;
    if (sw > sh) { float t = sw; sw = sh; sh = t; }  // normalise to portrait
    outCols = kMaxCols;
    outRows = (int)(outCols * (sh / sw) / kGlyphAspect);
    if (outRows < 1) outRows = 1;
}

// ── processFrame — runs on analysis thread inside feedFrame ───────────────────
static void processFrame() {
    int cols, rows;
    computeGrid(cols, rows);

    bool isPortrait = (g_rotation == 90 || g_rotation == 270);
    int frameW = isPortrait ? g_camH : g_camW;
    int frameH = isPortrait ? g_camW : g_camH;

    // ── Stretch fix: crop frame to match screen aspect ratio ─────────────────
    float screenAR = (float)g_screenW / (float)g_screenH;
    if (g_screenW < g_screenH) screenAR = (float)g_screenH / (float)g_screenW; // portrait
    float frameAR  = (float)frameW / (float)frameH;

    int cropW = frameW, cropH = frameH;
    if (frameAR > screenAR) {
        cropW = (int)(frameH * screenAR);
    } else {
        cropH = (int)(frameW / screenAR);
    }

    int offsetX = (frameW - cropW) / 2;
    int offsetY = (frameH - cropH) / 2;

    int cellW = cropW / cols;  if (cellW < 1) cellW = 1;
    int cellH = cropH / rows;  if (cellH < 1) cellH = 1;

    int total = cols * rows;
    g_result.resize(total + 2);
    g_result[0] = cols;
    g_result[1] = rows;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            int dc = g_isFront ? (cols - 1 - c) : c;
            uint32_t sumY = 0, sumU = 0, sumV = 0;
            int count = 0;

            // Sample every other pixel — 4× faster, imperceptible quality loss
            for (int dy = 0; dy < cellH; dy += 2) {
                for (int dx = 0; dx < cellW; dx += 2) {
                    // Cropped frame coords
                    int sx = offsetX + dc * cellW + dx;
                    int sy = offsetY + r  * cellH + dy;

                    // Rotate back to raw sensor coords
                    int rx = sx, ry = sy;
                    switch (g_rotation) {
                        case  90: rx = sy;              ry = g_camH - 1 - sx; break;
                        case 270: rx = g_camW - 1 - sy; ry = sx;              break;
                        case 180: rx = g_camW - 1 - sx; ry = g_camH - 1 - sy; break;
                        default: break;
                    }

                    if ((unsigned)rx >= (unsigned)g_camW ||
                        (unsigned)ry >= (unsigned)g_camH) continue;

                    sumY += g_yPlane[ry * g_camW + rx];

                    int uvIdx = (ry / 2) * g_camW + (rx & ~1);
                    if (uvIdx + 1 < (int)g_uvPlane.size()) {
                        sumU += g_uvPlane[uvIdx];
                        sumV += g_uvPlane[uvIdx + 1];
                    }
                    count++;
                }
            }

            if (count == 0) { g_result[2 + r * cols + c] = 0; continue; }

            uint8_t Y = (uint8_t)(sumY / count);
            uint8_t U = (uint8_t)(sumU / count);
            uint8_t V = (uint8_t)(sumV / count);

            int Yf = (int)Y - 16, Uf = (int)U - 128, Vf = (int)V - 128;

            uint8_t R = clamp_u8((298*Yf + 409*Vf          + 128) >> 8);
            uint8_t G = clamp_u8((298*Yf - 100*Uf - 208*Vf + 128) >> 8);
            uint8_t B = clamp_u8((298*Yf + 516*Uf           + 128) >> 8);

            int charIdx = (Y * (kRampLen - 1)) / 255;

            g_result[2 + r * cols + c] =
                (int32_t)(((uint32_t)charIdx << 24) |
                          ((uint32_t)R       << 16) |
                          ((uint32_t)G       <<  8) |
                           (uint32_t)B);
        }
    }
}

// ── JNI ──────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_dev_palazik_palazikascii_MainActivity_setScreenSize(
        JNIEnv*, jobject, jint w, jint h) {
    std::lock_guard<std::mutex> lk(g_mutex);
    g_screenW = w; g_screenH = h;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_palazik_palazikascii_MainActivity_feedFrame(
        JNIEnv* env, jobject,
        jbyteArray yBytes, jbyteArray uvBytes,
        jint width, jint height, jint rotation, jboolean isFront) {

    jsize yLen  = env->GetArrayLength(yBytes);
    jsize uvLen = env->GetArrayLength(uvBytes);

    std::lock_guard<std::mutex> lk(g_mutex);
    g_yPlane.resize(yLen);
    g_uvPlane.resize(uvLen);
    env->GetByteArrayRegion(yBytes,  0, yLen,  reinterpret_cast<jbyte*>(g_yPlane.data()));
    env->GetByteArrayRegion(uvBytes, 0, uvLen, reinterpret_cast<jbyte*>(g_uvPlane.data()));

    g_camW     = width;
    g_camH     = height;
    g_rotation = rotation;
    g_isFront  = (bool)isFront;

    processFrame();   // process immediately on analysis thread
}

// Just copies pre-computed result — fast, no processing
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_palazik_palazikascii_MainActivity_getLatestColorFrame(
        JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_result.size() < 2) return env->NewIntArray(2);
    jintArray out = env->NewIntArray((jsize)g_result.size());
    if (!out) return nullptr;
    env->SetIntArrayRegion(out, 0, (jsize)g_result.size(),
                           reinterpret_cast<const jint*>(g_result.data()));
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_palazik_palazikascii_MainActivity_getLatestAsciiFrame(
        JNIEnv* env, jobject) {
    return env->NewStringUTF("");
}
