#include "release_ticket.h"
#include <atomic>
#include <cassert>
#include <memory>
#include <thread>

struct Payload {
    std::atomic<int> &live;
    explicit Payload(std::atomic<int> &value):live(value) { ++live; }
    ~Payload() { --live; }
};

int main() {
    std::atomic<int> live{0};
    auto create=[&] { return std::make_unique<Payload>(live); };
    {
        ReleaseTicket<Payload> slot;
        assert(slot.assign([] { return std::unique_ptr<Payload>{}; })==0);
        const auto first=slot.assign(create);
        assert(first==1 && live==1);
        bool called=false;
        assert(slot.assign([&] { called=true; return create(); })==0);
        assert(!called && live==1);
        assert(!slot.take(0) && !slot.take(-1) && !slot.take(first+1));
        auto owned=slot.take(first);
        assert(owned && !slot.take(first) && live==1);
        const auto second=slot.assign(create);
        assert(second>first && live==2);
        // A delayed duplicate must not consume the next frame on this Binder.
        assert(!slot.take(first));
        assert(slot.take(second));
        assert(live==1);
        owned.reset();
        assert(live==0);
        assert(slot.assign(create)>second);
    }
    assert(live==0); // an unsent frame is owned by the endpoint until cleanup
    {
        ReleaseTicket<Payload> slot;
        std::atomic<int> assigned{0}, consumed{0};
        std::atomic<int64_t> ticket{0};
        std::thread threads[8];
        for (auto &thread:threads) thread=std::thread([&] {
            auto value=slot.assign(create);
            if (value) { ticket=value; ++assigned; }
        });
        for (auto &thread:threads) thread.join();
        assert(assigned==1 && live==1);
        for (auto &thread:threads) thread=std::thread([&] {
            if (slot.take(ticket.load())) ++consumed;
        });
        for (auto &thread:threads) thread.join();
        assert(consumed==1 && live==0);
    }
}
