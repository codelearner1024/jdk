/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.foreign;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.CarrierThread;
import jdk.internal.misc.TerminatingThreadLocal;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Reference;
import java.util.Objects;

public final class CarrierLocalArenaPools {

    @Stable
    private final TerminatingThreadLocal<LocalArenaPoolImpl> tl;

    private CarrierLocalArenaPools(long byteSize, long byteAlignment) {
        this.tl = new TerminatingThreadLocal<>() {

            private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

            // This method can be invoked by either a virtual thread, a platform thread
            // , or a carrier thread (e.g. ForkJoinPool-1-worker-1).
            @Override
            protected LocalArenaPoolImpl initialValue() {
                if (JLA.currentCarrierThread() instanceof CarrierThread) {
                    // Only a carrier thread that is an instance of `CarrierThread` can
                    // ever carry virtual threads. (Notably, a `CarrierThread` can also
                    // carry a platform thread.) This means a `CarrierThread` can carry
                    // any number of virtual threads, and they can be mounted/unmounted
                    // from the carrier thread at almost any time. Therefore, we must use
                    // stronger-than-plain semantics when dealing with mutual exclusion
                    // of thread local resources.
                    return new LocalArenaPoolImpl.OfCarrier(Arena.ofAuto(), byteSize, byteAlignment);
                } else {
                    // A carrier thread that is not an instance of `CarrierThread` can
                    // never carry a virtual thread. Because of this, only one thread will
                    // be mounted on such a carrier thread. Therefore, we can use plain
                    // memory semantics when dealing with mutual exclusion of thread local
                    // resources.
                    return new LocalArenaPoolImpl.OfPlatform(Arena.ofConfined(), byteSize, byteAlignment);
                }
            }

            // This method is never invoked by a virtual thread but can be invoked by
            // a platform thread or a carrier thread (e.g. ForkJoinPool-1-worker-1).
            // Note: the fork join pool can expand/contract dynamically
            // We do not use the method here as we are using an automatic arena.
            @Override
            protected void threadTerminated(LocalArenaPoolImpl pool) {
                pool.close();
            }
        };
    }

    @ForceInline
    public Arena take() {
        return tl.get()
                .take();
    }

    private static sealed abstract class LocalArenaPoolImpl {

        // Hold a reference so that the arena is not GC:ed before the thread dies.
        @Stable
        final Arena originalArena;
        @Stable
        final MemorySegment recyclableSegment;

        long sp;

        private LocalArenaPoolImpl(Arena originalArena,
                                   long byteSize,
                                   long byteAlignment) {
            this.originalArena = originalArena;
            this.recyclableSegment = originalArena.allocate(byteSize, byteAlignment);
        }

        public abstract Arena take();

        void close() {
            // Do not close an automatic arena
            if (((MemorySessionImpl) originalArena).isCloseable()) {
                originalArena.close();
            }
        }

        abstract void arenaClosed();

        /**
         * Thread safe implementation.
         */
        public static final class OfCarrier
                extends LocalArenaPoolImpl {

            static final int AVAILABLE = 0;
            static final int TAKEN = 1;

            // Unsafe allows earlier use in the init sequence and
            // better start and warmup properties.
            static final Unsafe UNSAFE = Unsafe.getUnsafe();
            static final long SEG_AVAIL_OFFSET =
                    UNSAFE.objectFieldOffset(OfCarrier.class, "segmentAvailability");

            // Used reflectively
            private int segmentAvailability;

            public OfCarrier(Arena originalArena,
                             long byteSize,
                             long byteAlignment) {
                super(originalArena, byteSize, byteAlignment);
            }

            // If we are on a carrier thread, we can only provide a single arena that
            // is using the recyclable segment. This is because a VT might be re-mounted
            // on another carrier thread at any time. Keeping track of the open/closed
            // arenas in such situation is slow.
            @ForceInline
            public Arena take() {
                final Arena delegate = Arena.ofConfined();
                return tryAcquireSegment()
                        ? new CarrierSlicingArena(originalArena, (ArenaImpl) delegate, recyclableSegment)
                        : delegate;
            }

            /**
             * {@return {@code true } if the segment was acquired for exclusive use, {@code
             * false} otherwise}
             */
            @ForceInline
            boolean tryAcquireSegment() {
                return UNSAFE.compareAndSetInt(this, SEG_AVAIL_OFFSET, AVAILABLE, TAKEN);
            }

            /**
             * Unconditionally releases the acquired segment if it was previously acquired,
             * otherwise this is a no-op.
             */
            @ForceInline
            void arenaClosed() {
                UNSAFE.putIntVolatile(this, SEG_AVAIL_OFFSET, AVAILABLE);
            }
        }

        /**
         * No need for thread-safe implementation here as a platform thread is exclusively
         * mounted on a particular carrier thread.
         */
        public static final class OfPlatform
                extends LocalArenaPoolImpl {

            private int openArenas;

            public OfPlatform(Arena originalArena,
                              long byteSize,
                              long byteAlignment) {
                super(originalArena, byteSize, byteAlignment);
            }

            // If we are on a non-carrier thread, several arenas can share the same
            // recyclable segment as the current thread will never move.
            @ForceInline
            public Arena take() {
                final Arena delegate = Arena.ofConfined();
                openArenas++;
                return new SlicingArena(originalArena, (ArenaImpl) delegate, recyclableSegment);
            }

            @ForceInline
            @Override
            void arenaClosed() {
                if (--openArenas == 0) {
                    sp = 0;
                }
            }
        }

