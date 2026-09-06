package paperlab.log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LabLoggerConcurrencyTest {

    @Test
    @DisplayName("Многопоточный стресс-тест LabLogger: конкурентные подписки и чтение без CME")
    public void testConcurrentSubscriptionsAndIterations() throws Exception {
        final LabLogger logger = new LabLogger("test", false, "default", List.of("opt1", "opt2", "opt3"));
        final int threads = 8;
        final int iterations = 10_000;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final AtomicBoolean failed = new AtomicBoolean(false);
        final List<Future<?>> futures = new ArrayList<>();

        // 4 пишущих потока (переключают подписки)
        for (int i = 0; i < 4; i++) {
            final int id = i;
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterations; j++) {
                        final String player = "Player_" + (j % 20);
                        logger.toggle(player, "opt" + (j % 3 + 1));
                        if (j % 50 == 0) {
                            logger.unsubscribeAll(player);
                        }
                    }
                } catch (final Throwable t) {
                    failed.set(true);
                    t.printStackTrace();
                }
            }));
        }

        // 4 читающих потока (симулируют тики и обработку событий логгеров)
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterations; j++) {
                        for (final var entry : logger.subscribers().entrySet()) {
                            final String player = entry.getKey();
                            assertNotNull(player);
                            for (final String opt : entry.getValue()) {
                                assertNotNull(opt);
                            }
                        }
                    }
                } catch (final Throwable t) {
                    failed.set(true);
                    t.printStackTrace();
                }
            }));
        }

        startLatch.countDown();
        for (final Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertFalse(failed.get(), "Многопоточный тест подписок обязан пройти без ConcurrentModificationException");
    }
}
