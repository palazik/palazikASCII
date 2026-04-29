#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "palazikASCII"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/**
 * Stub implementation of getLatestAsciiFrame.
 *
 * Replace the body of this function with your real ASCII
 * conversion logic once the camera pipeline is wired in.
 *
 * The function receives the latest camera frame (e.g. via a shared
 * pixel buffer written from the CameraX ImageAnalysis use-case) and
 * should return a newline-delimited ASCII string ready for rendering.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_dev_palazik_palazikascii_MainActivity_getLatestAsciiFrame(
        JNIEnv* env,
        jobject /* this */) {

    // TODO: replace with real frame → ASCII conversion
    static const char* placeholder =
        "##################################################\n"
        "#                                                #\n"
        "#          palazikASCII  [ STUB MODE ]           #\n"
        "#                                                #\n"
        "#   replace getLatestAsciiFrame() in native-lib  #\n"
        "#                                                #\n"
        "##################################################\n";

    LOGI("getLatestAsciiFrame called (stub)");
    return env->NewStringUTF(placeholder);
}
