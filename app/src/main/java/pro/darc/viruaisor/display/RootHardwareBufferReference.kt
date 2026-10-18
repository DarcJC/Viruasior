package pro.darc.viruaisor.display

import android.hardware.HardwareBuffer
import android.os.Parcel

/** Duplicates the native reference and its ID, without allocating or copying pixels. */
internal fun retainHardwareBuffer(buffer: HardwareBuffer): HardwareBuffer {
    val parcel = Parcel.obtain()
    return try {
        buffer.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        HardwareBuffer.CREATOR.createFromParcel(parcel)
    } finally {
        parcel.recycle()
    }
}
