// Azux engine - stage 1: own JVM launcher (JNI_CreateJavaVM), written from scratch.
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>

// Android's jni.h stops at 1_6; HotSpot understands 1.8 (0x00010008).
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

#define TAG "azux"
#define LOG(...) do { fprintf(stderr, "[azux] " __VA_ARGS__); fputc('\n', stderr); \
                      __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); } while (0)

typedef jint (*CreateVM_t)(JavaVM **, void **, void *);

typedef struct {
    char  *home;
    char  *mainClass;
    char **jvmArgs;  int nJvm;
    char **progArgs; int nProg;
    int    rc;
} Cfg;

static char **to_c_array(JNIEnv *env, jobjectArray arr, int *n) {
    *n = arr ? (int)(*env)->GetArrayLength(env, arr) : 0;
    char **out = calloc((size_t)*n + 1, sizeof(char *));
    for (int i = 0; i < *n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(u);
        (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void *vm_thread(void *p) {
    Cfg *c = (Cfg *)p;
    char path[4096];
    snprintf(path, sizeof path, "%s/lib/server/libjvm.so", c->home);
    LOG("dlopen %s", path);
    void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!h) { LOG("dlopen libjvm failed: %s", dlerror()); c->rc = -2; return NULL; }
    CreateVM_t create = (CreateVM_t)dlsym(h, "JNI_CreateJavaVM");
    if (!create) { LOG("JNI_CreateJavaVM not found: %s", dlerror()); c->rc = -3; return NULL; }

    JavaVMOption *opts = calloc((size_t)c->nJvm + 1, sizeof(JavaVMOption));
    for (int i = 0; i < c->nJvm; i++) opts[i].optionString = c->jvmArgs[i];
    JavaVMInitArgs args;
    memset(&args, 0, sizeof args);
    args.version = JNI_VERSION_1_8;
    args.nOptions = c->nJvm;
    args.options = opts;
    args.ignoreUnrecognized = JNI_TRUE;

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    LOG("JNI_CreateJavaVM ...");
    jint r = create(&vm, (void **)&env, &args);
    if (r != JNI_OK) { LOG("JNI_CreateJavaVM failed: %d", (int)r); c->rc = -4; return NULL; }
    LOG("JVM created");

    char cls[512];
    strncpy(cls, c->mainClass, sizeof cls - 1);
    cls[sizeof cls - 1] = 0;
    for (char *q = cls; *q; q++) if (*q == '.') *q = '/';

    jclass mc = (*env)->FindClass(env, cls);
    if (!mc) { LOG("main class not found: %s", cls); (*env)->ExceptionDescribe(env); c->rc = -5; goto done; }
    jmethodID mm = (*env)->GetStaticMethodID(env, mc, "main", "([Ljava/lang/String;)V");
    if (!mm) { LOG("main(String[]) not found"); (*env)->ExceptionDescribe(env); c->rc = -6; goto done; }
    jclass sc = (*env)->FindClass(env, "java/lang/String");
    jobjectArray ja = (*env)->NewObjectArray(env, c->nProg, sc, NULL);
    for (int i = 0; i < c->nProg; i++) {
        jstring s = (*env)->NewStringUTF(env, c->progArgs[i]);
        (*env)->SetObjectArrayElement(env, ja, i, s);
    }
    (*env)->CallStaticVoidMethod(env, mc, mm, ja);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); c->rc = 1; }
    else c->rc = 0;
done:
    (*vm)->DestroyJavaVM(vm);   // waits for non-daemon threads
    LOG("JVM finished rc=%d", c->rc);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_azux_engine_Native_launch(JNIEnv *env, jclass cl, jstring javaHome, jstring logPath,
                                   jobjectArray jvmArgs, jstring mainClass, jobjectArray progArgs) {
    const char *lp = (*env)->GetStringUTFChars(env, logPath, NULL);
    int fd = open(lp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    (*env)->ReleaseStringUTFChars(env, logPath, lp);
    if (fd >= 0) { dup2(fd, 1); dup2(fd, 2); }
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    Cfg c;
    memset(&c, 0, sizeof c);
    const char *hm = (*env)->GetStringUTFChars(env, javaHome, NULL);
    c.home = strdup(hm);
    (*env)->ReleaseStringUTFChars(env, javaHome, hm);
    const char *mcs = (*env)->GetStringUTFChars(env, mainClass, NULL);
    c.mainClass = strdup(mcs);
    (*env)->ReleaseStringUTFChars(env, mainClass, mcs);
    c.jvmArgs = to_c_array(env, jvmArgs, &c.nJvm);
    c.progArgs = to_c_array(env, progArgs, &c.nProg);
    c.rc = -1;

    pthread_attr_t at;
    pthread_attr_init(&at);
    pthread_attr_setstacksize(&at, 8u * 1024 * 1024);
    pthread_t th;
    if (pthread_create(&th, &at, vm_thread, &c) != 0) { LOG("pthread_create failed"); return -7; }
    pthread_join(th, NULL);
    return c.rc;
}
