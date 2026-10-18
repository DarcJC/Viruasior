package pro.darc.viruaisor.display

import android.os.Parcel
import android.os.ParcelFileDescriptor

/** The NDK reader consumes a nullable Parcelable, including its presence word. */
internal object BufferReleaseProtocol {
    fun write(parcel: Parcel, fence: ParcelFileDescriptor?) {
        parcel.writeBoolean(fence != null)
        if(fence != null) parcel.writeTypedObject(fence,0)
    }
}
