package io.github.timer_err.qml4j.android;

import io.github.timer_err.qml4j.render.items.animation.AbstractAnimation;
import io.github.timer_err.qml4j.render.items.animation.Timer;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Image;
import io.github.timer_err.qml4j.render.items.core.Item;

/**
 * Extra work that keeps an otherwise idle Android QML surface rendering.
 *
 * <p>{@link android.opengl.GLSurfaceView#RENDERMODE_WHEN_DIRTY} cannot observe
 * clocks owned by qml4j or an image decoder publishing into an {@link Image}.
 * Stopping after the frame that starts either operation leaves it suspended
 * until an unrelated touch requests another frame.</p>
 */
final class AndroidRenderPolicy {
    private AndroidRenderPolicy() { }

    static boolean hasActiveQmlWork(Item root) {
        return hasActiveQmlWork(root, true);
    }

    static boolean shouldRenderContinuously(boolean hostAnimation,
                                             boolean changedThisFrame,
                                             boolean settlingAfterWake,
                                             Item root) {
        return hostAnimation || changedThisFrame || settlingAfterWake
                || hasActiveQmlWork(root, true);
    }

    private static boolean hasActiveQmlWork(Item item, boolean ancestorsVisible) {
        if (item == null) return false;
        if (item instanceof AbstractAnimation
                && Boolean.TRUE.equals(((AbstractAnimation) item).running.peek())) {
            return true;
        }
        if (item instanceof Timer
                && Boolean.TRUE.equals(((Timer) item).running.peek())) {
            return true;
        }
        // Scroll clocks update paint-only positions, so an unchanged layout
        // version does not mean that the surface can stop producing frames.
        if (item instanceof Flickable
                && Boolean.TRUE.equals(((Flickable) item).moving.peek())) {
            return true;
        }
        boolean visible = ancestorsVisible
                && Boolean.TRUE.equals(item.visible.peek())
                && item.opacity.peekFloat() > 0.001f;
        if (visible && item instanceof Image && ((Image) item).hasPendingDecode()) {
            return true;
        }
        for (int i = 0; i < item.children.size(); i++) {
            if (hasActiveQmlWork(item.children.get(i), visible)) return true;
        }
        for (int i = 0; i < item.resources.size(); i++) {
            if (hasActiveQmlWork(item.resources.get(i), visible)) return true;
        }
        return false;
    }
}
