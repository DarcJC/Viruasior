// SPDX-License-Identifier: GPL-2.0-or-later
#include <kwineffects.h>

namespace KWin {
// The paired Venus/GLX scanout does not preserve pixels outside the submitted
// damage. Expand an existing paint pass, without scheduling continuous repaints.
class ViruaisorFullRepaint final : public Effect {
public:
    void prePaintScreen(ScreenPrePaintData &data, std::chrono::milliseconds time) override {
        effects->prePaintScreen(data, time);
        data.paint = effects->virtualScreenGeometry();
    }
    bool isActive() const override { return effects->isOpenGLCompositing(); }
};
KWIN_EFFECT_FACTORY_SUPPORTED(ViruaisorFullRepaint, "metadata.json",
                             return effects->isOpenGLCompositing();)
}
#include "full-repaint.moc"
