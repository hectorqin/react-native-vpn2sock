// log level
#ifndef LOG_LEVEL_NONE
#define LOG_LEVEL_NONE 0
#define LOG_LEVEL_ERROR 1
#define LOG_LEVEL_WARN 2
#define LOG_LEVEL_INFO 3
#define LOG_LEVEL_DEBUG 4
#define LOG_TAG "tun2http"

extern int log_level;

#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...)                                                 \
    do                                                               \
    {                                                                \
        if (log_level >= LOG_LEVEL_ERROR)                            \
        {                                                            \
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); \
        }                                                            \
    } while (0)
#define LOGW(...)                                                 \
    do                                                               \
    {                                                                \
        if (log_level >= LOG_LEVEL_WARN)                            \
        {                                                            \
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__); \
        }                                                            \
    } while (0)
#define LOGI(...)                                                \
    do                                                              \
    {                                                               \
        if (log_level >= LOG_LEVEL_INFO)                            \
        {                                                           \
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); \
        }                                                           \
    } while (0)
#define LOGD(...)                                                 \
    do                                                               \
    {                                                                \
        if (log_level >= LOG_LEVEL_DEBUG)                            \
        {                                                            \
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); \
        }                                                            \
    } while (0)
#endif
