#include <assert.h>
#include "desktop_lease.h"
int main(void) {
    assert(overlay_desktop_identity(10278,10278,"crosvm_default",0));
    assert(!overlay_desktop_identity(10278,0,"crosvm_default",0));
    assert(!overlay_desktop_identity(10278,10278,"crosvm_other",0));
    assert(!overlay_desktop_identity(10278,10278,"crosvm_viruaisor-stock-overlay-probe",0));
    assert(!overlay_desktop_identity(10278,10278,"crosvm_default",1));
    assert(!overlay_desktop_identity(0,0,"crosvm_default",0));
    assert(overlay_desktop_heartbeat('0','2'));
    assert(overlay_desktop_heartbeat('2','3'));
    assert(overlay_desktop_heartbeat('3','2'));
    assert(!overlay_desktop_heartbeat('2','2'));
    assert(!overlay_desktop_heartbeat('0','0'));
    assert(!overlay_desktop_heartbeat('2','1'));
    assert(!overlay_desktop_heartbeat('2','x'));
}
