package pro.darc.viruaisor.display

import android.graphics.PixelFormat
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import java.io.DataInputStream
import java.io.IOException
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small transparent cursor plane. The desktop's original AHB remains untouched. */
internal class RootCursorSurface private constructor(
    val surface: Surface,
    private val control: SurfaceControl,
    private val bufferControl: SurfaceControl,
    private val queue: Any,
    private val destroy: Method,
    private val parentWidth: Int,
    private val parentHeight: Int,
) {
    private var guestWidth=parentWidth
    private var guestHeight=parentHeight
    private var x=Int.MIN_VALUE
    private var y=Int.MIN_VALUE
    @Volatile private var closed=false
    private var input: DataInputStream?=null

    fun startPositions(pipe: ParcelFileDescriptor) {
        val stream=DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipe))
        input=stream
        Thread({
            val bytes=ByteArray(8)
            val packet=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            try {
                while(!closed) {
                    stream.readFully(bytes)
                    // Coalesce queued moves without delaying the last position.
                    while(stream.available()>=8) stream.readFully(bytes)
                    synchronized(this) {
                        if(!closed) {
                            x=packet.getInt(0); y=packet.getInt(4)
                            updatePosition()
                        }
                    }
                }
            } catch (_: IOException) {
                if(!closed) Log.i("ViruaisorRootDisplay","Cursor position stream closed")
            }
        },"viruaisor-cursor").apply { isDaemon=true;start() }
    }

    @Synchronized fun setGuestSize(width: Int,height: Int) {
        RootDisplaySize.validate(width,height)
        if(closed || (width==guestWidth && height==guestHeight)) return
        guestWidth=width;guestHeight=height
        updatePosition()
    }

    private fun updatePosition() {
        val sx=parentWidth.toFloat()/guestWidth
        val sy=parentHeight.toFloat()/guestHeight
        val visible=x in -64 until guestWidth && y in -64 until guestHeight
        SurfaceControl.Transaction().use {
            it.setVisibility(control,visible)
            if(visible) it.setPosition(control,x*sx,y*sy).setScale(control,sx,sy)
            it.apply()
        }
    }

    /** Producer must detach/close its pipe before this local cleanup. */
    fun close() {
        synchronized(this) {
            if(closed) return
            closed=true
        }
        try { input?.close() } finally {
            try {
                SurfaceControl.Transaction().use { it.setVisibility(control,false).reparent(control,null).apply() }
            } finally {
                try { surface.release() } finally {
                    try { destroy.invoke(queue) } finally { bufferControl.release();control.release() }
                }
            }
        }
    }

    companion object {
        fun positionChannel(): Array<ParcelFileDescriptor> {
            val read=FileDescriptor();val write=FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_SEQPACKET or OsConstants.SOCK_CLOEXEC,0,read,write)
            try {
                val reader=ParcelFileDescriptor.dup(read)
                try { return arrayOf(reader,ParcelFileDescriptor.dup(write)) }
                catch(error: Throwable) { reader.close();throw error }
            } finally { Os.close(read);Os.close(write) }
        }

        fun create(parent: SurfaceControl,width: Int,height: Int): RootCursorSurface {
            val type=Class.forName("android.graphics.BLASTBufferQueue")
            val destroy=type.getMethod("destroy")
            // BLAST owns the buffer layer's geometry. Put position/scale on a
            // separate container so a new cursor glyph cannot reset them.
            val builder=SurfaceControl.Builder().setName("ViruaisorCursorPosition")
                .setParent(parent).setHidden(true)
            SurfaceControl.Builder::class.java.getMethod("setContainerLayer").invoke(builder)
            val control=builder.build()
            var bufferControl: SurfaceControl?=null
            var queue: Any?=null
            var surface: Surface?=null
            try {
                val buffer=SurfaceControl.Builder().setName("ViruaisorRootCursor")
                    .setParent(control).setBufferSize(64,64).setFormat(PixelFormat.RGBA_8888)
                    .setOpaque(false).setHidden(false).build()
                bufferControl=buffer
                val created=type.getConstructor(String::class.java,Boolean::class.javaPrimitiveType)
                    .newInstance("ViruaisorRootCursor",true)
                queue=created
                type.getMethod("update",SurfaceControl::class.java,Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
                    .invoke(created,buffer,64,64,PixelFormat.RGBA_8888)
                val output=type.getMethod("createSurface").invoke(created) as Surface
                surface=output
                SurfaceControl.Transaction().use {
                    SurfaceControl.Transaction::class.java.getMethod("setTrustedOverlay",SurfaceControl::class.java,
                        Boolean::class.javaPrimitiveType).invoke(it,control,true)
                    it.setLayer(control,3).apply()
                }
                return RootCursorSurface(output,control,buffer,created,destroy,width,height)
            } catch(error: Throwable) {
                runCatching { surface?.release() }
                runCatching { queue?.let { destroy.invoke(it) } }
                bufferControl?.release()
                control.release()
                throw error
            }
        }
    }
}
