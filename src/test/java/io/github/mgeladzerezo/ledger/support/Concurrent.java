package io.github.mgeladzerezo.ledger.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/** Runs the same task on many threads released at the same instant, to maximise contention. */
public final class Concurrent {

    private Concurrent() {
    }

    /**
     * @param task receives the thread index
     * @return each thread's result, in thread-index order; a task failure fails the test
     */
    public static <T> List<T> run(int threads, IntFunction<T> task) {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.apply(index);
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("concurrent task failed", e);
        }
    }
}
