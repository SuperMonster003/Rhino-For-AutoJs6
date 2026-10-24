/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import org.mozilla.javascript.ast.FunctionNode;

/**
 * AutoJs6 async: lowering of async functions into IR (ROADMAP P9). Until P9 lands, an async
 * function that reaches {@link IRFactory} is reported with a readable "not supported yet" error;
 * the syntax itself is already parsed by {@link AsyncParser}.
 */
final class AsyncLowering {

    private AsyncLowering() {}

    /** Rejects async functions, methods and arrow functions before they are transformed. */
    static void check(Parser parser, FunctionNode fn) {
        if (fn.isAsync()) {
            ClassLowering.reportNotSupported(parser, fn, "msg.async.not.supported");
        }
    }
}
