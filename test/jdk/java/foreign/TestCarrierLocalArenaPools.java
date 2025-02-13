/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */

/*
 * @test
 * @summary Test TestCarrierLocalArenaPools
 * @library /test/lib
 * @modules java.base/jdk.internal.foreign
 * @run junit TestCarrierLocalArenaPools
 */

import java.io.FileDescriptor;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import jdk.internal.foreign.CarrierLocalArenaPools;
import jdk.test.lib.thread.VThreadRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

final class TestCarrierLocalArenaPools {

    private static final long POOL_SIZE = 64;
    private static final long SMALL_ALLOC_SIZE = 8;
    private static final long VERY_LARGE_ALLOC_SIZE = 1L << 10;

    @Test
    void invariants1LongArg() {
        assertThrows(IllegalArgumentException.class, () -> CarrierLocalArenaPools.create(-1));
        CarrierLocalArenaPools pool = CarrierLocalArenaPools.create(0);
        try (var arena = pool.take()) {
            // This should come from the underlying arena and not from recyclable memory
            assertDoesNotThrow(() -> arena.allocate(1));
            try (var arena2 = pool.take()) {
                assertDoesNotThrow(() -> arena.allocate(1));
            }
        }
    }

    @Test
    void invariants2LongArgs() {
        assertThrows(IllegalArgumentException.class, () -> CarrierLocalArenaPools.create(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> CarrierLocalArenaPools.create(1, -1));
        assertThrows(IllegalArgumentException.class, () -> CarrierLocalArenaPools.create(1, 3));
        CarrierLocalArenaPools pool = CarrierLocalArenaPools.create(0, 16);
        try (var arena = pool.take()) {
            // This should come from the underlying arena and not from recyclable memory
            assertDoesNotThrow(() -> arena.allocate(1));
            try (var arena2 = pool.take()) {
                assertDoesNotThrow(() -> arena.allocate(1));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("pools")
    void negativeAlloc(CarrierLocalArenaPools pool) {
        Consumer<Arena> action = arena ->
                assertThrows(IllegalArgumentException.class, () -> arena.allocate(-1));
        doInTwoStackedArenas(pool, action, action);
    }

    @ParameterizedTest
    @MethodSource("pools")
    void negativeAllocVt(CarrierLocalArenaPools pool) {
        VThreadRunner.run(() -> negativeAlloc(pool));
    }

    @ParameterizedTest
    @MethodSource("pools")
    void allocateConfinement(CarrierLocalArenaPools pool) {
        Consumer<Arena> allocateAction = arena ->
                assertThrows(WrongThreadException.class, () -> {
                    CompletableFuture<Arena> future = CompletableFuture.supplyAsync(pool::take);
                    var otherThreadArena = future.get();
                    otherThreadArena.allocate(SMALL_ALLOC_SIZE);
                    // Intentionally do not close the otherThreadArena here.
                });
        doInTwoStackedArenas(pool, allocateAction, allocateAction);
    }

    @ParameterizedTest
    @MethodSource("pools")
    void allocateConfinementVt(CarrierLocalArenaPools pool) {
        VThreadRunner.run(() -> allocateConfinement(pool));
    }

    @ParameterizedTest
    @MethodSource("pools")
    void closeConfinement(CarrierLocalArenaPools pool) {
        Consumer<Arena> closeAction = arena -> {
            // Do not use CompletableFuture here as it might accidentally run on the
            // same carrier thread as a virtual thread.
            AtomicReference<Arena> otherThreadArena = new AtomicReference<>();
            var thread = Thread.ofPlatform().start(() -> {
                otherThreadArena.set(pool.take());
            });
            try {
                thread.join();
            } catch (InterruptedException ie) {
                fail(ie);
            }
            assertThrows(WrongThreadException.class, otherThreadArena.get()::close);
        };
        doInTwoStackedArenas(pool, closeAction, closeAction);
    }

    @ParameterizedTest
    @MethodSource("pools")
    void closeConfinementVt(CarrierLocalArenaPools pool) {
        VThreadRunner.run(() -> closeConfinement(pool));
    }

    @ParameterizedTest
    @MethodSource("pools")
    void reuse(CarrierLocalArenaPools pool) {
        MemorySegment firstSegment;
        MemorySegment secondSegment;
        try (var arena = pool.take()) {
            firstSegment = arena.allocate(SMALL_ALLOC_SIZE);
        }
        try (var arena = pool.take()) {
            secondSegment = arena.allocate(SMALL_ALLOC_SIZE);
        }
        assertNotSame(firstSegment, secondSegment);
        assertNotSame(firstSegment.scope(), secondSegment.scope());
        assertEquals(firstSegment.address(), secondSegment.address());
        assertThrows(IllegalStateException.class, () -> firstSegment.get(JAVA_BYTE, 0));
        assertThrows(IllegalStateException.class, () -> secondSegment.get(JAVA_BYTE, 0));
    }

    @ParameterizedTest
    @MethodSource("pools")
    void reuseVt(CarrierLocalArenaPools pool) {
        VThreadRunner.run(() -> reuse(pool));
    }

    @ParameterizedTest
    @MethodSource("pools")
    void largeAlloc(CarrierLocalArenaPools pool) {
        try (var arena = pool.take()) {
            var segment = arena.allocate(VERY_LARGE_ALLOC_SIZE);
            assertEquals(VERY_LARGE_ALLOC_SIZE, segment.byteSize());
        }
    }

    @ParameterizedTest
    @MethodSource("pools")
    void largeAllocSizeVt(CarrierLocalArenaPools pool) {
        VThreadRunner.run(() -> largeAlloc(pool));
    }

    @Test
    void allocationSameAsPoolSize() {
        var pool = CarrierLocalArenaPools.create(4);
        long firstAddress;
        try (var arena = pool.take()) {
            var segment = arena.allocate(4);
            firstAddress = segment.address();
        }
        try (var arena = pool.take()) {
            var segment = arena.allocate(4 + 1);
            assertNotEquals(firstAddress, segment.address());
        }
        for (int i = 0; i < 10; i++) {
            try (var arena = pool.take()) {
                var segment = arena.allocate(4);
                assertEquals(firstAddress, segment.address());
                var segmentTwo = arena.allocate(4);
                assertNotEquals(firstAddress, segmentTwo.address());
            }
        }
    }

    @Test
    void allocationCaptureStateLayout() {
        var layout = Linker.Option.captureStateLayout();
        var pool = CarrierLocalArenaPools.create(layout);
        long firstAddress;
        try (var arena = pool.take()) {
            var segment = arena.allocate(layout);
            firstAddress = segment.address();
        }
        for (int i = 0; i < 10; i++) {
            try (var arena = pool.take()) {
                var segment = arena.allocate(layout);
                assertEquals(firstAddress, segment.address());
                var segmentTwo = arena.allocate(layout);
                assertNotEquals(firstAddress, segmentTwo.address());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("pools")
    void outOfOrderUse(CarrierLocalArenaPools pool) {
        Arena firstArena = pool.take();
        Arena secondArena = pool.take();
        firstArena.close();
        Arena thirdArena = pool.take();
        secondArena.close();
        thirdArena.close();
    }

    @ParameterizedTest
    @MethodSource("pools")
    void zeroing(CarrierLocalArenaPools pool) {
        try (var arena = pool.take()) {
            var seg = arena.allocate(SMALL_ALLOC_SIZE);
            seg.fill((byte) 1);
        }
        try (var arena = pool.take()) {
            var seg = arena.allocate(SMALL_ALLOC_SIZE);
            for (int i = 0; i < SMALL_ALLOC_SIZE; i++) {
                assertEquals((byte) 0, seg.get(JAVA_BYTE, i));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("pools")
    void zeroingVt(CarrierLocalArenaPools pool) {
        VThreadRunner.run(() -> zeroing(pool));
    }

    @ParameterizedTest
    @MethodSource("pools")
    void useAfterFree(CarrierLocalArenaPools pool) {
        MemorySegment segment = null;
        try (var arena = pool.take()){
            segment = arena.allocate(SMALL_ALLOC_SIZE);
        }
        final var closedSegment = segment;
        var e = assertThrows(IllegalStateException.class, () -> closedSegment.get(ValueLayout.JAVA_INT, 0));
        assertEquals("Already closed", e.getMessage());
    }

    @ParameterizedTest
    @MethodSource("pools")
    void toStringTest(CarrierLocalArenaPools pool) {
        assertTrue(pool.toString().contains("ArenaPool"));
        try (var arena = pool.take()) {
            assertTrue(arena.toString().contains("SlicingArena"));
        }
    }

    private static final VarHandle LONG_HANDLE = JAVA_LONG.varHandle();

    /**
     * The objective of this test is to try to provoke a situation where threads are
     * competing to use allocated pooled memory and then trying to make sure no thread
     * can see the same shared memory another thread is using.
     */
    @Test
    void stress() throws InterruptedException {

        // Encourage the VT ForkJoin pool to expand/contract so that VT:s will be allocated
        // on FJP threads that are later terminated.
        LongStream.range(0, Runtime.getRuntime().availableProcessors() * 10L)
                .parallel()
                // Using a CompletableFuture expands the FJP
                .forEach(_ -> Thread.ofVirtual().start(() -> {
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);
                    }
                }));

        // Just use one pool variant as testing here is fairly expensive.
        final CarrierLocalArenaPools pool = CarrierLocalArenaPools.create(POOL_SIZE);
        // Make sure it works for both virtual and platform threads (as they are handled differently)
        for (var threadBuilder : List.of(Thread.ofVirtual(), Thread.ofPlatform())) {
            final Thread[] threads = IntStream.range(0, 1024).mapToObj(_ ->
                    threadBuilder.start(() -> {
                        final long threadId = Thread.currentThread().threadId();
                        while (!Thread.interrupted()) {
                            for (int i = 0; i < 1_000_000; i++) {
                                try (Arena arena = pool.take()) {
                                    // Try to assert no two threads get allocated the same memory region.
                                    final MemorySegment segment = arena.allocate(JAVA_LONG);
                                    LONG_HANDLE.setVolatile(segment, 0L, threadId);
                                    assertEquals(threadId, (long) LONG_HANDLE.getVolatile(segment, 0L));
                                }
                            }
                            Thread.yield(); // make sure the driver thread gets a chance.
                        }
                    })).toArray(Thread[]::new);
            Thread.sleep(Duration.of(5, SECONDS));
            Arrays.stream(threads).forEach(
                    thread -> {
                        assertTrue(thread.isAlive());
                        thread.interrupt();
                    });
        }
    }

    /**
     * The objective with this test is to test the case when a virtual thread VT0 is
     * mounted on a carrier thread CT0; VT0 is then suspended; The pool of carrier threads
     * are then contracted; VT0 is then remounted on another carrier thread C1. VT0 runs
     * for a while when there is a lot of GC activity.
     * In other words, we are trying to establish that there is no use-after-free and that
     * the original arena, from which reusable segments are initially allocated from, is
     * not closed underneath.
     */
    @Test
    void movingVirtualThreadWithGc() throws InterruptedException {
        var pool = CarrierLocalArenaPools.create(POOL_SIZE);

        System.setProperty("jdk.virtualThreadScheduler.parallelism", "1");

        var done = new AtomicBoolean();
        var quiescent = new Object();

        var executor = Executors.newVirtualThreadPerTaskExecutor();

        executor.submit(() -> {
            while (!done.get()) {
                FileDescriptor.out.sync();
            }
            return null;
        });

        executor.submit(() -> {
            System.out.println("ALLOCATING = " + Thread.currentThread());
            try (Arena arena = pool.take()) {
                MemorySegment segment = arena.allocate(SMALL_ALLOC_SIZE);
                done.set(true);
                synchronized (quiescent) {
                    try {
                        quiescent.wait();
                    } catch (Throwable ex) {
                        throw new AssertionError(ex);
                    }
                }
                System.out.println("ACCESSING SEGMENT");

                for (int i = 0; i < SMALL_ALLOC_SIZE; i++) {
                    if (i % 100 == 0) {
                        System.gc();
                    }
                    segment.get(ValueLayout.JAVA_BYTE, i);
                }
            }
        });

        long count;
        do {
            Thread.sleep(1000);
            count = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t instanceof ForkJoinWorkerThread)
                    .count();
        } while (count > 0);

        System.out.println("FJP HAS CONTRACTED");

        synchronized (quiescent) {
            quiescent.notify();
        }

        executor.close();
    }

    // Factories and helper methods

    static Stream<CarrierLocalArenaPools> pools() {
        return Stream.of(
                CarrierLocalArenaPools.create(POOL_SIZE),
                CarrierLocalArenaPools.create(POOL_SIZE, 16),
                CarrierLocalArenaPools.create(MemoryLayout.sequenceLayout(POOL_SIZE, JAVA_BYTE))
        );
    }

    static void doInTwoStackedArenas(CarrierLocalArenaPools pool,
                                     Consumer<Arena> firstAction,
                                     Consumer<Arena> secondAction) {
        try (var firstArena = pool.take()) {
            firstAction.accept(firstArena);
            try (var secondArena = pool.take()) {
                secondAction.accept(secondArena);
            }
        }
    }

}