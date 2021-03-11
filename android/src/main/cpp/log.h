#include <android/log.h>

#ifndef LOG_LEVEL

#define LOG_LEVEL ANDROID_LOG_SILENT // 日志级别
#if(LOG_LEVEL <= ANDROID_LOG_ERROR)
#define LOG_TAG "tun2http"
#if(LOG_LEVEL <= ANDROID_LOG_VERBOSE)
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE,LOG_TAG,__VA_ARGS__)
#else
#define LOGV(...) NULL
#endif

#if(LOG_LEVEL <= ANDROID_LOG_DEBUG)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,LOG_TAG,__VA_ARGS__)
#else
#define LOGD(...) NULL
#endif

#if(LOG_LEVEL <= ANDROID_LOG_INFO)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#else
#define LOGI(...) NULL
#endif

#if(LOG_LEVEL <= ANDROID_LOG_WARN)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,LOG_TAG,__VA_ARGS__)
#else
#define LOGW(...) NULL
#endif

#if(LOG_LEVEL <= ANDROID_LOG_ERROR)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#else
#define LOGE(...) NULL
#endif
#else
#define LOGV(...) NULL
#define LOGD(...) NULL
#define LOGI(...) NULL
#define LOGW(...) NULL
#define LOGE(...) NULL
#endif

#endif
