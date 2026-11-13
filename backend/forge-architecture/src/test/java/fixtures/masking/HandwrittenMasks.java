package fixtures.masking;

public class HandwrittenMasks {
    public static class Concat {
        public String maskPhone(String value) {
            return value.substring(0, 3) + "****" + value.substring(7);
        }
    }

    public static class Repeated {
        public String maskPhone(String value) {
            return value.substring(0, 3) + "*".repeat(4);
        }
    }

    public static class Replaced {
        public String maskPhone(String value) {
            return value.replace(value.substring(3), "****");
        }
    }

    public static class OrdinaryFormatting {
        public String format(String value) {
            return value.replace("-", "/") + " ".repeat(2);
        }

        public String maskFileGlob(String value) {
            return value.replace("?", "*");
        }
    }
}
