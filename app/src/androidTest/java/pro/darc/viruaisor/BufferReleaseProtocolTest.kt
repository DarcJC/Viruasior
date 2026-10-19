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
    @Test fun trackedFenceKeepsTicketBeforeTheExistingFenceEncoding() {
        val pipe=ParcelFileDescriptor.createPipe()
        val parcel=Parcel.obtain()
        try {
            BufferReleaseProtocol.writeTracked(parcel,0x100000001L,pipe[0])
            parcel.setDataPosition(0)
            assertEquals(0x100000001L,parcel.readLong())
            assertTrue(parcel.readBoolean())
            parcel.readTypedObject(ParcelFileDescriptor.CREATOR)!!.use {
                assertTrue(it.fileDescriptor.valid())
            }
            assertEquals(0,parcel.dataAvail())
        } finally { parcel.recycle();pipe.forEach { it.close() } }
    }

    @Test fun trackedAbsentFenceStillCarriesTheTicket() {
        val parcel=Parcel.obtain()
        try {
            BufferReleaseProtocol.writeTracked(parcel,2L,null)
            parcel.setDataPosition(0)
            assertEquals(2L,parcel.readLong())
            assertFalse(parcel.readBoolean())
            assertEquals(0,parcel.dataAvail())
        } finally { parcel.recycle() }
    }

    @Test fun invalidTicketCannotEmitAPartialRelease() {
        val parcel=Parcel.obtain()
        try {
            for (ticket in listOf(0L,-1L)) {
                assertThrows(IllegalArgumentException::class.java) {
                    BufferReleaseProtocol.writeTracked(parcel,ticket,null)
                }
                assertEquals(0,parcel.dataSize())
            }
        } finally { parcel.recycle() }
    }

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
