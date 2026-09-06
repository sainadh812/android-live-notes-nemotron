#include <jni.h>
// JNI gates control the duration/failure of calls without loading a real model.
#define PREFIX Java_com_sainadh_livenotes_stt_NemotronTranscriber_
JNIEXPORT jlong JNICALL Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeInit(
        JNIEnv *env, jobject self, jstring path, jstring lang, jint context) {
    jclass gate = (*env)->FindClass(env, "check/Gate");
    jmethodID method = (*env)->GetStaticMethodID(env, gate, "init", "()J");
    return (*env)->CallStaticLongMethod(env, gate, method);
}
JNIEXPORT jstring JNICALL Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFeedPcm(
        JNIEnv *env, jobject self, jlong handle, jfloatArray pcm) {
    jclass gate = (*env)->FindClass(env, "check/Gate");
    jmethodID method = (*env)->GetStaticMethodID(env, gate, "feed", "([F)Ljava/lang/String;");
    return (*env)->CallStaticObjectMethod(env, gate, method, pcm);
}
JNIEXPORT jstring JNICALL Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeFinalizeStream(
        JNIEnv *env, jobject self, jlong handle) {
    jclass gate = (*env)->FindClass(env, "check/Gate");
    jmethodID method = (*env)->GetStaticMethodID(env, gate, "finish", "()Ljava/lang/String;");
    return (*env)->CallStaticObjectMethod(env, gate, method);
}
JNIEXPORT jboolean JNICALL Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeRestartStream(
        JNIEnv *env, jobject self, jlong handle, jstring lang, jint context) {
    jclass gate = (*env)->FindClass(env, "check/Gate");
    jmethodID method = (*env)->GetStaticMethodID(env, gate, "restart", "()Z");
    return (*env)->CallStaticBooleanMethod(env, gate, method);
}
JNIEXPORT void JNICALL Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeDestroy(
        JNIEnv *env, jobject self, jlong handle) {
    jclass gate = (*env)->FindClass(env, "check/Gate");
    jmethodID method = (*env)->GetStaticMethodID(env, gate, "destroy", "()V");
    (*env)->CallStaticVoidMethod(env, gate, method);
}

JNIEXPORT jboolean JNICALL Java_com_sainadh_livenotes_stt_NemotronTranscriber_nativeWasTruncated(
        JNIEnv *env, jobject self, jlong handle) {
    jclass gate = (*env)->FindClass(env, "check/Gate");
    jfieldID field = (*env)->GetStaticFieldID(env, gate, "truncated", "Z");
    return (*env)->GetStaticBooleanField(env, gate, field);
}
