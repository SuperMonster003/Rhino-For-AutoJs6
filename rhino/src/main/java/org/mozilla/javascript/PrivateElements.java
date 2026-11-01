/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * AutoJs6 class syntax: the [[PrivateElements]] of an object (ROADMAP T5), a table of its own next
 * to the property map. The private fields of the object map their {@link PrivateNameKey} to the
 * value; the private methods and accessors of a class are one brand key per object. Reflection
 * never sees the table, and adding to it works on frozen or non-extensible objects as well.
 *
 * <p>A {@link ScriptableObject} holds its table in a field ({@code privateElements}). The keys are
 * few and compared by identity, so a table is two small arrays: reading takes no lock, adding an
 * element copies the arrays under the owner's monitor (as {@code SlotMapOwner.ThreadedAccess} does
 * for the property map, without VarHandle) and publishes the new table. Other {@link Scriptable}
 * objects keep their table in a weak identity map; a Java object wrapper is keyed by the object it
 * wraps, so every wrapper of the same Java object has the same private elements, as {@code ===}
 * treats them as the same object.
 */
final class PrivateElements implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    final PrivateNameKey[] keys;
    final Object[] values;

    private PrivateElements(PrivateNameKey[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    /** The table of an object, or {@code null} when it has no private elements. */
    static PrivateElements of(Object object) {
        if (object instanceof ScriptableObject) {
            return ((ScriptableObject) object).privateElements;
        }
        return FOREIGN.get(foreignKey(object));
    }

    /** The index of the key, or -1. */
    int indexOf(PrivateNameKey key) {
        PrivateNameKey[] keys = this.keys;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == key) {
                return i;
            }
        }
        return -1;
    }

    static boolean has(Object object, PrivateNameKey key) {
        PrivateElements elements = of(object);
        return elements != null && elements.indexOf(key) >= 0;
    }

    /**
     * Adds a private element to the object; returns false when the object has it already, which
     * PrivateFieldAdd and PrivateMethodOrAccessorAdd report as a TypeError.
     */
    static boolean add(Scriptable object, PrivateNameKey key, Object value) {
        if (object instanceof ScriptableObject) {
            ScriptableObject owner = (ScriptableObject) object;
            synchronized (owner) {
                PrivateElements next = with(owner.privateElements, key, value);
                if (next == null) {
                    return false;
                }
                owner.privateElements = next;
                return true;
            }
        }
        return FOREIGN.add(foreignKey(object), key, value);
    }

    private static PrivateElements with(PrivateElements current, PrivateNameKey key, Object value) {
        if (current == null) {
            return new PrivateElements(new PrivateNameKey[] {key}, new Object[] {value});
        }
        if (current.indexOf(key) >= 0) {
            return null;
        }
        int n = current.keys.length;
        PrivateNameKey[] keys = new PrivateNameKey[n + 1];
        Object[] values = new Object[n + 1];
        System.arraycopy(current.keys, 0, keys, 0, n);
        System.arraycopy(current.values, 0, values, 0, n);
        keys[n] = key;
        values[n] = value;
        return new PrivateElements(keys, values);
    }

    private static Object foreignKey(Object object) {
        return object instanceof Wrapper ? ((Wrapper) object).unwrap() : object;
    }

    private static final ForeignTable FOREIGN = new ForeignTable();

    /** The tables of objects that are not ScriptableObjects, by identity and weakly. */
    private static final class ForeignTable {
        private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        private Entry[] buckets = new Entry[16];
        private int size;

        private static final class Entry extends WeakReference<Object> {
            final int hash;
            PrivateElements elements;
            Entry next;

            Entry(Object key, int hash, ReferenceQueue<Object> queue) {
                super(key, queue);
                this.hash = hash;
            }
        }

        synchronized PrivateElements get(Object key) {
            expunge();
            Entry e = find(key, System.identityHashCode(key));
            return e == null ? null : e.elements;
        }

        synchronized boolean add(Object key, PrivateNameKey name, Object value) {
            expunge();
            int hash = System.identityHashCode(key);
            Entry e = find(key, hash);
            if (e == null) {
                if (size >= buckets.length * 3 / 4) {
                    resize();
                }
                int i = hash & (buckets.length - 1);
                e = new Entry(key, hash, queue);
                e.next = buckets[i];
                buckets[i] = e;
                size++;
            }
            PrivateElements next = with(e.elements, name, value);
            if (next == null) {
                return false;
            }
            e.elements = next;
            return true;
        }

        private Entry find(Object key, int hash) {
            for (Entry e = buckets[hash & (buckets.length - 1)]; e != null; e = e.next) {
                if (e.hash == hash && e.get() == key) {
                    return e;
                }
            }
            return null;
        }

        private void resize() {
            Entry[] old = buckets;
            buckets = new Entry[old.length * 2];
            for (Entry head : old) {
                for (Entry e = head; e != null; ) {
                    Entry next = e.next;
                    int i = e.hash & (buckets.length - 1);
                    e.next = buckets[i];
                    buckets[i] = e;
                    e = next;
                }
            }
        }

        private void expunge() {
            for (Object ref; (ref = queue.poll()) != null; ) {
                Entry dead = (Entry) ref;
                int i = dead.hash & (buckets.length - 1);
                Entry prev = null;
                for (Entry e = buckets[i]; e != null; prev = e, e = e.next) {
                    if (e == dead) {
                        if (prev == null) {
                            buckets[i] = e.next;
                        } else {
                            prev.next = e.next;
                        }
                        size--;
                        break;
                    }
                }
            }
        }
    }

    @Serial
    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
        Context.checkDeserializationEnabled();
        stream.defaultReadObject();
    }

    @Serial
    private void writeObject(ObjectOutputStream stream) throws IOException {
        Context.checkSerializationEnabled();
        stream.defaultWriteObject();
    }

    @Serial
    private void readObjectNoData() throws InvalidObjectException {
        throw new InvalidObjectException("Missing serialized Rhino object data");
    }
}
