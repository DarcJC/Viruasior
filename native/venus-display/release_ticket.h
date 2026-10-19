#pragma once
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <utility>

/* One in-flight handoff per reusable endpoint. Taking a payload transfers its
 * ownership; the consumer's actual fence still controls when it may be freed. */
template<class T> class ReleaseTicket {
    std::mutex mutex;
    std::unique_ptr<T> payload;
    int64_t generation=0;
public:
    template<class Factory> int64_t assign(Factory &&create) {
        std::lock_guard guard(mutex);
        if (payload || generation==std::numeric_limits<int64_t>::max()) return 0;
        auto next=std::forward<Factory>(create)();
        if (!next) return 0;
        payload=std::move(next);
        return ++generation;
    }
    std::unique_ptr<T> take(int64_t ticket) {
        std::lock_guard guard(mutex);
        if (ticket<=0 || ticket!=generation) return {};
        return std::move(payload);
    }
};
