#include <QtCore/QDebug>
#include <QtCore/private/qabstractanimation_p.h>
#include <QtGui/QGenericPlugin>
#include <QtGui/QGuiApplication>
#include <QtGui/QScreen>
#include <cmath>

// The basic software scenegraph uses QUnifiedTimer's 16 ms default instead of
// a vsync animation driver. Observe the guest screen without replacing Qt's
// renderer or its wall-clock animation accounting.
class SoftwareRefresh final : public QObject
{
public:
    SoftwareRefresh()
    {
        connect(qGuiApp, &QGuiApplication::primaryScreenChanged,
                this, &SoftwareRefresh::observeScreen);
        observeScreen(QGuiApplication::primaryScreen());
    }

private:
    void observeScreen(QScreen *screen)
    {
        disconnect(m_refreshConnection);
        if (screen) {
            m_refreshConnection = connect(screen, &QScreen::refreshRateChanged,
                    this, &SoftwareRefresh::setRefreshRate);
            setRefreshRate(screen->refreshRate());
        } else {
            setRefreshRate(0);
        }
    }

    void setRefreshRate(qreal rate)
    {
        // Qt 5 accepts integer milliseconds. Round down so the animation
        // producer can keep up with 60/120 Hz; wall time still sets its speed.
        const int interval = std::isfinite(rate) && rate >= 10 && rate <= 240
                ? int(std::floor(1000.0 / rate)) : 16;
        if (interval == m_interval)
            return;
        m_interval = interval;
        // Qt restarts only a running driver and preserves its elapsed time.
        QUnifiedTimer::instance()->setTimingInterval(interval);
        qInfo("Viruaisor Qt software animation: %.3f Hz, interval=%d ms",
              double(rate), interval);
    }

    QMetaObject::Connection m_refreshConnection;
    int m_interval = -1;
};

class SoftwareRefreshPlugin final : public QGenericPlugin
{
    Q_OBJECT
    Q_PLUGIN_METADATA(IID QGenericPluginFactoryInterface_iid FILE "metadata.json")
public:
    QObject *create(const QString &name, const QString &) override
    {
        if (name != QStringLiteral("viruaisor-software-refresh"))
            return nullptr;
        const auto backend = qgetenv("QT_QUICK_BACKEND");
        if ((backend != "software" && backend != "softwarecontext")
                || qgetenv("QSG_RENDER_LOOP") == "threaded"
                || QGuiApplication::platformName() != QStringLiteral("xcb")
                || (!qEnvironmentVariableIsEmpty("QSG_FIXED_ANIMATION_STEP")
                    && qgetenv("QSG_FIXED_ANIMATION_STEP") != "no"))
            return new QObject;
        // QGuiApplicationPrivate owns generic-plugin objects. Parenting this
        // to qGuiApp would delete it twice during application teardown.
        return new SoftwareRefresh;
    }
};

#include "refresh.moc"
