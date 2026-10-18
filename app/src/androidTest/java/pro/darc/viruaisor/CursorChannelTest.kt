package pro.darc.viruaisor

import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pro.darc.viruaisor.display.RootCursorSurface

@RunWith(AndroidJUnit4::class)
class CursorChannelTest {
    @Test fun nullableDescriptorTransfersAnAtomicSignedPosition() {
        val channel=RootCursorSurface.positionChannel()
        val parcel=Parcel.obtain()
        try {
            parcel.writeTypedObject(channel[1],0)
            parcel.setDataPosition(0)
            val writer=parcel.readTypedObject(ParcelFileDescriptor.CREATOR)!!
            val bytes=byteArrayOf(-4,-1,-1,-1,32,0,0,0)
            ParcelFileDescriptor.AutoCloseOutputStream(writer).use { it.write(bytes) }
            val result=ByteArray(8)
            ParcelFileDescriptor.AutoCloseInputStream(channel[0]).use {
                assertEquals(8,it.read(result))
            }
            assertArrayEquals(bytes,result)
            assertEquals(0,parcel.dataAvail())
        } finally { channel.forEach { it.close() };parcel.recycle() }
    }
}
