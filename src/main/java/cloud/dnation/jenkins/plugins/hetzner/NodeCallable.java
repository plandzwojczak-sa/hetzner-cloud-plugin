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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;
import hudson.model.Computer;
import hudson.model.Node;
import jenkins.model.Jenkins;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

@Slf4j
@RequiredArgsConstructor
class NodeCallable implements Callable<Node> {
    /**
     * Cumulative poll instants 2, 6, 15, 30, 45, 60, ... - a superset of the 45, 60, 75, ... this
     * plugin polled on before, so a server is never noticed later than it used to be.
     */
    @VisibleForTesting
    static final int[] POLL_SCHEDULE = {2, 4, 9};
    @VisibleForTesting
    static final int MAX_POLL_INTERVAL = 15;
    @VisibleForTesting
    static final int SETTLE_AFTER_RUNNING = 2;

    private final HetznerServerAgent agent;
    private final HetznerCloud cloud;

    @VisibleForTesting
    static boolean awaitRunning(WaitStrategy waitStrategy, BooleanSupplier aliveCheck) {
        while (!waitStrategy.isDeadLineOver()) {
            waitStrategy.waitNext();
            if (aliveCheck.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Node call() throws Exception {
        Computer computer = agent.getComputer();
        if (computer != null && computer.isOnline()) {
            return agent;
        }
        final HetznerServerInfo serverInfo = cloud.getResourceManager().createServer(agent);
        agent.setServerInstance(serverInfo);
        final String serverName = serverInfo.getServerDetail().getName();
        final int bootDeadline = agent.getTemplate().getBootDeadline();
        //wait for status == "running", but at most bootDeadline minutes
        final WaitStrategy waitStrategy = new WaitStrategy(bootDeadline, POLL_SCHEDULE, MAX_POLL_INTERVAL);
        Preconditions.checkState(awaitRunning(waitStrategy, agent::isAlive),
                "Server '%s' didn't start after %s minutes, giving up", serverName, bootDeadline);
        log.info("Server '{}' is now running, waiting {} seconds before proceeding", serverName,
                SETTLE_AFTER_RUNNING);
        Uninterruptibles.sleepUninterruptibly(SETTLE_AFTER_RUNNING, TimeUnit.SECONDS);
        Jenkins.get().addNode(agent);
        computer = agent.toComputer();
        int retry = 5;
        boolean connected = false;
        if (computer != null) {
            while (--retry > 0) {
                try {
                    computer.connect(false).get();
                    connected = true;
                    break;
                } catch (InterruptedException | ExecutionException e) {
                    log.warn("Connection to '{}' has failed, remaining retries {}", computer.getDisplayName(),
                            retry, e);
                    TimeUnit.SECONDS.sleep(10);
                }
            }
            if (!connected) {
                throw new IllegalStateException("Computer is not connected : " + computer.getName());
            }
        } else {
            throw new IllegalStateException("No computer object in agent " + agent.getDisplayName());
        }

        return agent;
    }

    @VisibleForTesting
    static final class WaitStrategy {
        private final int[] schedule;
        private final int maxInterval;
        private final long deadlineNanos;
        private final LongSupplier clock;
        private final IntConsumer sleeper;
        private int polls;

        WaitStrategy(int deadlineMinutes, int[] schedule, int maxInterval) {
            this(deadlineMinutes, schedule, maxInterval, System::nanoTime,
                    seconds -> Uninterruptibles.sleepUninterruptibly(seconds, TimeUnit.SECONDS));
        }

        @VisibleForTesting
        WaitStrategy(int deadlineMinutes, int[] schedule, int maxInterval, LongSupplier clock,
                     IntConsumer sleeper) {
            Preconditions.checkArgument(schedule.length > 0, "empty poll schedule");
            Preconditions.checkArgument(maxInterval > 0, "maxInterval must be positive");
            this.clock = clock;
            this.sleeper = sleeper;
            this.deadlineNanos = clock.getAsLong() + deadlineMinutes * 60L * 1_000_000_000L;
            this.schedule = schedule.clone();
            this.maxInterval = maxInterval;
        }

        boolean isDeadLineOver() {
            return clock.getAsLong() > deadlineNanos;
        }

        void waitNext() {
            sleeper.accept(polls < schedule.length ? schedule[polls++] : maxInterval);
        }
    }
}
