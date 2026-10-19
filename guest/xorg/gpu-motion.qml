import QtQuick 2.15
import QtQuick.Window 2.15
Window {
    id: probe
    visible: true
    width: 900; height: 300
    title: "Viruaisor native GPU motion clock"
    color: "#806040"
    property int swaps: 0
    property int changes: 0
    property double started: Date.now()
    onFrameSwapped: swaps++
    Timer {
        interval: 5000; running: true; repeat: true
        onTriggered: {
            console.warn("VIRUAISOR_QML_CADENCE swaps=" + probe.swaps + " changes=" + probe.changes + " ms=" + (Date.now()-probe.started))
            probe.swaps=0; probe.changes=0; probe.started=Date.now()
        }
    }
    Row {
        id: bars
        onXChanged: probe.changes++
        y: 0
        Repeater {
            model: ["red", "lime", "blue", "white", "black", "cyan"]
            Rectangle { width: 130; height: 300; color: modelData }
        }
        SequentialAnimation on x {
            loops: Animation.Infinite
            NumberAnimation { from: 10; to: 110; duration: 1000 }
            NumberAnimation { from: 110; to: 10; duration: 1000 }
        }
    }
}
