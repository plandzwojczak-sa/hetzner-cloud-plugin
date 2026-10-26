/*
 *     Copyright 2021 https://dnation.cloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boot wait runs on a fake clock, so these assertions are on the poll schedule itself rather
 * than on wall clock.
 */
class NodeCallableBootWaitTest {
    private static final int[] PREVIOUS_SCHEDULE = {45};

    /**
     * Fake clock whose only way to advance is a recorded sleep, so the recorded sequence and the
     * elapsed time can never disagree.
     */
    private static final class FakeClock {
        private final List<Integer> sleeps = new ArrayList<>();
        private long nanos = 1_000_000_000L;

        long nanoTime() {
            return nanos;
        }

        void sleep(int seconds) {
            sleeps.add(seconds);
            nanos += seconds * 1_000_000_000L;
        }

        int totalSlept() {
            return sleeps.stream().mapToInt(Integer::intValue).sum();
        }

        NodeCallable.WaitStrategy strategy(int deadlineMinutes, int[] schedule) {
            return new NodeCallable.WaitStrategy(deadlineMinutes, schedule,
                    NodeCallable.MAX_POLL_INTERVAL, this::nanoTime, this::sleep);
        }

        /**
         * Cumulative instants at which the liveness check runs, for a server that never comes up.
         */
        List<Integer> pollInstants(int deadlineMinutes, int[] schedule) {
            final List<Integer> instants = new ArrayList<>();
            NodeCallable.awaitRunning(strategy(deadlineMinutes, schedule), () -> {
                instants.add(totalSlept());
                return false;
            });
            return instants;
        }
    }

    @Test
    void testServerAlreadyRunningIsNoticedInTwoSeconds() {
        final FakeClock clock = new FakeClock();

        assertTrue(NodeCallable.awaitRunning(clock.strategy(30, NodeCallable.POLL_SCHEDULE),
                () -> true));

        assertEquals(List.of(2), clock.sleeps);
    }

    @Test
    void testPollIntervalsBackOffAndCapAtFifteenSeconds() {
        final FakeClock clock = new FakeClock();
        final AtomicInteger checks = new AtomicInteger();

        assertTrue(NodeCallable.awaitRunning(clock.strategy(30, NodeCallable.POLL_SCHEDULE),
                () -> checks.incrementAndGet() == 6));

        assertEquals(List.of(2, 4, 9, 15, 15, 15), clock.sleeps);
    }

    /**
     * The point of the schedule: every instant the previous one polled on is still polled on, so no
     * boot time is noticed later than before. Without this the two grids are one second apart and a
     * boot landing in that second waits a whole extra interval.
     */
    @Test
    void testEveryInstantThePreviousScheduleWouldPollOnIsStillPolledOn() {
        final List<Integer> previous = new FakeClock().pollInstants(15, PREVIOUS_SCHEDULE);
        final List<Integer> current = new FakeClock().pollInstants(15, NodeCallable.POLL_SCHEDULE);

        assertTrue(current.containsAll(previous),
                "polls " + current + " must cover the previous schedule's " + previous);
    }

    @Test
    void testTheDefaultBootDeadlineGetsMorePollsThanItUsedTo() {
        final int polls = new FakeClock()
                .pollInstants(HetznerConstants.DEFAULT_BOOT_DEADLINE, NodeCallable.POLL_SCHEDULE)
                .size();
        final int previousPolls = new FakeClock()
                .pollInstants(HetznerConstants.DEFAULT_BOOT_DEADLINE, PREVIOUS_SCHEDULE).size();

        assertEquals(3, previousPolls, "the default " + HetznerConstants.DEFAULT_BOOT_DEADLINE
                + " minute deadline used to allow this many liveness checks");
        assertEquals(7, polls, "and now allows this many");
    }

    @Test
    void testDeadlineIsHonouredWhenServerNeverComesUp() {
        final FakeClock clock = new FakeClock();
        final AtomicInteger checks = new AtomicInteger();

        assertFalse(NodeCallable.awaitRunning(clock.strategy(1, NodeCallable.POLL_SCHEDULE), () -> {
            checks.incrementAndGet();
            return false;
        }));

        assertTrue(checks.get() > 0, "liveness must be checked at least once");
        assertTrue(clock.totalSlept() >= 60 && clock.totalSlept() <= 60 + NodeCallable.MAX_POLL_INTERVAL,
                "must give up just past the 1 minute deadline, slept " + clock.totalSlept() + "s");
    }

    @Test
    void testDeadlineFollowsTheTemplate() {
        final FakeClock clock = new FakeClock();

        assertFalse(NodeCallable.awaitRunning(clock.strategy(30, NodeCallable.POLL_SCHEDULE),
                () -> false));

        assertTrue(clock.totalSlept() >= 30 * 60,
                "a 30 minute bootDeadline must be waited out in full, slept " + clock.totalSlept() + "s");
    }
}
