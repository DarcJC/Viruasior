#include "scanout_buffer.h"
#include <cassert>
#include <cstring>
int main() {
    ScanoutBuffer frame;
    assert(!frame.available());
    assert(frame.configure(2,2));
    assert(!frame.available());
    const uint32_t bgra[]={0xff112233,0x80445566,0xffaabbcc,0xff010203};
    std::memcpy(frame.data(),bgra,sizeof(bgra));
    frame.finish(); // The UI may be absent for an arbitrary time here.
    assert(frame.available());
    uint32_t first[6]={0,0,99,0,0,99},reattached[6]={0,0,77,0,0,77};
    assert(frame.copyRgba(first,12));
    assert(frame.copyRgba(reattached,12));
    assert(first[0]==0xff332211 && first[1]==0x80665544 && first[3]==0xffccbbaa);
    assert(first[2]==99 && first[5]==99 && reattached[2]==77 && reattached[5]==77);
    assert(first[0]==reattached[0] && first[3]==reattached[3]);
    assert(!std::memcmp(frame.data(),bgra,sizeof(bgra))); // Presentation never mutates retained BGRA.
    assert(frame.configure(2,2) && frame.available());
    assert(!frame.configure(UINT32_MAX,2) && frame.available());
    assert(frame.configure(3,2) && !frame.available());
    assert(!frame.copyRgba(first,12));
}
