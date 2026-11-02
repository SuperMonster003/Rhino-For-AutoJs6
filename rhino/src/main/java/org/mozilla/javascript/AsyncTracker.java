/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

/**
 * AutoJs6 async: the async functions and async generators of a {@link Context} suspended at an
 * {@code await}, oldest first (ROADMAP T7, appendix C Q7). An embedder asks when a script ends
 * because nothing is left to run. It holds locations only, never a suspended frame, so a function
 * whose promise never settles can still be collected.
 */
public final class AsyncTracker {

    /** Where a function is suspended. */
    public static class Pending {
        private final int lineNumber;
        String sourceName;
        Pending previous;
        Pending next;

        Pending(int lineNumber) {
            this.lineNumber = lineNumber;
        }

        /** The name of the script, as given when it was compiled. */
        public String sourceName() {
            return sourceName;
        }

        /** The line of the {@code await}. */
        public int lineNumber() {
            return lineNumber;
        }
    }

    private Pending first;
    private Pending last;
    private int count;

    AsyncTracker() {}

    /** Returns the tracker of a Context. */
    public static AsyncTracker of(Context cx) {
        return cx.asyncTracker;
    }

    /** Returns how many functions of the Context are suspended at an {@code await}. */
    public synchronized int pendingCount() {
        return count;
    }

    /** Returns the function suspended the longest, or null when there is none. */
    public synchronized Pending firstPending() {
        return first;
    }

    synchronized void add(Pending pending) {
        pending.previous = last;
        if (last == null) {
            first = pending;
        } else {
            last.next = pending;
        }
        last = pending;
        count++;
    }

    synchronized void remove(Pending pending) {
        if (pending.previous == null) {
            first = pending.next;
        } else {
            pending.previous.next = pending.next;
        }
        if (pending.next == null) {
            last = pending.previous;
        } else {
            pending.next.previous = pending.previous;
        }
        pending.previous = null;
        pending.next = null;
        count--;
    }
}
