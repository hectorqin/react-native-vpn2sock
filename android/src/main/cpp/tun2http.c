#include "tun2http.h"

JavaVM *jvm = NULL;
int pipefds[2];
pthread_t thread_id = 0;
pthread_mutex_t lock;
extern int max_tun_msg;
extern struct ng_session *ng_session;
extern int log_level;

/**
 *
 * Base64 encode start
 *
 */
#include <stddef.h>
/* calculates number of bytes base64-encoded stream of N bytes will take. */
#define BASE64ENC_BYTES(N) (((N + 2) / 3) * 4)
void base64enc(char *dst, const void *src, size_t count);

static const char base64_tbl[64] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/*
   rofl0r's base64 impl (taken from libulz)
   takes count bytes from src, writing base64 encoded string into dst.
   dst needs to be at least BASE64ENC_BYTES(count) + 1 bytes in size.
   the string in dst will be zero-terminated.
   */
void base64enc(char *dst, const void *src, size_t count)
{
    unsigned const char *s = src;
    char *d = dst;
    while (count)
    {
        int i = 0, n = *s << 16;
        s++;
        count--;
        if (count)
        {
            n |= *s << 8;
            s++;
            count--;
            i++;
        }
        if (count)
        {
            n |= *s;
            s++;
            count--;
            i++;
        }
        *d++ = base64_tbl[(n >> 18) & 0x3f];
        *d++ = base64_tbl[(n >> 12) & 0x3f];
        *d++ = i ? base64_tbl[(n >> 6) & 0x3f] : '=';
        *d++ = i == 2 ? base64_tbl[n & 0x3f] : '=';
    }
    *d = 0;
}
/**
 *
 * Base64 encode end
 *
 */


// JNI

jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
  LOGI("JNI load");

  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
  {
    LOGI("JNI load GetEnv failed");
    return -1;
  }

  // Raise file number limit to maximum
  struct rlimit rlim;
  if (getrlimit(RLIMIT_NOFILE, &rlim))
    LOGW("getrlimit error %d: %s", errno, strerror(errno));
  else
  {
    rlim_t soft = rlim.rlim_cur;
    rlim.rlim_cur = rlim.rlim_max;
    if (setrlimit(RLIMIT_NOFILE, &rlim))
      LOGW("setrlimit error %d: %s", errno, strerror(errno));
    else
      LOGW("raised file limit from %lu to %lu", soft, rlim.rlim_cur);
  }

  return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM *vm, void *reserved)
{
  LOGI("JNI unload");

  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
    LOGI("JNI load GetEnv failed");
}

// JNI ServiceSinkhole

int initPipe(JNIEnv *env, jobject instance)
{
  struct arguments args;
  args.env = env;
  args.instance = instance;
  init(&args);

  if (pthread_mutex_init(&lock, NULL))
  {
    LOGE("pthread_mutex_init failed");
  }

  // Create signal pipe
  if (pipe(pipefds))
  {
    LOGE("Create pipe error %d: %s", errno, strerror(errno));
    return errno;
  }
  else
  {
    for (int i = 0; i < 2; i++)
    {
      int flags = fcntl(pipefds[i], F_GETFL, 0);
      if (flags < 0 || fcntl(pipefds[i], F_SETFL, flags | O_NONBLOCK) < 0)
      {
        LOGE("fcntl pipefds[%d] O_NONBLOCK error %d: %s",
                    i, errno, strerror(errno));
        return errno;
      }
    }
  }
  return 0;
}

void cleanPipe()
{
  if (pthread_mutex_destroy(&lock))
  {
    LOGE("pthread_mutex_destroy failed");
  }

  for (int i = 0; i < 2; i++)
  {
    if (close(pipefds[i]))
    {
      LOGE("Close pipe error %d: %s", errno, strerror(errno));
    }
  }
}

