/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests.classsyntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Token;

/**
 * AutoJs6 class syntax: the numbering chains that the fork extends (AGENTS.md 7.1) stay unique
 * after upstream merges. Fork constants hang at the tail of each chain; when upstream appends to
 * the same chain, upstream goes first and the fork constants are renumbered after it.
 */
class TokenUniquenessTest {

    /** Fork tokens, in chain order, at the tail of {@link Token}. */
    private static final List<String> FORK_TOKENS =
            List.of("CLASS", "PRIVATE_NAME", "CLASS_ELEMENT", "AWAIT");

    /** Fork features, in chain order, at the tail of the {@code Context.FEATURE_*} chain. */
    private static final List<String> FORK_FEATURES =
            List.of("FEATURE_CLASS_SYNTAX", "FEATURE_ASYNC_SYNTAX");

    private static Map<String, Integer> intConstants(Class<?> type, String prefix)
            throws IllegalAccessException {
        Map<String, Integer> result = new HashMap<>();
        for (Field field : type.getFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)
                    && field.getType() == int.class
                    && field.getName().startsWith(prefix)
                    && !field.isAnnotationPresent(Deprecated.class)) { // misspelled aliases
                result.put(field.getName(), field.getInt(null));
            }
        }
        return result;
    }

    @Test
    void tokenValuesAreUnique() throws IllegalAccessException {
        Map<Integer, String> seen = new HashMap<>();
        for (Map.Entry<String, Integer> entry : intConstants(Token.class, "").entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("FIRST_") || name.startsWith("LAST_")) {
                continue; // range markers alias real tokens
            }
            String previous = seen.put(entry.getValue(), name);
            assertNull(previous, "Token." + name + " reuses the value of Token." + previous);
        }
    }

    @Test
    void forkTokensAreAtTheTail() throws IllegalAccessException {
        Map<String, Integer> tokens = intConstants(Token.class, "");
        int first = tokens.get(FORK_TOKENS.get(0));
        for (int i = 0; i < FORK_TOKENS.size(); i++) {
            assertEquals(first + i, (int) tokens.get(FORK_TOKENS.get(i)), FORK_TOKENS.get(i));
        }
        assertEquals(first + FORK_TOKENS.size(), Token.LAST_TOKEN);
        for (Map.Entry<String, Integer> entry : tokens.entrySet()) {
            if (!FORK_TOKENS.contains(entry.getKey()) && !entry.getKey().startsWith("LAST_")) {
                assertTrue(entry.getValue() < first, "Token." + entry.getKey() + " follows fork");
            }
        }
        for (String name : FORK_TOKENS) {
            assertEquals(name, Token.typeToName(tokens.get(name)));
        }
        assertEquals("class", Token.keywordToName(Token.CLASS));
        assertEquals("await", Token.keywordToName(Token.AWAIT));
    }

    @Test
    void featureValuesAreUniqueAndForkFeaturesLast() throws IllegalAccessException {
        Map<String, Integer> features = intConstants(Context.class, "FEATURE_");
        Map<Integer, String> seen = new HashMap<>();
        int max = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> entry : features.entrySet()) {
            String previous = seen.put(entry.getValue(), entry.getKey());
            assertNull(previous, entry.getKey() + " reuses the value of " + previous);
            if (!FORK_FEATURES.contains(entry.getKey())) {
                max = Math.max(max, entry.getValue());
            }
        }
        for (int i = 0; i < FORK_FEATURES.size(); i++) {
            assertEquals(max + 1 + i, (int) features.get(FORK_FEATURES.get(i)));
        }
    }

    @Test
    void forkFeaturesAreOnByDefault() {
        try (Context cx = Context.enter()) {
            assertTrue(cx.hasFeature(Context.FEATURE_CLASS_SYNTAX));
            assertTrue(cx.hasFeature(Context.FEATURE_ASYNC_SYNTAX));
        }
    }
}
