package com.se.telecomalarm.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs several tasks (normally "send one HTTP request") so that they really start at the same moment.
 * <p>
 * How the start is synchronised:
 * <ol>
 *   <li>One thread per task: the pool has exactly as many threads as there are tasks, so no task waits in the
 *       pool's queue behind another one.</li>
 *   <li>Every thread first walks up to a {@link CyclicBarrier} that is set for all the tasks, and blocks there.
 *       Nobody sends anything until the last thread has arrived, which also proves all threads are alive and ready.</li>
 *   <li>When the last thread arrives, the barrier opens and all threads are released together; each one then
 *       immediately runs its task.</li>
 * </ol>
 * A plain loop (or a pool smaller than the number of tasks) would not do this: the requests would go one after
 * another, and a race in the application could never show up.
 * <p>
 * What this does NOT guarantee: the barrier aligns the moment the client threads start, not the moment the server
 * handles each request (connection set-up and HTTP parsing add a little jitter). The window of a typical race in the
 * application is much wider than that jitter, but a test that depends on it should still be repeated.
 */
final class SimultaneousRequests {

    /**
     * Upper bound for the whole run (waiting at the barrier plus every task). A hung request fails the test.
     */
    private static final long TIMEOUT_SECONDS = 30;

    private SimultaneousRequests() {
    }

    /**
     * Runs all tasks at once and returns their results in the order of the tasks.
     * If any task throws, the run fails with an {@link AssertionError} that carries the cause.
     */
    static <T> List<T> runTogether(List<Callable<T>> tasks) {
        int count = tasks.size();
        CyclicBarrier startLine = new CyclicBarrier(count);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startLine.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } catch (ExecutionException e) {
            throw new AssertionError("A parallel request failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("The parallel requests did not finish within " + TIMEOUT_SECONDS + " seconds", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the parallel requests", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
