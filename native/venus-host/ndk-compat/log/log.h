#pragma once
#include <android/log.h>
#define LOG_PRI(priority, tag, ...) __android_log_print(priority, tag, __VA_ARGS__)
