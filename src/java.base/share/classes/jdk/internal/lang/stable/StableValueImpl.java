/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.lang.stable;

import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * The implementation of StableValue.
 *
 * @implNote This implementation can be used early in the boot sequence as it does not
 *           rely on reflection, MethodHandles, Streams etc.
 *
 * @param <T> type of the underlying data
 */
public final class StableValueImpl<T> implements StableValue<T> {

    // Unsafe allows StableValue to be used early in the boot sequence
    static final Unsafe UNSAFE = Unsafe.getUnsafe();

    // Unsafe offsets for direct field access
    private static final long UNDERLYING_DATA_OFFSET =
            UNSAFE.objectFieldOffset(StableValueImpl.class, "underlyingData");

    // Generally, fields annotated with `@Stable` are accessed by the JVM using special
    // memory semantics rules (see `parse.hpp` and `parse(1|2|3).cpp`).
    //
    // This field is used directly and via Unsafe using explicit memory semantics.
    //
    // | Value          |  Meaning      |
    // | -------------- |  ------------ |
    // | null           |  Unset        |
    // | nullSentinel() |  Set(null)    |
    // | other          |  Set(other)   |
    //
    @Stable
    private volatile Object underlyingData;

    // Only allow creation via the factory `StableValueImpl::newInstance`
    private StableValueImpl() {}

    @ForceInline
    @Override
    public boolean trySet(T underlyingData) {
        if (this.underlyingData != null) {
            return false;
        }
        // Mutual exclusion is required here as `computeIfUnset` might also
        // attempt to modify the `wrappedValue`
        synchronized (this) {
            return wrapAndCas(underlyingData);
        }
    }

    @ForceInline
    @Override
    public void setOrThrow(T underlyingData) {
        if (!trySet(underlyingData)) {
            throw new IllegalStateException("Cannot set the underlying data to " + underlyingData +
                    " because the underlying data is already set: " + this);
        }
    }

    @ForceInline
    @Override
    public T orElseThrow() {
        final Object t = underlyingData;
        if (t == null) {
            throw new NoSuchElementException("No underlying data set");
        }
        return unwrap(t);
    }

    @ForceInline
    @Override
    public T orElse(T other) {
        final Object t = underlyingData;
        return (t == null) ? other : unwrap(t);
    }

    @ForceInline
    @Override
    public boolean isSet() {
        return underlyingData != null;
    }

    @ForceInline
    @Override
    public T computeIfUnset(Supplier<? extends T> supplier) {
        final Object t = underlyingData;
        return (t == null) ? computeIfUnsetSlowPath(supplier) : unwrap(t);
    }

    @DontInline
    private synchronized T computeIfUnsetSlowPath(Supplier<? extends T> supplier) {
        final Object t = underlyingData;
        if (t == null) {
            final T newValue = supplier.get();
            // The mutex is reentrant so we need to check if the value was actually set.
            return wrapAndCas(newValue) ? newValue : orElseThrow();
        }
        return unwrap(t);
    }

    // The methods equals() and hashCode() should be based on identity (defaults from Object)

    @Override
    public String toString() {
        final Object t = underlyingData;
        return t == this
                ? "(this StableValue)"
                : "StableValue" + renderWrapped(t);
    }

    // Internal methods shared with other internal classes

    @ForceInline
    public Object wrappedValue() {
        return underlyingData;
    }

    static String renderWrapped(Object t) {
        return (t == null) ? ".unset" : "[" + unwrap(t) + "]";
    }

    // Private methods

    @ForceInline
    private boolean wrapAndCas(Object value) {
        // This upholds the invariant, a `@Stable` field is written to at most once
        return UNSAFE.compareAndSetReference(this, UNDERLYING_DATA_OFFSET, null, wrap(value));
    }

    // Used to indicate a holder value is `null` (see field `value` below)
    // A wrapper method `nullSentinel()` is used for generic type conversion.
    private static final Object NULL_SENTINEL = new Object();

    // Wraps `null` values into a sentinel value
    @ForceInline
    private static <T> T wrap(T t) {
        return (t == null) ? nullSentinel() : t;
    }

    // Unwraps null sentinel values into `null`
    @SuppressWarnings("unchecked")
    @ForceInline
    private static <T> T unwrap(Object t) {
        return t != nullSentinel() ? (T) t : null;
    }

    @SuppressWarnings("unchecked")
    @ForceInline
    private static <T> T nullSentinel() {
        return (T) NULL_SENTINEL;
    }

    // Factory

    static <T> StableValueImpl<T> newInstance() {
        return new StableValueImpl<>();
    }

}
