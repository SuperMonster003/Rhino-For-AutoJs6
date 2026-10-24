/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The translated message bundles of this fork stay usable after upstream adds or changes keys. */
class MessageTranslationsTest {

    private static final String RESOURCES = "/org/mozilla/javascript/resources/";
    private static final Pattern KEY_LINE =
            Pattern.compile("^(msg\\.[^\\s=:]+)\\s*[=:]", Pattern.MULTILINE);
    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)");
    private static final List<String> UINT8ARRAY_KEYS =
            List.of(
                    "msg.not.an.object",
                    "msg.bad.alphabet",
                    "msg.bad.lastchunkhandling",
                    "msg.invalid.base64",
                    "msg.hex.not.even",
                    "msg.not.base64",
                    "msg.bad.hex");

    static Stream<String> languages() {
        return Stream.of("ar", "es", "fr", "ja", "ko", "ru", "zh", "zh_HK", "zh_TW");
    }

    @ParameterizedTest
    @MethodSource("languages")
    void keysAreUnique(String language) throws IOException {
        Set<String> keys = new HashSet<>();
        Matcher matcher = KEY_LINE.matcher(read("Messages_" + language + ".properties"));
        while (matcher.find()) {
            assertTrue(keys.add(matcher.group(1)), language + ": duplicate " + matcher.group(1));
        }
    }

    @ParameterizedTest
    @MethodSource("languages")
    void translationsFormatTheArgumentsOfTheBaseMessage(String language) throws IOException {
        Properties base = load("Messages.properties");
        Properties translated = load("Messages_" + language + ".properties");
        for (String key : translated.stringPropertyNames()) {
            if (!key.startsWith("msg.") || !base.containsKey(key)) {
                continue;
            }
            assertEquals(
                    formattedArguments(base.getProperty(key)),
                    formattedArguments(translated.getProperty(key)),
                    language + ": " + key);
        }
    }

    @ParameterizedTest
    @MethodSource("languages")
    void uint8ArrayMessagesAreTranslated(String language) throws IOException {
        Properties base = load("Messages.properties");
        Properties translated = load("Messages_" + language + ".properties");
        for (String key : UINT8ARRAY_KEYS) {
            assertNotNull(base.getProperty(key), key);
            String message = translated.getProperty(key);
            assertNotNull(message, language + ": " + key);
            assertTrue(!message.equals(base.getProperty(key)), language + ": " + key);
        }
    }

    /** The indices of the arguments that survive formatting, so a stray quote cannot hide one. */
    private static Set<Integer> formattedArguments(String pattern) {
        Matcher matcher = ARGUMENT.matcher(pattern);
        int count = 0;
        while (matcher.find()) {
            count = Math.max(count, Integer.parseInt(matcher.group(1)) + 1);
        }
        Object[] args = new Object[count];
        for (int i = 0; i < count; i++) {
            args[i] = "<arg" + i + ">";
        }
        String formatted = new MessageFormat(pattern, Locale.ROOT).format(args);
        Set<Integer> result = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            if (formatted.contains("<arg" + i + ">")) {
                result.add(i);
            }
        }
        return result;
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(read(name)));
        return properties;
    }

    private static String read(String name) throws IOException {
        try (InputStream in = MessageTranslationsTest.class.getResourceAsStream(RESOURCES + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
