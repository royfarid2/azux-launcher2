/*
 * Azux engine - native side.
 *
 * Starts a HotSpot JVM inside the app process through the JNI invocation API:
 *   - redirects stdout/stderr to a log file the launcher shows on screen
 *   - preloads the runtime's helper libraries, then dlopen()s libjvm.so
 *   - creates the VM with the options the launcher built, runs the main class
 * Written from scratch for Azux.
 */
#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>

#include "azux_internal.h"

#define TAG "azux-engine"

extern const char *azux_gl_path;   /* defined in glfw_shim.c */

typedef jint (JNICALL *CreateJavaVM_t)(JavaVM **, void **, void *);
typedef void (*UpdateLdPath_t)(const char *);

void azux_log(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    fprintf(stderr, "[azux] %s\n", buf);
    fflush(stderr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", buf);
}

#define say azux_log

static int starts_with(const char *s, const char *p) {
    return strncmp(s, p, strlen(p)) == 0;
}

/* Loads helper libraries listed in <home>/lib/azux-preload.txt. Retries a few passes
 * because the list is not sorted by dependency order. */
static void preload_libs(const char *home) {
    char list[PATH_MAX];
    snprintf(list, sizeof(list), "%s/lib/azux-preload.txt", home);
    FILE *f = fopen(list, "r");
    if (!f) return;
    char names[256][128];
    int done[256] = {0};
    int n = 0;
    char line[128];
    while (n < 256 && fgets(line, sizeof(line), f)) {
        size_t l = strlen(line);
        while (l > 0 && (line[l - 1] == '\n' || line[l - 1] == '\r')) line[--l] = 0;
        if (l == 0) continue;
        strncpy(names[n], line, sizeof(names[n]) - 1);
        names[n][sizeof(names[n]) - 1] = 0;
        n++;
    }
    fclose(f);
    for (int pass = 0; pass < 4; pass++) {
        int progress = 0;
        for (int i = 0; i < n; i++) {
            if (done[i]) continue;
            char path[PATH_MAX];
            snprintf(path, sizeof(path), "%s/lib/%s", home, names[i]);
            void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
            if (h) { done[i] = 1; progress = 1; }
        }
        if (!progress) break;
    }
    for (int i = 0; i < n; i++) {
        if (!done[i]) say("preload failed: %s (%s)", names[i], dlerror());
    }
}


/* Same fixed order Zalith/Pojav use in JREUtils.initJavaRuntime(): libnio needs libnet already loaded, etc.
 * Each name is looked up in the runtime's lib directories (Java 9+: lib/, Java 8: lib/aarch64/). */
static void preload_named(const char *home, const char *const *names) {
    static const char *const dirs[] = { "lib", "lib/server", "lib/jli", "lib/aarch64", "lib/aarch64/server", "lib/aarch64/jli", NULL };
    for (int i = 0; names[i]; i++) {
        int ok = 0;
        for (int d = 0; dirs[d] && !ok; d++) {
            char path[PATH_MAX];
            snprintf(path, sizeof(path), "%s/%s/%s", home, dirs[d], names[i]);
            if (access(path, F_OK) != 0) continue;
            if (dlopen(path, RTLD_GLOBAL | RTLD_LAZY)) ok = 1;
            else say("preload %s failed: %s", names[i], dlerror());
        }
    }
}

static void update_ld_path(const char *ld) {
    void *libdl = dlopen("libdl.so", RTLD_LAZY);
    UpdateLdPath_t upd = libdl ? (UpdateLdPath_t) dlsym(libdl, "android_update_LD_LIBRARY_PATH") : NULL;
    if (!upd && libdl) upd = (UpdateLdPath_t) dlsym(libdl, "__loader_android_update_LD_LIBRARY_PATH");
    if (!upd) upd = (UpdateLdPath_t) dlsym(RTLD_DEFAULT, "android_update_LD_LIBRARY_PATH");
    if (upd) upd(ld); else say("android_update_LD_LIBRARY_PATH not found: %s", dlerror());
}

static int azux_run(const char *home, const char *cwd, const char *logp, char **args, int nargs) {

    int fd = open(logp, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (fd >= 0) {
        dup2(fd, 1);
        dup2(fd, 2);
        close(fd);
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    say("java home: %s", home);
    say("engine state id: %p", azux_state_id());
    say("work dir : %s", cwd);
    if (chdir(cwd) != 0) say("chdir failed");

    char tmp[PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s/tmp", cwd);
    mkdir(tmp, 0700);
    setenv("HOME", cwd, 1);
    setenv("TMPDIR", tmp, 1);
    setenv("JAVA_HOME", home, 1);
    setenv("LIBGL_ES", "2", 0);
    setenv("LIBGL_MIPMAP", "3", 0);
    setenv("LIBGL_NORMALIZE", "1", 0);
    setenv("LIBGL_NOERROR", "1", 0);
    setenv("LIBGL_NOINTOVLHACK", "1", 0);

    /* Java 9+ keeps its libraries in lib/, Java 8 in lib/aarch64/ */
    char ld[PATH_MAX * 6];
    snprintf(ld, sizeof(ld), "%s/lib:%s/lib/server:%s/lib/jli:%s/lib/aarch64:%s/lib/aarch64/server:%s/lib/aarch64/jli",
             home, home, home, home, home, home);
    setenv("LD_LIBRARY_PATH", ld, 1);
    update_ld_path(ld);

    preload_libs(home);
    { static const char *const first[] = { "libjli.so", NULL }; preload_named(home, first); }

    /* Some runtimes (Java 25) are linked against the NDK's C++ runtime, which the app ships next to this library. */
    for (int i = 1; i < nargs; i++) {
        if (starts_with(args[i], "-Dazux.nativedir=")) {
            char cxx[PATH_MAX];
            snprintf(cxx, sizeof(cxx), "%s/libc++_shared.so", args[i] + 17);
            if (!dlopen(cxx, RTLD_NOW | RTLD_GLOBAL)) say("libc++_shared.so not preloaded: %s", dlerror());
            break;
        }
    }

    char jvmpath[PATH_MAX];
    snprintf(jvmpath, sizeof(jvmpath), "%s/lib/server/libjvm.so", home);
    if (access(jvmpath, F_OK) != 0) snprintf(jvmpath, sizeof(jvmpath), "%s/lib/aarch64/server/libjvm.so", home);
    say("libjvm   : %s", jvmpath);
    void *h = dlopen(jvmpath, RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        say("dlopen(libjvm.so) failed: %s", dlerror());
        return -10;
    }
    {
        static const char *const rest[] = { "libverify.so", "libjava.so", "libnet.so", "libnio.so", "libawt.so",
                                            "libawt_headless.so", "libfreetype.so", "libfontmanager.so", NULL };
        preload_named(home, rest);
    }
    CreateJavaVM_t create = (CreateJavaVM_t) dlsym(h, "JNI_CreateJavaVM");
    if (!create) {
        say("JNI_CreateJavaVM not found: %s", dlerror());
        return -11;
    }

    /* split args: [0]=java path (ignored), JVM options, main class, program args */
    JavaVMOption *opts = (JavaVMOption *) calloc((size_t) nargs + 4, sizeof(JavaVMOption));
    int no = 0, mainIdx = -1;
    for (int i = 1; i < nargs; i++) {
        const char *a = args[i];
        if (!strcmp(a, "-cp") || !strcmp(a, "-classpath") || !strcmp(a, "--class-path")) {
            if (i + 1 < nargs) {
                char *o = (char *) malloc(strlen(args[i + 1]) + 32);
                sprintf(o, "-Djava.class.path=%s", args[i + 1]);
                opts[no++].optionString = o;
                i++;
            }
        } else if (a[0] == '-') {
            if (starts_with(a, "-Dazux.gl4es=")) azux_gl_path = strdup(a + 13);
            if (starts_with(a, "-Dazux.env.")) {   /* -Dazux.env.NAME=VALUE -> environment variable */
                char *kv = strdup(a + 11), *eq = strchr(kv, '=');
                if (eq) { *eq = 0; setenv(kv, eq + 1, 1); }
                free(kv);
            }
            if (starts_with(a, "--") && !strchr(a, '=') && i + 1 < nargs && args[i + 1][0] != '-') {
                char *o = (char *) malloc(strlen(a) + strlen(args[i + 1]) + 2);
                sprintf(o, "%s=%s", a, args[i + 1]);
                opts[no++].optionString = o;
                i++;
            } else {
                opts[no++].optionString = args[i];
            }
        } else {
            mainIdx = i;
            break;
        }
    }
    if (mainIdx < 0) {
        say("no main class given");
        return -12;
    }

    JavaVMInitArgs vmargs;
    memset(&vmargs, 0, sizeof(vmargs));
    vmargs.version = 0x00010008;   /* JNI_VERSION_1_8: Android's jni.h does not define it */
    vmargs.nOptions = no;
    vmargs.options = opts;
    vmargs.ignoreUnrecognized = JNI_TRUE;

    say("starting JVM with %d options, main class %s", no, args[mainIdx]);
    JavaVM *vm = NULL;
    JNIEnv *jenv = NULL;
    jint rc = create(&vm, (void **) &jenv, &vmargs);
    if (rc != JNI_OK) {
        say("JNI_CreateJavaVM failed: %d", (int) rc);
        return -13;
    }

    int ret = 0;
    char *mc = strdup(args[mainIdx]);
    for (char *p = mc; *p; p++) if (*p == '.') *p = '/';
    jclass main = (*jenv)->FindClass(jenv, mc);
    if (!main) {
        say("main class not found: %s", mc);
        (*jenv)->ExceptionDescribe(jenv);
        ret = -14;
    } else {
        jmethodID mid = (*jenv)->GetStaticMethodID(jenv, main, "main", "([Ljava/lang/String;)V");
        if (!mid) {
            say("main(String[]) not found");
            (*jenv)->ExceptionDescribe(jenv);
            ret = -15;
        } else {
            int nm = nargs - mainIdx - 1;
            jclass strc = (*jenv)->FindClass(jenv, "java/lang/String");
            jobjectArray arr = (*jenv)->NewObjectArray(jenv, nm, strc, NULL);
            for (int i = 0; i < nm; i++) {
                jstring s = (*jenv)->NewStringUTF(jenv, args[mainIdx + 1 + i]);
                (*jenv)->SetObjectArrayElement(jenv, arr, i, s);
                (*jenv)->DeleteLocalRef(jenv, s);
            }
            (*jenv)->CallStaticVoidMethod(jenv, main, mid, arr);
            if ((*jenv)->ExceptionCheck(jenv)) {
                (*jenv)->ExceptionDescribe(jenv);
                ret = 1;
            }
        }
    }
    (*vm)->DetachCurrentThread(vm);
    (*vm)->DestroyJavaVM(vm);
    say("JVM finished with code %d", ret);
    return ret;
}

#ifndef AZUX_TEST
JNIEXPORT jint JNICALL
Java_com_azux_launcher_engine_Engine_nativeRun(JNIEnv *env, jclass cls, jstring jhome, jobjectArray jargs,
                                               jstring jcwd, jstring jlog) {
    (void) cls;
    const char *home = (*env)->GetStringUTFChars(env, jhome, NULL);
    const char *cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
    const char *logp = (*env)->GetStringUTFChars(env, jlog, NULL);
    int nargs = (*env)->GetArrayLength(env, jargs);
    char **args = (char **) calloc((size_t) nargs + 1, sizeof(char *));
    for (int i = 0; i < nargs; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jargs, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        args[i] = strdup(u);
        (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
    }
    return azux_run(home, cwd, logp, args, nargs);
}
#else
/* host test: azux_test <java home> <cwd> <log> <java> [jvm opts...] <main class> [args...] */
int main(int argc, char **argv) {
    if (argc < 6) return 2;
    return azux_run(argv[1], argv[2], argv[3], argv + 4, argc - 4);
}
#endif
