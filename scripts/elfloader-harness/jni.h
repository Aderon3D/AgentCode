/* Minimal jni.h stub so elfloader.c compiles natively for the host harness.
 * JNIEnv mirrors the real ABI: a pointer to a function table, so native
 * methods see JNIEnv * and call (*env)->method(...). */
#ifndef JNI_STUB_H
#define JNI_STUB_H
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

typedef int jint;
typedef int jsize;
typedef void *jstring;
typedef void *jobject;
typedef void *jobjectArray;

struct JNINativeInterface;
typedef struct JNINativeInterface *JNIEnv;

struct JNINativeInterface {
    const char *(*GetStringUTFChars)(JNIEnv *, jstring, int *);
    void (*ReleaseStringUTFChars)(JNIEnv *, jstring, const char *);
    jsize (*GetArrayLength)(JNIEnv *, jobjectArray);
    jobject (*GetObjectArrayElement)(JNIEnv *, jobjectArray, jsize);
    void (*DeleteLocalRef)(JNIEnv *, jobject);
};

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
#endif
