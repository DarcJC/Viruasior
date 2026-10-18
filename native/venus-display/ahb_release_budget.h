#pragma once
#include <atomic>
class AhbReleaseBudget {
    std::atomic<unsigned> count{0};
public:
    static constexpr unsigned limit=8;
    bool take() {
        unsigned value=count.load(std::memory_order_relaxed);
        while (value<limit) {
            if (count.compare_exchange_weak(value,value+1,std::memory_order_acquire)) return true;
        }
        return false;
    }
    void release() { count.fetch_sub(1,std::memory_order_release); }
};