        /**
         * A SlicingArena is similar to a {@linkplain SlicingAllocator} but if the backing
         * segment cannot be used for allocation, a fall-back arena is used instead. This
         * means allocation never fails due to the size and alignment of the backing
         * segment.
         */
        private sealed class SlicingArena implements Arena, NoInitSegmentAllocator {

            // In order to prevent use-after-free issues, we make sure the original arena
            // is reachable until the dying moments of a carrier thread AND remains
            // reachable whenever a carved out segment can be reached. The reason for
            // this is reinterpreted segments carved out from the original arena can be
            // used independently of the original arena but are freed when the
            // original arena is collected.
            //
            // To solve this, we also hold a reference to the original arena from which we
            // carved out the `segment`. This covers the case when a VT was remounted on
            // another CarrierThread and the original CarrierThread
            // died and therefore the original arena was not referenced anymore.
            @Stable
            private final Arena originalArena;
            @Stable
            private final ArenaImpl delegate;
            @Stable
            private final MemorySegment segment;

            @ForceInline
            private SlicingArena(Arena originalArena,
                                 ArenaImpl delegate,
                                 MemorySegment segment) {
                this.originalArena = originalArena;
                this.delegate = delegate;
                this.segment = segment;
            }

            @ForceInline
            @Override
            public MemorySegment.Scope scope() {
                return delegate.scope();
            }

            @ForceInline
            @Override
            public NativeMemorySegmentImpl allocate(long byteSize, long byteAlignment) {
                return NoInitSegmentAllocator.super.allocate(byteSize, byteAlignment);
            }

            @SuppressWarnings("restricted")
            @ForceInline
            public NativeMemorySegmentImpl allocateNoInit(long byteSize, long byteAlignment) {
                final long min = segment.address();
                final long start = Utils.alignUp(min + sp(), byteAlignment) - min;
                if (start + byteSize <= segment.byteSize()) {
                    Utils.checkAllocationSizeAndAlign(byteSize, byteAlignment);
                    final MemorySegment slice = segment.asSlice(start, byteSize, byteAlignment);

                    // We only need to do this once for VTs
                    if (sp() == 0 && !(scope() instanceof ConfinedSession)) {
                        // This prevents the automatic original arena from being collected before
                        // the SlicingArena is closed. This case might otherwise happen if a reference
                        // is held to a reusable segment and its arena is not closed.
                        ((MemorySessionImpl) scope())
                                .addCloseAction(new ReferenceHolder(originalArena));
                    }
                    sp(start + byteSize);
                    return fastReinterpret(delegate, (NativeMemorySegmentImpl) slice, byteSize);
                } else {
                    return delegate.allocateNoInit(byteSize, byteAlignment);
                }
            }

            @ForceInline
            @Override
            public void close() {
                delegate.close();
                // Intentionally do not releaseSegment() in a finally clause as
                // the segment still is in play if close() initially fails (e.g. is closed
                // from a non-owner thread). Later on the close() method might be
                // successfully re-invoked (e.g. from its owner thread).
                LocalArenaPoolImpl.this.arenaClosed();
            }

            long sp() {
                return LocalArenaPoolImpl.this.sp;
            }

            void sp(long sp) {
                LocalArenaPoolImpl.this.sp = sp;
            }

        }

        private final class CarrierSlicingArena extends SlicingArena {

            // Use a local stack pointer here as this state cannot be held in
            // a carrier thread local (VT may be remounted).
            private long sp;

            private CarrierSlicingArena(Arena originalArena,
                                        ArenaImpl delegate,
                                        MemorySegment segment) {
                super(originalArena, delegate, segment);
            }

            @Override
            long sp() {
                return this.sp;
            }

            @Override
            void sp(long sp) {
                this.sp = sp;
            }
        }

    }

    private record ReferenceHolder(Object ref) implements Runnable {
        @Override public void run() { Reference.reachabilityFence(ref);}
    }

    // Equivalent to but faster than:
    //     return (NativeMemorySegmentImpl) slice
    //             .reinterpret(byteSize, delegate, null); */
    @ForceInline
    static NativeMemorySegmentImpl fastReinterpret(ArenaImpl arena,
                                                   NativeMemorySegmentImpl segment,
                                                   long byteSize) {
        // We already know the segment:
        //  * is native
        //  * we have native access
        //  * there is no cleanup action
        //  * the segment is read/write
        return SegmentFactories.makeNativeSegmentUnchecked(segment.address(), byteSize,
                MemorySessionImpl.toMemorySession(arena), false, null);
    }

    public static CarrierLocalArenaPools create(long byteSize) {
        if (byteSize < 0) {
            throw new IllegalArgumentException();
        }
        return new CarrierLocalArenaPools(byteSize, 1L);
    }

    public static CarrierLocalArenaPools create(long byteSize,
                                                long byteAlignment) {
        Utils.checkAllocationSizeAndAlign(byteSize, byteAlignment);
        return new CarrierLocalArenaPools(byteSize, byteAlignment);
    }

    public static CarrierLocalArenaPools create(MemoryLayout layout) {
        Objects.requireNonNull(layout);
        return new CarrierLocalArenaPools(layout.byteSize(), layout.byteAlignment());
    }

}
