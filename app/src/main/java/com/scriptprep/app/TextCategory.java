package com.scriptprep.app;

enum TextCategory {
    ROLE_REHEARSAL,
    SPEECH,
    POEM_OR_MONOLOGUE;

    static TextCategory fromSavedValue(String value) {
        for (TextCategory category : values()) {
            if (category.name().equals(value)) {
                return category;
            }
        }
        return ROLE_REHEARSAL;
    }

    boolean hasRoles() {
        return this == ROLE_REHEARSAL;
    }

    Scene parse(String source, String narrator) {
        return hasRoles() ? Scene.parse(source)
            : Scene.parseSolo(source, narrator, this == POEM_OR_MONOLOGUE);
    }
}
