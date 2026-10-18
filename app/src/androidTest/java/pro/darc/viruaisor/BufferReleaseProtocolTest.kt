package pro.darc.viruaisor

import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pro.darc.viruaisor.display.BufferReleaseProtocol

@RunWith(AndroidJUnit4::class)
class BufferReleaseProtocolTest {
    @Test fun validFenceRetainsTheNullableParcelablePresenceWord() {
        val pipe=ParcelFileDescriptor.createPipe()
        val parcel=Parcel.obtain()
        try {
            BufferReleaseProtocol.write(parcel,pipe[0])
            parcel.setDataPosition(0)
            assertTrue(parcel.readBoolean())
            // AParcel_readParcelFileDescriptor uses readParcelable(optional<PFD>).
            val decoded=parcel.readTypedObject(ParcelFileDescriptor.CREATOR)
            assertNotNull("Valid release FD must not decode as a null fence",decoded)
            decoded!!.use { assertTrue(it.fileDescriptor.valid()) }
            assertEquals(0,parcel.dataAvail())
        } finally { parcel.recycle();pipe.forEach { it.close() } }
    }

    @Test fun absentFenceNeedsNoDescriptor() {
        val parcel=Parcel.obtain()
        try {
            BufferReleaseProtocol.write(parcel,null)
            parcel.setDataPosition(0)
            assertFalse(parcel.readBoolean())
            assertEquals(0,parcel.dataAvail())
        } finally { parcel.recycle() }
    }
}
