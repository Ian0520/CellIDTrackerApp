#include <jni.h>
#include <string>
#include "third_party/core/include/core_api.h"
#include <android/log.h>
#include "third_party/wificalling/adapter/core_adapter.h"

#define LOG_TAG "NativeDemo"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {
JNIEXPORT jstring JNICALL
Java_com_example_cellidtracker_NativeBridge_process(JNIEnv* env, jobject /* this */, jstring jin) {
    const char* c = env->GetStringUTFChars(jin, nullptr);
    std::string input = c ? c : "";
    env->ReleaseStringUTFChars(jin, c);

    // call into your core
    std::string out = core::process(input);
    LOGI("core::process -> %s", out.c_str());

    return env->NewStringUTF(out.c_str());
}


JNIEXPORT jstring JNICALL
Java_com_example_cellidtracker_NativeBridge_runReport(JNIEnv* env, jobject, jstring jnum) {
    const char* c = env->GetStringUTFChars(jnum, nullptr);
    std::string num = c ? c : "";
    env->ReleaseStringUTFChars(jnum, c);
    auto out = core::runReport(num);
    return env->NewStringUTF(out.c_str());
}

}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_cellidtracker_NativeBridge_runReportWithWifi(
        JNIEnv* env, jobject, jstring jvictim, jboolean jEnabled, jstring jSsid) {
    const char* cv = env->GetStringUTFChars(jvictim, nullptr);
    const char* cs = env->GetStringUTFChars(jSsid, nullptr);
    std::string victim = cv ? cv : "";
    std::string ssid   = cs ? cs : "";
    env->ReleaseStringUTFChars(jvictim, cv);
    env->ReleaseStringUTFChars(jSsid, cs);

    auto out = core::runReportWithWifi(victim, jEnabled == JNI_TRUE, ssid);
    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_cellidtracker_NativeBridge_runReportWithWifiAndCarrier(
        JNIEnv* env, jobject, jstring jvictim, jboolean jEnabled, jstring jSsid, jstring jCarrier) {

    const char* cv = env->GetStringUTFChars(jvictim, nullptr);
    const char* cs = env->GetStringUTFChars(jSsid,   nullptr);
    const char* cc = env->GetStringUTFChars(jCarrier,nullptr);
    std::string victim  = cv ? cv : "";
    std::string ssid    = cs ? cs : "";
    std::string carrier = cc ? cc : "";
    if (cv) env->ReleaseStringUTFChars(jvictim, cv);
    if (cs) env->ReleaseStringUTFChars(jSsid,   cs);
    if (cc) env->ReleaseStringUTFChars(jCarrier,cc);

    auto out = core::runReportWithWifiAndCarrier(victim, jEnabled == JNI_TRUE, ssid, carrier);
    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_cellidtracker_NativeBridge_runReportWithConfig(
        JNIEnv* env, jobject,
        jstring jvictim, jboolean jEnabled, jstring jSsid,
        jstring jCarrier, jstring jConfigJson) {

    const char* cv = env->GetStringUTFChars(jvictim,   nullptr);
    const char* cs = env->GetStringUTFChars(jSsid,     nullptr);
    const char* cc = env->GetStringUTFChars(jCarrier,  nullptr);
    const char* cj = env->GetStringUTFChars(jConfigJson,nullptr);

    std::string victim  = cv ? cv : "";
    std::string ssid    = cs ? cs : "";
    std::string carrier = cc ? cc : "";
    std::string config  = cj ? cj : "";

    if (cv) env->ReleaseStringUTFChars(jvictim,   cv);
    if (cs) env->ReleaseStringUTFChars(jSsid,     cs);
    if (cc) env->ReleaseStringUTFChars(jCarrier,  cc);
    if (cj) env->ReleaseStringUTFChars(jConfigJson,cj);

    auto out = core::runReportWithConfig(victim, jEnabled == JNI_TRUE, ssid, carrier, config);
    return env->NewStringUTF(out.c_str());
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_cellidtracker_NativeBridge_runOriginalFlow(
    JNIEnv* env, jobject,
    jstring jVictim, jstring jCarrier,
    jboolean jRemote, jboolean jLocal, jboolean jRl,
    jboolean jUnavail, jboolean jDetect,
    jint jVerbose, jboolean jAllowSysOps) {

  auto toStr = [&](jstring s)->std::string{
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
  };

  core_adapter::Params p;
  p.victim  = toStr(jVictim);
  p.carrier = toStr(jCarrier);
  p.remoteCellIDProber = (jRemote  == JNI_TRUE);
  p.localCellIDProber  = (jLocal   == JNI_TRUE);
  p.rlRemoteProber     = (jRl      == JNI_TRUE);
  p.unavailabilityEval = (jUnavail == JNI_TRUE);
  p.detectEval         = (jDetect  == JNI_TRUE);
  p.verbose            = (int)jVerbose;
  p.allowSystemOps     = (jAllowSysOps == JNI_TRUE);

  auto log = core_adapter::run_original_flow(p);
  return env->NewStringUTF(log.c_str());
}