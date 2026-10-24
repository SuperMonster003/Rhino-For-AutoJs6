/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.ast;

import org.mozilla.javascript.Token;

/**
 * AutoJs6 class syntax: AST node for a private name such as {@code #x}. It appears as the key of a
 * class element, as the property of a member access ({@code o.#x}, {@code o?.#x}) and as the left
 * operand of a private brand check ({@code #x in o}). Node type is {@link Token#PRIVATE_NAME}.
 *
 * <p>The identifier is stored without the leading {@code #}; the node bounds include it.
 */
public class PrivateName extends Name {

    {
        type = Token.PRIVATE_NAME;
    }

    public PrivateName() {}

    public PrivateName(int pos, int len, String identifier) {
        super(pos, len, identifier);
    }

    @Override
    public String toSource(int depth) {
        return makeIndent(depth) + "#" + getIdentifier();
    }
}
