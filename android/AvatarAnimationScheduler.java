package br.com.rian.deck;

import java.util.concurrent.ThreadLocalRandom;

final class AvatarAnimationScheduler {
    static final long ACTIVITY_DURATION = 6500;
    private static final long[] GESTURE_DURATION = {300, 2600, 2900, 2350, 2100, 1900};
    private final int[] order = {0,1,2,3,4,5,6,7,8};
    private int cursor = order.length, previousActivity = -1, previousGesture = -1;
    private long nextActivity, nextGesture, activityInterval, gestureInterval;
    int activity = -1, gesture = -1;
    long started;

    void reset() {
        activity = gesture = -1;
        nextActivity = nextGesture = 0;
    }

    void update(long now, int idleSeconds, int activitySeconds) {
        long idle = idleSeconds * 1000L, full = activitySeconds * 1000L;
        if (nextActivity == 0 || idle != gestureInterval || full != activityInterval) {
            gestureInterval = idle;
            activityInterval = full;
            nextActivity = now + full;
            nextGesture = now + idle;
        }
        if (activity >= 0 && now - started >= ACTIVITY_DURATION) activity = -1;
        if (gesture >= 0 && now - started >= GESTURE_DURATION[gesture]) gesture = -1;
        if (now >= nextActivity && activity < 0) {
            activity = nextScene();
            gesture = -1;
            started = now;
            nextActivity = now + full;
            if (nextGesture <= now) nextGesture = now + idle;
        } else if (now >= nextGesture) {
            nextGesture = now + idle;
            if (activity < 0 && gesture < 0) {
                varRandomGesture();
                started = now;
            }
        }
    }

    private void varRandomGesture() {
        int pick = ThreadLocalRandom.current().nextInt(GESTURE_DURATION.length - (previousGesture < 0 ? 0 : 1));
        gesture = previousGesture >= 0 && pick >= previousGesture ? pick + 1 : pick;
        previousGesture = gesture;
    }

    float gestureProgress(long now) {
        return gesture < 0 ? 0 : Math.min(1f, (float)(now - started) / GESTURE_DURATION[gesture]);
    }

    private int nextScene() {
        if (cursor == order.length) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = order.length - 1; i > 0; i--) swap(i, random.nextInt(i + 1));
            if (order[0] == previousActivity) swap(0, random.nextInt(1, order.length));
            cursor = 0;
        }
        return previousActivity = order[cursor++];
    }

    private void swap(int a, int b) {
        int saved = order[a];
        order[a] = order[b];
        order[b] = saved;
    }
}
