package pro.darc.viruaisor.ui

import android.view.SurfaceHolder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import pro.darc.viruaisor.display.*
import pro.darc.viruaisor.vm.DisplaySize

@Composable
internal fun VmSurface(
    modifier: Modifier = Modifier,
    backend: DisplayBackend,
    fullscreen: Boolean,
    onEnterFullscreen: () -> Unit,
    onReadyChanged: (Boolean) -> Unit,
    guestSize: DisplaySize,
    bufferSize: DisplaySize,
    inputEnabled: Boolean,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier=modifier.background(Color.Black)) {
    val aspect = guestSize.width.toFloat()/guestSize.height
    val fitted=if (maxWidth/maxHeight > aspect) Modifier.fillMaxHeight().aspectRatio(aspect)
        else Modifier.fillMaxWidth().aspectRatio(aspect)
    AndroidView(
        modifier = fitted.align(Alignment.Center),
        factory = {
            DesktopSurfaceView(context).apply {
                // The Android parent and virtio touch ABS ranges keep their boot dimensions.
                // New guest scanouts scale into this space; input uses the confirmed guest mode.
                holder.setFixedSize(bufferSize.width, bufferSize.height)
                inputWidth=bufferSize.width
                inputHeight=bufferSize.height
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        // The callback supplies fixed-buffer dimensions, not on-screen view size.
                        onReadyChanged(false)
                        backend.attach(DisplayTarget(holder.surface, surfaceControl, width, height)) {
                            post { onReadyChanged(true) }
                        }
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onReadyChanged(false)
                        backend.detach(holder.surface)
                    }
                })
            }
        },
        update = { view ->
            // The view can exist before the service replaces imported dimensions
            // with the VM's native boot size. Refresh the fixed ABS range too.
            if (view.inputWidth != bufferSize.width || view.inputHeight != bufferSize.height) {
                view.releaseInput()
                view.inputWidth=bufferSize.width
                view.inputHeight=bufferSize.height
                view.holder.setFixedSize(bufferSize.width,bufferSize.height)
            }
            if (view.guestWidth != guestSize.width || view.guestHeight != guestSize.height || !inputEnabled) view.releaseInput()
            view.guestWidth=guestSize.width
            view.guestHeight=guestSize.height
            view.inputEnabled=inputEnabled
            view.enterFullscreen = onEnterFullscreen
            if (view.fullscreen != fullscreen) view.fullscreen = fullscreen
            view.resumePointerCapture()
        },
        onRelease = { view ->
            view.releaseInput()
            onReadyChanged(false)
            backend.detach(view.holder.surface)
        },
    )
    }
}
