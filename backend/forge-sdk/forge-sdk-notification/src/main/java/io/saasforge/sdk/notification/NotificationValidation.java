package io.saasforge.sdk.notification;

final class NotificationValidation {
    private NotificationValidation() {}

    static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maximum + " characters");
        }
        return value;
    }

    static <T> T required(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