JNIEXPORT jint JNICALL
Java_com_htmake_tun2http_Tun2HttpJni_start(
    JNIEnv *env, jclass cls, jint tun, jboolean fwd53, jint rcode, jstring proxyIp, jint proxyPort, jobject vpnServiceInstance, jstring proxyAuth, jint logLevel)
{
  if (thread_id && pthread_kill(thread_id, 0) == 0)
  {
    LOGI("Already running thread %lx", thread_id);
    return 0;
  }
  else
  {
    int res = initPipe(env, vpnServiceInstance);
    if (res != 0)
    {
      LOGE("initPipe error %d", res);
      return res;
    }
    max_tun_msg = 0;

    // Set blocking
    int flags = fcntl(tun, F_GETFL, 0);
    if (flags < 0 || fcntl(tun, F_SETFL, flags & ~O_NONBLOCK) < 0)
    {
      LOGE("fcntl tun ~O_NONBLOCK error %d: %s",
                  errno, strerror(errno));
      return -1;
    }

    const char *proxy_ip = (*env)->GetStringUTFChars(env, proxyIp, 0);
    const char *proxy_auth = (*env)->GetStringUTFChars(env, proxyAuth, 0);
    jint rs = (*env)->GetJavaVM(env, &jvm);
    if (rs != JNI_OK)
    {
      LOGE("GetJavaVM failed");
    }

    // Get arguments
    struct arguments *args = malloc(sizeof(struct arguments));
    // args->env = will be set in thread
    args->instance = (*env)->NewGlobalRef(env, vpnServiceInstance);
    args->tun = tun;
    args->fwd53 = fwd53;
    args->rcode = rcode;
    strcpy(args->proxyIp, proxy_ip);
    if (proxy_auth != NULL) {
      base64enc(args->proxyAuth, proxy_auth, strlen(proxy_auth));
      LOGI("Set proxyAuth %s", args->proxyAuth);
    }
    args->proxyPort = proxyPort;
    if (logLevel >= LOG_LEVEL_NONE && logLevel <= LOG_LEVEL_DEBUG) {
      LOGI("Set log_level %d", logLevel);
      log_level = logLevel;
    }

    (*env)->ReleaseStringUTFChars(env, proxyIp, proxy_ip);
    (*env)->ReleaseStringUTFChars(env, proxyAuth, proxy_auth);

    // Start native thread
    int err = pthread_create(&thread_id, NULL, handle_events, (void *)args);
    if (err == 0)
    {
      LOGI("Started thread %lx", thread_id);
    }
    else
    {
      LOGE("pthread_create error %d: %s", err, strerror(err));
    }

    return err;
  }
}

JNIEXPORT jint JNICALL
Java_com_htmake_tun2http_Tun2HttpJni_stop(
    JNIEnv *env, jclass cls)
{
  pthread_t t = thread_id;
  LOGI("Stop tunnel thread %lx", t);
  if (t && pthread_kill(t, 0) == 0)
  {
    LOGI("Write pipe thread %lx", t);
    if (write(pipefds[1], "x", 1) < 0)
    {
      LOGW("Write pipe error %d: %s", errno, strerror(errno));
      return errno;
    }
    else
    {
      LOGI("Join thread %lx", t);
      int err = pthread_join(t, NULL);
      if (err != 0)
      {
        LOGW("pthread_join error %d: %s", err, strerror(err));
        return err;
      }
    }

    clear();

    LOGI("Stopped thread %lx", t);
    cleanPipe();
    return 0;
  }
  else
  {
    LOGI("Not running thread %lx", t);
    return 0;
  }
}

JNIEXPORT jint JNICALL
Java_com_htmake_tun2http_Tun2HttpJni_get_1mtu(JNIEnv *env, jclass cls)
{
  return get_mtu();
}

// JNI Util

// JNIEXPORT jstring JNICALL
// Java_com_tun2http_app_utils_Util_jni_1getprop(JNIEnv *env, jclass type, jstring name_) {
//     const char *name = (*env)->GetStringUTFChars(env, name_, 0);

//     char value[PROP_VALUE_MAX + 1] = "";
//     __system_property_get(name, value);

//     (*env)->ReleaseStringUTFChars(env, name_, name);

//     return (*env)->NewStringUTF(env, value);
// }

static jmethodID midProtect = NULL;

int protect_socket(const struct arguments *args, int socket)
{
  jclass cls = (*args->env)->GetObjectClass(args->env, args->instance);
  // jclass cls = jniFindClass(args->env, "org/outline/vpn/VpnTunnelSerice");
  if (midProtect == NULL)
    midProtect = jniGetMethodID(args->env, cls, "protect", "(I)Z");

  jboolean isProtected = (*args->env)->CallBooleanMethod(args->env, args->instance, midProtect, socket);
  jniCheckException(args->env);

  if (!isProtected)
  {
    LOGE("protect socket failed");
    return -1;
  }

  (*args->env)->DeleteLocalRef(args->env, cls);

  return 0;
}

jobject jniGlobalRef(JNIEnv *env, jobject cls)
{
  jobject gcls = (*env)->NewGlobalRef(env, cls);
  if (gcls == NULL)
    LOGE("Global ref failed (out of memory?)");
  return gcls;
}

jclass jniFindClass(JNIEnv *env, const char *name)
{
  jclass cls = (*env)->FindClass(env, name);
  if (cls == NULL)
    LOGE("Class %s not found", name);
  else
    jniCheckException(env);
  return cls;
}

jmethodID jniGetMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature)
{
  jmethodID method = (*env)->GetMethodID(env, cls, name, signature);
  if (method == NULL)
  {
    LOGE("Method %s %s not found", name, signature);
    jniCheckException(env);
  }
  return method;
}

int jniCheckException(JNIEnv *env)
{
  jthrowable ex = (*env)->ExceptionOccurred(env);
  if (ex)
  {
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, ex);
    return 1;
  }
  return 0;
}
