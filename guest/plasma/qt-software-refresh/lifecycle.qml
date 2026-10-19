import QtQuick 2.15
import QtQuick.Window 2.15

Window {
    visible: true
    width: 160; height: 100
    title: "Viruaisor Qt lifecycle regression"
    Rectangle {
        width: 20; height: 20; color: "red"
        NumberAnimation on x { from: 0; to: 100; duration: 400; loops: Animation.Infinite }
    }
    Timer { interval: 1000; running: true; onTriggered: Qt.quit() }
}
