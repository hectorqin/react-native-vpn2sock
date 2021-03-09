
#include "tun2http.h"

JavaVM *jvm = NULL;
int pipefds[2];
pthread_t thread_id = 0;
pthread_mutex_t lock;
int loglevel = ANDROID_LOG_WARN;

extern int max_tun_msg;
extern struct ng_session *ng_session;

// JNI

jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
  log_android(ANDROID_LOG_INFO, "JNI load");

  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
  {
    log_android(ANDROID_LOG_INFO, "JNI load GetEnv failed");
    return -1;
  }

  // Raise file number limit to maximum
  struct rlimit rlim;
  if (getrlimit(RLIMIT_NOFILE, &rlim))
    log_android(ANDROID_LOG_WARN, "getrlimit error %d: %s", errno, strerror(errno));
  else
  {
    rlim_t soft = rlim.rlim_cur;
    rlim.rlim_cur = rlim.rlim_max;
    if (setrlimit(RLIMIT_NOFILE, &rlim))
      log_android(ANDROID_LOG_WARN, "setrlimit error %d: %s", errno, strerror(errno));
    else
      log_android(ANDROID_LOG_WARN, "raised file limit from %d to %d", soft, rlim.rlim_cur);
  }

  return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM *vm, void *reserved)
{
  log_android(ANDROID_LOG_INFO, "JNI unload");

  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
    log_android(ANDROID_LOG_INFO, "JNI load GetEnv failed");
}

// JNI ServiceSinkhole

int initPipe(JNIEnv *env, jobject instance)
{
  loglevel = ANDROID_LOG_WARN;

  struct arguments args;
  args.env = env;
  args.instance = instance;
  init(&args);

  if (pthread_mutex_init(&lock, NULL))
  {
    log_android(ANDROID_LOG_ERROR, "pthread_mutex_init failed");
  }

  // Create signal pipe
  if (pipe(pipefds))
  {
    log_android(ANDROID_LOG_ERROR, "Create pipe error %d: %s", errno, strerror(errno));
    return errno;
  }
  else
  {
    for (int i = 0; i < 2; i++)
    {
      int flags = fcntl(pipefds[i], F_GETFL, 0);
      if (flags < 0 || fcntl(pipefds[i], F_SETFL, flags | O_NONBLOCK) < 0)
      {
        log_android(ANDROID_LOG_ERROR, "fcntl pipefds[%d] O_NONBLOCK error %d: %s",
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
    log_android(ANDROID_LOG_ERROR, "pthread_mutex_destroy failed");
  }

  for (int i = 0; i < 2; i++)
  {
    if (close(pipefds[i]))
    {
      log_android(ANDROID_LOG_ERROR, "Close pipe error %d: %s", errno, strerror(errno));
    }
  }
}

JNIEXPORT jint JNICALL
Java_com_htmake_tun2http_Tun2HttpJni_start(
    JNIEnv *env, jclass cls, jint tun, jboolean fwd53, jint rcode, jstring proxyIp, jint proxyPort, jobject vpnServiceInstance)
{
  if (thread_id && pthread_kill(thread_id, 0) == 0)
  {
    log_android(ANDROID_LOG_INFO, "Already running thread %x", thread_id);
    return 0;
  }
  else
  {
    int res = initPipe(*env, vpnServiceInstance);
    if (res != 0)
    {
      log_android(ANDROID_LOG_ERROR, "initPipe error %d", res);
      return res;
    }
    max_tun_msg = 0;

    // Set blocking
    int flags = fcntl(tun, F_GETFL, 0);
    if (flags < 0 || fcntl(tun, F_SETFL, flags & ~O_NONBLOCK) < 0)
    {
      log_android(ANDROID_LOG_ERROR, "fcntl tun ~O_NONBLOCK error %d: %s",
                  errno, strerror(errno));
      return -1;
    }

    const char *proxy_ip = (*env)->GetStringUTFChars(env, proxyIp, 0);
    jint rs = (*env)->GetJavaVM(env, &jvm);
    if (rs != JNI_OK)
    {
      log_android(ANDROID_LOG_ERROR, "GetJavaVM failed");
    }

    // Get arguments
    struct arguments *args = malloc(sizeof(struct arguments));
    // args->env = will be set in thread
    args->instance = (*env)->NewGlobalRef(env, vpnServiceInstance);
    args->tun = tun;
    args->fwd53 = fwd53;
    args->rcode = rcode;
    strcpy(args->proxyIp, proxy_ip);
    args->proxyPort = proxyPort;

    (*env)->ReleaseStringUTFChars(env, proxyIp, proxy_ip);

    // Start native thread
    int err = pthread_create(&thread_id, NULL, handle_events, (void *)args);
    if (err == 0)
    {
      log_android(ANDROID_LOG_INFO, "Started thread %x", thread_id);
    }
    else
    {
      log_android(ANDROID_LOG_ERROR, "pthread_create error %d: %s", err, strerror(err));
    }

    return err;
  }
}

JNIEXPORT jint JNICALL
Java_com_htmake_tun2http_Tun2HttpJni_stop(
    JNIEnv *env, jclass cls)
{
  pthread_t t = thread_id;
  log_android(ANDROID_LOG_INFO, "Stop tunnel thread %x", t);
  if (t && pthread_kill(t, 0) == 0)
  {
    log_android(ANDROID_LOG_INFO, "Write pipe thread %x", t);
    if (write(pipefds[1], "x", 1) < 0)
    {
      log_android(ANDROID_LOG_WARN, "Write pipe error %d: %s", errno, strerror(errno));
      return errno;
    }
    else
    {
      log_android(ANDROID_LOG_INFO, "Join thread %x", t);
      int err = pthread_join(t, NULL);
      if (err != 0)
      {
        log_android(ANDROID_LOG_WARN, "pthread_join error %d: %s", err, strerror(err));
        return err;
      }
    }

    clear();

    log_android(ANDROID_LOG_INFO, "Stopped thread %x", t);
    cleanPipe();
    return 0;
  }
  else
  {
    log_android(ANDROID_LOG_INFO, "Not running thread %x", t);
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
    log_android(ANDROID_LOG_ERROR, "protect socket failed");
    return -1;
  }

  (*args->env)->DeleteLocalRef(args->env, cls);

  return 0;
}

jobject jniGlobalRef(JNIEnv *env, jobject cls)
{
  jobject gcls = (*env)->NewGlobalRef(env, cls);
  if (gcls == NULL)
    log_android(ANDROID_LOG_ERROR, "Global ref failed (out of memory?)");
  return gcls;
}

jclass jniFindClass(JNIEnv *env, const char *name)
{
  jclass cls = (*env)->FindClass(env, name);
  if (cls == NULL)
    log_android(ANDROID_LOG_ERROR, "Class %s not found", name);
  else
    jniCheckException(env);
  return cls;
}

jmethodID jniGetMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature)
{
  jmethodID method = (*env)->GetMethodID(env, cls, name, signature);
  if (method == NULL)
  {
    log_android(ANDROID_LOG_ERROR, "Method %s %s not found", name, signature);
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
