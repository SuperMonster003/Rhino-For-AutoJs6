/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6: whether iterating a plain array of a realm can be observed, kept up to date by watching
 * the properties it depends on (ROADMAP P6.12), so that an array pattern ({@link
 * ArrayDestructuring}) needs no lookups per destructuring. Not related to classes.
 *
 * <p>Once {@link ArraySpread#iterationRealm} and {@link ArraySpread#mayHaveReturn} found the
 * built-ins of a realm, the slot maps of {@code Array.prototype}, {@code %ArrayIteratorPrototype%}
 * and {@code Object.prototype} are replaced by {@link Watched} maps holding copies of their slots,
 * which invalidate the guard whenever {@code Symbol.iterator}, {@code next} or {@code return} of
 * these objects is created, redefined or deleted, or a map outgrows itself. A plain write to an
 * existing property changes the value of its slot in place, so {@link #holds} compares the values
 * of the two slots it depends on; it also compares the maps and the prototypes of the other two
 * objects. An invalid guard stays invalid, and the realm goes back to the lookups. Maps that are
 * thread safe are left alone.
 */
final class ArrayIterationGuard {

    /** The realm of the built-in {@code values}, which would create the iterator. */
    final TopLevel realm;

    private final Slot<?> values;
    private final Object valuesFunction;
    private final NativeArrayIterator iteratorProto;
    private final SlotMap<?> iteratorMap;
    private final Slot<?> next;
    private final Object nextFunction;
    private final NativeObject objectProto;
    private final SlotMap<?> objectMap;
    private boolean valid = true;

    private ArrayIterationGuard(
            TopLevel realm,
            NativeArray arrayProto,
            NativeArrayIterator iteratorProto,
            NativeObject objectProto) {
        this.realm = realm;
        this.iteratorProto = iteratorProto;
        this.objectProto = objectProto;
        objectMap = watch(objectProto, ES6Iterator.RETURN_METHOD, null);
        iteratorMap = watch(iteratorProto, ES6Iterator.NEXT_METHOD, ES6Iterator.RETURN_METHOD);
        // Last: the guard is found through the map of Array.prototype.
        SlotMap<Scriptable> arrayMap = watch(arrayProto, SymbolKey.ITERATOR, null);
        values = arrayMap.query(SymbolKey.ITERATOR, 0);
        valuesFunction = values.value;
        next = iteratorMap.query(ES6Iterator.NEXT_METHOD, 0);
        nextFunction = next.value;
    }

    private SlotMap<Scriptable> watch(ScriptableObject object, Object key1, Object key2) {
        Watched<Scriptable> map = new Watched<>(object, object.getMap(), this, key1, key2);
        object.setMap(map);
        return map;
    }

    /** The guard of a plain array whose prototype is watched, when it holds; else null. */
    static ArrayIterationGuard of(NativeArray array) {
        Scriptable proto = array.getPrototype();
        if (proto == null || proto.getClass() != NativeArray.class) {
            return null;
        }
        SlotMap<Scriptable> map = ((NativeArray) proto).getMap();
        if (!(map instanceof Watched)) {
            return null;
        }
        ArrayIterationGuard guard = ((Watched<?>) map).guard;
        return guard.holds() ? guard : null;
    }

    /**
     * Whether nothing the iteration of a plain array depends on changed: the built-in {@code
     * values} and {@code next}, no {@code return} on {@code %ArrayIteratorPrototype%} or on {@code
     * Object.prototype}, its prototype, which has none.
     */
    boolean holds() {
        return valid
                && values.value == valuesFunction
                && next.value == nextFunction
                && iteratorProto.getMap() == iteratorMap
                && objectProto.getMap() == objectMap
                && iteratorProto.getPrototype() == objectProto
                && objectProto.getPrototype() == null;
    }

    /**
     * Watches the built-ins of the realm of a plain array that {@link ArraySpread#iterationRealm}
     * returned, without a {@code return} ({@link ArraySpread#mayHaveReturn}): once per realm.
     */
    static void install(NativeArray array, TopLevel realm) {
        NativeArray arrayProto = (NativeArray) array.getPrototype();
        Object iteratorProto = realm.getAssociatedValue("ArrayIterator");
        Scriptable objectProto = realm.getBuiltinPrototype(TopLevel.Builtins.Object);
        if (arrayProto.getMap() instanceof Watched
                || !watchable(arrayProto.getMap())
                || !watchable(((NativeArrayIterator) iteratorProto).getMap())
                || !watchable(((NativeObject) objectProto).getMap())) {
            return;
        }
        new ArrayIterationGuard(
                realm, arrayProto, (NativeArrayIterator) iteratorProto, (NativeObject) objectProto);
    }

    private static boolean watchable(SlotMap<?> map) {
        return map.getClass() == EmbeddedSlotMap.class
                || map.getClass() == ImmutableSmallSlotMap.class;
    }

    /** A slot map that invalidates its guard when one of up to two keys changes. */
    static final class Watched<T extends PropHolder<T>> extends EmbeddedSlotMap<T> {
        final ArrayIterationGuard guard;
        private final Object key1;
        private final Object key2;

        Watched(
                SlotMapOwner<T> owner,
                SlotMap<T> map,
                ArrayIterationGuard guard,
                Object key1,
                Object key2) {
            super(Math.max(map.size() * 2, 4));
            this.guard = guard;
            this.key1 = key1;
            this.key2 = key2;
            // Copies, so that a reader of the old map is not disturbed.
            for (Slot<T> slot : map) {
                super.add(owner, slot.copySlot());
            }
        }

        private void watch(Object key) {
            if (key != null && (key.equals(key1) || key.equals(key2))) {
                guard.valid = false;
            }
        }

        @Override
        public Slot<T> modify(SlotMapOwner<T> owner, Object key, int index, int attributes) {
            watch(key);
            return super.modify(owner, key, index, attributes);
        }

        @Override
        public <S extends Slot<T>> S compute(
                SlotMapOwner<T> owner,
                CompoundOperationMap<T> compoundOp,
                Object key,
                int index,
                SlotComputer<S, T> c) {
            watch(key);
            return super.compute(owner, compoundOp, key, index, c);
        }

        @Override
        public void add(SlotMapOwner<T> owner, Slot<T> newSlot) {
            watch(newSlot.getName());
            super.add(owner, newSlot);
        }

        @Override
        protected void promoteMap(SlotMapOwner<T> owner, Slot<T> newSlot) {
            guard.valid = false;
            super.promoteMap(owner, newSlot);
        }
    }
}
