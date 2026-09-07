package com.e2eechat.desktop.ui;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The animation system: easing curves, interpolation, and a single clock driving every animation.
 *
 * <h2>One ticker, not one per animation</h2>
 * Every running animation is advanced by one shared 60 Hz {@link Timer}, which starts when the
 * first animation begins and stops when the last one ends. The obvious alternative - a {@code Timer}
 * per animation - drifts, because independent timers fire on their own phase, so two animations
 * started together visibly desynchronise. It also leaves a timer running per animated component,
 * which on a chat list of any length is a lot of idle wakeups.
 *
 * <h2>Reduced motion</h2>
 * Motion is not decoration for everyone. Vestibular disorders make sliding and parallax genuinely
 * unpleasant, and some people simply find it slow. When {@link #isReducedMotion()} is set, every
 * animation jumps straight to its final value and calls its completion handler, so callers need no
 * special path: the UI still ends in exactly the same state, it just gets there instantly.
 *
 * <p>Not thread-safe: like the rest of the UI, all access must happen on the event dispatch thread.
 */
public final class Motion {

    /** Receives the eased progress of an animation, from 0 to 1. */
    public interface Frame {
        void at(float progress);
    }

    // Durations, in milliseconds. Small enough to feel immediate; the eye reads anything past about
    // 400ms in a UI control as sluggish rather than smooth.
    public static final int INSTANT = 90;
    public static final int FAST = 150;
    public static final int NORMAL = 220;
    public static final int SLOW = 320;
    public static final int DELIBERATE = 460;

    private static final int FRAME_MS = 16;   // ~60fps

    private static final Preferences PREFS = Preferences.userRoot().node("com/e2eechat/desktop");
    private static final String PREF_REDUCED = "reducedMotion";

    private static final List<Running> RUNNING = new ArrayList<>();
    private static Timer ticker;
    private static boolean reducedMotion = PREFS.getBoolean(PREF_REDUCED, false);

    private Motion() {
    }

    // ------------------------------------------------------------- preferences

    public static boolean isReducedMotion() {
        return reducedMotion;
    }

    /**
     * Turns animation on or off for this user.
     *
     * <p>Anything already running is finished immediately rather than frozen part-way, which would
     * otherwise leave a component stranded at a half-faded alpha.
     */
    public static void setReducedMotion(boolean value) {
        reducedMotion = value;
        PREFS.putBoolean(PREF_REDUCED, value);
        if (value) {
            finishAll();
        }
    }

    // ------------------------------------------------------------------ easing

    /**
     * The default curve: quick to leave, gentle to arrive.
     *
     * <p>Used for almost everything that moves on screen. Symmetric ease-in-out is the instinctive
     * choice and the wrong one for interface motion - it makes an element dawdle at the start, which
     * reads as lag between the click and the response.
     */
    public static float easeOut(float t) {
        float inverse = 1f - clamp(t);
        return 1f - inverse * inverse * inverse;
    }

    /** Gentler still, for large surfaces where a fast finish looks abrupt. */
    public static float easeOutQuart(float t) {
        float inverse = 1f - clamp(t);
        return 1f - inverse * inverse * inverse * inverse;
    }

    /** Symmetric. Correct for something moving between two points with no user input at either end. */
    public static float easeInOut(float t) {
        float clamped = clamp(t);
        return clamped < 0.5f
                ? 4f * clamped * clamped * clamped
                : 1f - (float) Math.pow(-2f * clamped + 2f, 3) / 2f;
    }

    /**
     * Overshoots slightly and settles back, the way a physical object with mass would.
     *
     * <p>Reserved for things appearing on demand - a panel the user asked for. Applying it to
     * something that merely changes state makes the interface feel jittery.
     */
    public static float easeOutBack(float t) {
        float overshoot = 1.70158f;
        float shifted = clamp(t) - 1f;
        return 1f + (overshoot + 1f) * shifted * shifted * shifted + overshoot * shifted * shifted;
    }

    /** A decaying oscillation, for drawing attention to a rejected input. */
    public static float shake(float t) {
        float clamped = clamp(t);
        return (float) (Math.sin(clamped * Math.PI * 6) * (1 - clamped));
    }

    private static float clamp(float t) {
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    // ----------------------------------------------------------- interpolation

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    public static int lerp(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    /**
     * Blends two colours, alpha included.
     *
     * <p>Done in straight sRGB. Perceptually it would be better done in a linear space, but every
     * pair blended here is a Telegram palette colour and its own hover shade - close enough in hue
     * that the difference is invisible, and not worth the conversion on every frame of every row.
     */
    public static Color lerp(Color from, Color to, float t) {
        return new Color(
                lerp(from.getRed(), to.getRed(), t),
                lerp(from.getGreen(), to.getGreen(), t),
                lerp(from.getBlue(), to.getBlue(), t),
                lerp(from.getAlpha(), to.getAlpha(), t));
    }

    // -------------------------------------------------------------- animations

    /** Runs {@code frame} from 0 to 1 over {@code durationMs}, eased with {@link #easeOut}. */
    public static Handle animate(int durationMs, Frame frame) {
        return animate(durationMs, frame, null);
    }

    public static Handle animate(int durationMs, Frame frame, Runnable onDone) {
        return animate(durationMs, Easing.EASE_OUT, frame, onDone);
    }

    /**
     * Starts an animation.
     *
     * @param durationMs how long it should take
     * @param easing     the curve applied to progress before {@code frame} sees it
     * @param frame      called with eased progress, once per frame, on the event dispatch thread
     * @param onDone     called after the final frame, or immediately under reduced motion
     * @return a handle that can cancel or complete it early
     */
    public static Handle animate(int durationMs, Easing easing, Frame frame, Runnable onDone) {
        if (reducedMotion || durationMs <= 0) {
            // Land on the end state immediately. The caller's completion handler still runs, so
            // nothing downstream has to know motion was skipped.
            frame.at(1f);
            if (onDone != null) {
                onDone.run();
            }
            return Handle.COMPLETED;
        }

        Running running = new Running(durationMs, easing, frame, onDone);
        RUNNING.add(running);
        frame.at(easing.apply(0f));
        startTicker();
        return running;
    }

    /**
     * Fades a component's opacity, for components that paint themselves through
     * {@link AlphaPanel} or an equivalent.
     */
    public static Handle fade(AlphaPanel panel, float from, float to, int durationMs) {
        return animate(durationMs, Easing.EASE_OUT,
                progress -> panel.setAlpha(lerp(from, to, progress)), null);
    }

    /** Nudges a component left and right to reject an input, then leaves it where it started. */
    public static Handle shakeComponent(JComponent component, int amplitude) {
        int originalX = component.getX();
        return animate(SLOW, Easing.LINEAR, progress -> {
            component.setLocation(originalX + Math.round(shake(progress) * amplitude),
                    component.getY());
        }, () -> component.setLocation(originalX, component.getY()));
    }

    private static void startTicker() {
        if (ticker == null) {
            ticker = new Timer(FRAME_MS, e -> tick());
            ticker.setCoalesce(true);
        }
        if (!ticker.isRunning()) {
            ticker.start();
        }
    }

    private static void tick() {
        long now = System.nanoTime();
        // Iterate a copy: a completion handler is allowed to start another animation, and often
        // does - a chained transition would otherwise mutate the list mid-iteration.
        List<Running> snapshot = new ArrayList<>(RUNNING);
        for (Running running : snapshot) {
            running.advance(now);
        }
        for (Iterator<Running> it = RUNNING.iterator(); it.hasNext();) {
            if (it.next().finished) {
                it.remove();
            }
        }
        if (RUNNING.isEmpty() && ticker != null) {
            ticker.stop();
        }
    }

    /** Completes everything in flight, used when motion is switched off mid-animation. */
    private static void finishAll() {
        for (Running running : new ArrayList<>(RUNNING)) {
            running.complete();
        }
        RUNNING.clear();
        if (ticker != null) {
            ticker.stop();
        }
    }

    /** A named curve, so call sites read as intent rather than as a method reference. */
    public enum Easing {
        LINEAR,
        EASE_OUT,
        EASE_OUT_QUART,
        EASE_IN_OUT,
        EASE_OUT_BACK;

        float apply(float t) {
            switch (this) {
                case EASE_OUT:
                    return easeOut(t);
                case EASE_OUT_QUART:
                    return easeOutQuart(t);
                case EASE_IN_OUT:
                    return easeInOut(t);
                case EASE_OUT_BACK:
                    return easeOutBack(t);
                case LINEAR:
                default:
                    return clamp(t);
            }
        }
    }

    /** Lets a caller stop an animation, typically because it is starting a replacement. */
    public interface Handle {
        /** Stops without running the completion handler and without jumping to the end. */
        void cancel();

        /** Jumps to the end state and runs the completion handler. */
        void complete();

        boolean isRunning();

        Handle COMPLETED = new Handle() {
            @Override
            public void cancel() {
            }

            @Override
            public void complete() {
            }

            @Override
            public boolean isRunning() {
                return false;
            }
        };
    }

    private static final class Running implements Handle {
        private final long startNanos = System.nanoTime();
        private final long durationNanos;
        private final Easing easing;
        private final Frame frame;
        private final Runnable onDone;
        private boolean finished;

        Running(int durationMs, Easing easing, Frame frame, Runnable onDone) {
            this.durationNanos = durationMs * 1_000_000L;
            this.easing = easing;
            this.frame = frame;
            this.onDone = onDone;
        }

        void advance(long now) {
            if (finished) {
                return;
            }
            float raw = (float) ((now - startNanos) / (double) durationNanos);
            if (raw >= 1f) {
                complete();
            } else {
                frame.at(easing.apply(raw));
            }
        }

        @Override
        public void complete() {
            if (finished) {
                return;
            }
            finished = true;
            frame.at(1f);
            if (onDone != null) {
                onDone.run();
            }
        }

        @Override
        public void cancel() {
            finished = true;
        }

        @Override
        public boolean isRunning() {
            return !finished;
        }
    }
}
