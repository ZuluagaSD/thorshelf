package dev.thorshelf.app;

/** Compact UI text for recognized successful preparations; the saved status remains untouched. */
final class PreparationText {
    private PreparationText() { }

    static String summary(String status, boolean ready) {
        if (!ready || status == null) return status;
        if (status.startsWith("Saved ")) {
            return titleBeforeInstructions(status, "Saved ",
                    ". Use Install CIA in Azahar", ". Refresh Azahar");
        }
        if (status.startsWith("Ready for Azahar: ")) {
            return titleBeforeInstructions(status, "Ready for Azahar: ", ". In Azahar");
        }
        return status;
    }

    private static String titleBeforeInstructions(String status, String prefix, String... markers) {
        int boundary = -1;
        for (String marker : markers) boundary = Math.max(boundary, status.lastIndexOf(marker));
        if (boundary <= prefix.length()) return status;
        // Match the instruction boundary, not the first period: titles and versions can contain dots.
        String filename = status.substring(prefix.length(), boundary).trim();
        return filename.isEmpty() ? status : GameArtwork.displayTitle(filename);
    }
}
