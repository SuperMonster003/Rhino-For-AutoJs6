/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.function.Consumer;

/**
 * AutoJs6 async: promises in an embedder with several script threads (ROADMAP T12, appendix C Q64
 * and Q65). Microtasks belong to the {@link Context} of a thread, and only a thread with an event
 * loop runs them, so two things ask {@link Context#forwardTask} whether they happen elsewhere:
 *
 * <ul>
 *   <li>the settlement of a promise by its resolving functions ({@code home} is null): a thread
 *       without an event loop, or a call outside any top call, would leave the reactions in a queue
 *       that nobody runs;
 *   <li>a Java reaction registered by {@link NativePromise#react}, that is, what {@code await}
 *       resumes ({@code home} is the Context that registered it): an async function stays on its
 *       thread when another thread settles what it waits for, as a coroutine of the embedder does.
 * </ul>
 *
 * The default {@link Context#forwardTask} forwards nothing, which leaves a single threaded embedder
 * as it was. Reactions of {@code then} stay where the promise settles.
 */
final class AsyncThreads {

    private AsyncThreads() {}

    /** Whether the settlement of {@code promise} was handed to another thread. */
    static boolean hop(Context cx, NativePromise promise, Consumer<Context> settle) {
        return cx.forwardTask(null, promise, () -> settle.accept(Context.getCurrentContext()));
    }

    /** {@code reaction}, which comes back to {@code home} when it is due on another Context. */
    static NativePromise.ReactionCall atHome(
            Context home, NativePromise promise, NativePromise.ReactionCall reaction) {
        return (cx, scope, value) -> {
            Runnable task = () -> reaction.invoke(Context.getCurrentContext(), scope, value);
            if (cx == home || !cx.forwardTask(home, promise, task)) {
                reaction.invoke(cx, scope, value);
            }
        };
    }
}
