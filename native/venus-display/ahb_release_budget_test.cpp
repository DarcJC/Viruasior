#include "ahb_release_budget.h"
#include <cassert>
#include <thread>
#include <vector>
int main() {
    AhbReleaseBudget budget;
    for(unsigned i=0;i<budget.limit;i++) assert(budget.take());
    assert(!budget.take());
    budget.release(); assert(budget.take()); assert(!budget.take());
    for(unsigned i=0;i<budget.limit;i++) budget.release();
    std::atomic<unsigned> active{0};
    std::vector<std::thread> threads;
    for(unsigned i=0;i<24;i++) threads.emplace_back([&]{
        for(int n=0;n<1000;n++) if(budget.take()) {
            assert(active.fetch_add(1)<budget.limit);
            active.fetch_sub(1);budget.release();
        }
    });
    for(auto &thread:threads) thread.join();
    assert(active==0);
    for(unsigned i=0;i<budget.limit;i++) assert(budget.take());
    assert(!budget.take());
}
