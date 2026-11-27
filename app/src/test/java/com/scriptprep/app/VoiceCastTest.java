package com.scriptprep.app;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class VoiceCastTest {
    @Test
    public void alternatesFemaleAndMaleVoicesWhenGendersAreUnknown() {
        List<String> roles = Arrays.asList("A", "B", "C");
        Map<String, VoiceCast.Part> cast = VoiceCast.automatic(roles, Collections.emptyList());

        assertEquals(0, cast.get("A").speaker);
        assertEquals(5, cast.get("B").speaker);
        assertEquals(1, cast.get("C").speaker);
    }

    @Test
    public void givesCharactersVoicesOfTheirGender() {
        List<String> roles = Arrays.asList("MERT", "ECE", "ALİ", "X");
        Map<String, String> genders = new HashMap<>();
        genders.put("MERT", "male");
        genders.put("ECE", "female");
        genders.put("ALİ", "male");
        Map<String, VoiceCast.Part> cast = VoiceCast.automatic(roles, Collections.emptyList(), genders);

        assertEquals(5, cast.get("MERT").speaker);
        assertEquals(0, cast.get("ECE").speaker);
        assertEquals(6, cast.get("ALİ").speaker);
        // Two men and one woman so far: the character of unknown gender gets the next female voice.
        assertEquals(1, cast.get("X").speaker);
        assertEquals("Charon", StudioVoices.voiceOf(cast.get("MERT")));
        assertEquals("Kore", StudioVoices.voiceOf(cast.get("ECE")));
    }
}
