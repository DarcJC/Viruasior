#include <QtCore/QDebug>
#include <QtCore/QLockFile>
#include <QtCore/QSocketNotifier>
#include <QtCore/QTimer>
#include <QtDBus/QDBusInterface>
#include <QtDBus/QDBusReply>
#include <QtDBus/QDBusServiceWatcher>
#include <QtGui/QGuiApplication>
#include <QtGui/QScreen>
#include <algorithm>
#include <cerrno>
#include <cmath>
#include <csignal>
#include <cstdio>
#include <fcntl.h>
#include <unistd.h>

namespace {
int signalWriteFd = -1;
void stopSignal(int)
{
    const int savedErrno = errno;
    const char byte = 1;
    if (signalWriteFd >= 0) {
        const ssize_t result = write(signalWriteFd, &byte, 1);
        (void)result;
    }
    errno = savedErrno;
}

class CompositorPolicy final : public QObject
{
public:
    CompositorPolicy()
        : watcher(QStringLiteral("org.kde.KWin"), QDBusConnection::sessionBus(),
                  QDBusServiceWatcher::WatchForOwnerChange, this)
    {
        settle.setSingleShot(true);
        settle.setInterval(200);
        connect(&settle, &QTimer::timeout, this, &CompositorPolicy::update);
        connect(&watcher, &QDBusServiceWatcher::serviceOwnerChanged, this,
                [this] { ownSuspension(false); settle.start(); });
        connect(qGuiApp, &QGuiApplication::screenAdded, this, [this] { observe(); });
        connect(qGuiApp, &QGuiApplication::screenRemoved, this, [this] { observe(); });
        connect(qGuiApp, &QCoreApplication::aboutToQuit, this, &CompositorPolicy::restore);
        observe();
    }

private:
    void ownSuspension(bool value)
    {
        if (owned == value)
            return;
        owned = value;
        // The session supervisor can restore composition even after a crash.
        std::printf("VIRUAISOR_COMPOSITION_OWNED=%d\n", int(owned));
        std::fflush(stdout);
    }

    bool request(const char *method)
    {
        QDBusInterface compositor(QStringLiteral("org.kde.KWin"), QStringLiteral("/Compositor"),
                                  QStringLiteral("org.kde.kwin.Compositing"));
        compositor.setTimeout(1000);
        const QDBusReply<void> result = compositor.call(QString::fromLatin1(method));
        if (!result.isValid())
            qWarning() << "Viruaisor compositor policy:" << method << result.error().message();
        return result.isValid();
    }

    void restore()
    {
        if (owned && request("resume"))
            ownSuspension(false);
    }

    void observe()
    {
        for (const auto &connection : screenConnections)
            disconnect(connection);
        screenConnections.clear();
        for (QScreen *screen : QGuiApplication::screens()) {
            screenConnections.append(connect(screen, &QScreen::geometryChanged, this, [this] { settle.start(); }));
            screenConnections.append(connect(screen, &QScreen::refreshRateChanged, this, [this] { settle.start(); }));
        }
        settle.start();
    }

    void update()
    {
        const auto screens = QGuiApplication::screens();
        bool direct = false;
        if (screens.size() == 1) {
            const QSize size = screens.front()->size();
            const int longest = std::max(size.width(), size.height());
            const int shortest = std::min(size.width(), size.height());
            const double rate = screens.front()->refreshRate();
            // Only the measured 1920-wide 120 Hz presets use this path.
            direct = longest == 1920 && (shortest == 1200 || shortest == 1280)
                    && std::isfinite(rate) && std::abs(rate - 120.0) < 1.0;
            qInfo("Viruaisor compositor policy: %dx%d %.3f Hz, direct=%d",
                  size.width(), size.height(), rate, int(direct));
        }
        if (!direct) {
            restore();
            return;
        }
        if (owned)
            return;
        QDBusInterface compositor(QStringLiteral("org.kde.KWin"), QStringLiteral("/Compositor"),
                                  QStringLiteral("org.kde.kwin.Compositing"));
        compositor.setTimeout(1000);
        const QVariant active = compositor.property("active");
        // An already disabled compositor belongs to the user or another client.
        if (!active.isValid() || !active.toBool())
            return;
        ownSuspension(true);
        if (!request("suspend"))
            restore();
    }

    QTimer settle;
    QDBusServiceWatcher watcher;
    QList<QMetaObject::Connection> screenConnections;
    bool owned = false;
};
}

int main(int argc, char **argv)
{
    QGuiApplication app(argc, argv);
    if (QGuiApplication::platformName() != QStringLiteral("xcb"))
        return 1;
    QLockFile lock(qEnvironmentVariable("XDG_RUNTIME_DIR") + QStringLiteral("/viruaisor-compositor.lock"));
    if (!lock.tryLock())
        return 1;
    int stopPipe[2];
    if (pipe2(stopPipe, O_CLOEXEC | O_NONBLOCK) != 0)
        return 1;
    signalWriteFd = stopPipe[1];
    std::signal(SIGTERM, stopSignal);
    std::signal(SIGINT, stopSignal);
    QSocketNotifier stopNotifier(stopPipe[0], QSocketNotifier::Read);
    QObject::connect(&stopNotifier, &QSocketNotifier::activated, &app, [&app] { app.quit(); });
    CompositorPolicy policy;
    const int result = app.exec();
    signalWriteFd = -1;
    close(stopPipe[0]);
    close(stopPipe[1]);
    return result;
}
