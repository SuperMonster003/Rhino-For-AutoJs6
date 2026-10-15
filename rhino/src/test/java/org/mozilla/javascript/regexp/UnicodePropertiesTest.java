package org.mozilla.javascript.regexp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Property lookup of {@code \p{...}} and its Android compatibility: Android supports named groups
 * in java.util.regex from API 26 only, so on API 24 / 25 a lookup through {@code
 * Matcher.group(String)} threw UnsupportedOperationException for every Unicode property escape.
 */
class UnicodePropertiesTest {

    private static int encoded(byte property, int value) {
        return ((property & 0xFF) << 8) | (value & 0xFF);
    }

    @Test
    void lookupAcceptsNamesValuesAndAliases() {
        int letter = encoded(UnicodeProperties.GENERAL_CATEGORY, UnicodeProperties.LETTER);
        assertEquals(letter, UnicodeProperties.lookup("L"));
        assertEquals(letter, UnicodeProperties.lookup("Letter"));
        assertEquals(letter, UnicodeProperties.lookup("gc=L"));
        assertEquals(letter, UnicodeProperties.lookup("General_Category=Letter"));
        assertEquals(
                encoded(UnicodeProperties.GENERAL_CATEGORY, UnicodeProperties.UPPERCASE_LETTER),
                UnicodeProperties.lookup("Lu"));
        assertEquals(
                encoded(UnicodeProperties.ALPHABETIC, UnicodeProperties.TRUE),
                UnicodeProperties.lookup("Alpha"));
        assertEquals(
                encoded(UnicodeProperties.SCRIPT, Character.UnicodeScript.HAN.ordinal()),
                UnicodeProperties.lookup("Script=Han"));
        assertEquals(
                encoded(UnicodeProperties.SCRIPT, Character.UnicodeScript.GREEK.ordinal()),
                UnicodeProperties.lookup("sc=Greek"));
    }

    @Test
    void lookupRejectsEverythingElse() {
        String[] rejected = {
            "",
            "Foo",
            "gc=Foo",
            "Script=NoSuchScript",
            "Lu=x",
            "Alpha=Yes",
            "Script",
            "gc",
            "L=",
            "=L",
            "a b",
            "L;",
            "Script=Han=Han"
        };
        for (String text : rejected) {
            assertEquals(-1, UnicodeProperties.lookup(text), text);
        }
        assertEquals(-1, UnicodeProperties.lookup(null));
    }

    @Test
    void lookupNeverUsesNamedGroupsWhichAndroidLacksBeforeApi26() throws IOException {
        assertFalse(referencesMatcherGroupByName(UnicodeProperties.class));
        assertFalse(referencesMatcherGroupByName(NativeRegExp.class));
    }

    /** Whether the class file calls {@code Matcher.group(String)}, read from its constant pool. */
    private static boolean referencesMatcherGroupByName(Class<?> type) throws IOException {
        try (InputStream stream = type.getResourceAsStream(type.getSimpleName() + ".class");
                DataInputStream in = new DataInputStream(stream)) {
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[][] refs = new int[count][];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7, 8, 16, 19, 20 -> refs[i] = new int[] {tag, in.readUnsignedShort()};
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 9, 10, 11, 12, 17, 18 ->
                            refs[i] =
                                    new int[] {tag, in.readUnsignedShort(), in.readUnsignedShort()};
                    default -> throw new IOException("Unknown constant pool tag " + tag);
                }
            }
            for (int[] ref : refs) {
                if (ref == null || ref[0] != 10) {
                    continue;
                }
                String owner = utf8[refs[ref[1]][1]];
                int[] nameAndType = refs[ref[2]];
                if (owner.equals("java/util/regex/Matcher")
                        && utf8[nameAndType[1]].equals("group")
                        && utf8[nameAndType[2]].equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                    return true;
                }
            }
            return false;
        }
    }
}
