package pro.darc.viruaisor.gpu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import pro.darc.viruaisor.root.RootOverlayAuthorization
import java.io.File

/** Explicit, unexported control observer in the normal main process. No privileged operations. */
class VenusOverlayOutsideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token") ?: return
        if (!RootOverlayAuthorization.validRequest(token, "normal")) return
        val lab = File(context.filesDir, "venus-overlay-lab")
        val text = File(lab, "$token.target").readText()
        File(lab, "$token.outside").writeText("uid=${Process.myUid()} pid=${Process.myPid()} view=$text")
    }
}
