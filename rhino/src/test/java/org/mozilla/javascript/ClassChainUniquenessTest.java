/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AutoJs6 class syntax (ROADMAP P2, P3, AGENTS.md section 3 item 8): the fork members of the {@link
 * Icode} chain and of the {@link Node} property ids sit at the tail of the upstream chains and do
 * not reuse a value. {@code TokenUniquenessTest} covers {@code Token} and the features.
 */
class ClassChainUniquenessTest {

    /** Fork icodes, in chain order, below the upstream icodes. */
    private static final List<String> FORK_ICODES =
            List.of(
                    "CLASS_DEFINE",
                    "CLASS_ELEMENT",
                    "CLASS_CTOR_CHECK",
                    "CLASS_THIS",
                    "CLASS_TDZ",
                    "CLASS_SUPER_FUNC",
                    "CLASS_SUPER_CALL",
                    "CLASS_ITER_REST",
                    "CLASS_REQUIRE_OBJECT",
                    "CLASS_ITER_CLOSE",
                    "CLASS_PARAM_SCOPE",
                    "CLASS_PRIVATE_NAME",
                    "CLASS_INIT_FIELDS",
                    "CLASS_STATIC_INIT",
                    "SPREAD_CALL",
                    "SPREAD_CALL_ON_SUPER",
                    "SPREAD_NEW",
                    "SPREAD_CALLSPECIAL",
                    "SPREAD_REF_CALL",
                    "CLASS_DESTRUCTURE");

    /** Fork node properties, in chain order, after the upstream properties. */
    private static final List<String> FORK_PROPS = List.of("CLASS_PROP", "DEFAULT_NAME_PROP");

    private static Map<String, Integer> intConstants(Class<?> type) throws IllegalAccessException {
        Map<String, Integer> result = new HashMap<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)
                    && field.getType() == int.class) {
                field.setAccessible(true);
                result.put(field.getName(), field.getInt(null));
            }
        }
        return result;
    }

    @Test
    void icodesAreUniqueWithForkIcodesLast() throws IllegalAccessException {
        Map<String, Integer> icodes = intConstants(Icode.class);
        icodes.remove("MIN_ICODE");
        Map<Integer, String> seen = new HashMap<>();
        for (Map.Entry<String, Integer> entry : icodes.entrySet()) {
            String previous = seen.put(entry.getValue(), entry.getKey());
            assertNull(previous, "Icode." + entry.getKey() + " reuses the value of " + previous);
        }
        int first = icodes.get(FORK_ICODES.get(0));
        for (int i = 0; i < FORK_ICODES.size(); i++) {
            String name = FORK_ICODES.get(i);
            assertEquals(first - i, (int) icodes.get(name), name);
            assertEquals(name, ClassInterpreter.icodeName(first - i));
            assertTrue(Icode.validIcode(first - i), name);
        }
        assertEquals(first - FORK_ICODES.size() + 1, Icode.MIN_ICODE);
        for (Map.Entry<String, Integer> entry : icodes.entrySet()) {
            if (!FORK_ICODES.contains(entry.getKey())) {
                assertTrue(entry.getValue() > first, "Icode." + entry.getKey() + " follows fork");
            }
        }
    }

    @Test
    void nodePropertiesAreUniqueWithForkPropertiesLast() throws IllegalAccessException {
        Map<String, Integer> props = new HashMap<>();
        intConstants(Node.class)
                .forEach(
                        (name, value) -> {
                            if (name.endsWith("_PROP")
                                    && !name.equals("FIRST_PROP")
                                    && !name.equals("LAST_PROP")) {
                                props.put(name, value);
                            }
                        });
        Map<Integer, String> seen = new HashMap<>();
        for (Map.Entry<String, Integer> entry : props.entrySet()) {
            String previous = seen.put(entry.getValue(), entry.getKey());
            assertNull(previous, "Node." + entry.getKey() + " reuses the value of " + previous);
        }
        int first = props.get(FORK_PROPS.get(0));
        for (int i = 0; i < FORK_PROPS.size(); i++) {
            assertEquals(first + i, (int) props.get(FORK_PROPS.get(i)), FORK_PROPS.get(i));
        }
        assertEquals(first + FORK_PROPS.size() - 1, Node.LAST_PROP);
        for (Map.Entry<String, Integer> entry : props.entrySet()) {
            if (!FORK_PROPS.contains(entry.getKey())) {
                assertTrue(entry.getValue() < first, "Node." + entry.getKey() + " follows fork");
            }
        }
    }
}
