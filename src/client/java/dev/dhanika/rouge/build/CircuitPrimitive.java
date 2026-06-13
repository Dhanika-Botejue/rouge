package dev.dhanika.rouge.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A hand-authored, verified atomic circuit from the primitive library. */
public final class CircuitPrimitive {

    private final String id;
    private final String title;
    private final String category;
    private final String description;
    private final String footprint;
    private final List<String> aliases;
    private final List<StepPlan.Step> steps;

    private CircuitPrimitive(String id, String title, String category, String description,
                              String footprint, List<String> aliases, List<StepPlan.Step> steps) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.description = description;
        this.footprint = footprint;
        this.aliases = Collections.unmodifiableList(aliases);
        this.steps = Collections.unmodifiableList(steps);
    }

    public String id()          { return id; }
    public String title()       { return title; }
    public String category()    { return category; }
    public String description() { return description; }
    public String footprint()   { return footprint; }
    public List<String> aliases() { return aliases; }
    public List<StepPlan.Step> steps() { return steps; }

    /**
     * True when this entry ships hand-authored block data for every step. Only buildable
     * entries can be retrieved or composed verbatim; "blueprint" entries (no block data)
     * are description-only ground truth the AI generates coordinates from.
     */
    public boolean isBuildable() { return !steps.isEmpty(); }

    /** Wraps this primitive's authored steps as a standalone {@link StepPlan}. */
    public StepPlan toStepPlan() {
        return StepPlan.of(title, steps);
    }

    public static CircuitPrimitive fromJson(String raw) {
        JsonObject root = JsonParser.parseString(raw.trim()).getAsJsonObject();

        String id          = root.get("id").getAsString();
        String title       = root.get("title").getAsString();
        String category    = root.has("category")    ? root.get("category").getAsString()    : "misc";
        String description = root.has("description") ? root.get("description").getAsString() : "";
        String footprint   = root.has("footprint")   ? root.get("footprint").getAsString()   : "?";

        List<String> aliases = new ArrayList<>();
        if (root.has("aliases")) {
            for (JsonElement a : root.getAsJsonArray("aliases")) {
                aliases.add(a.getAsString());
            }
        }

        List<StepPlan.Step> steps = new ArrayList<>();
        if (root.has("steps")) {
            for (JsonElement el : root.getAsJsonArray("steps")) {
                JsonObject s = el.getAsJsonObject();
                String sTitle = s.has("title") ? s.get("title").getAsString() : "Step";
                String sExpl  = s.has("explanation") ? s.get("explanation").getAsString() : "";
                List<BlockEntry> blocks = new ArrayList<>();
                if (s.has("blocks")) {
                    for (JsonElement be : s.getAsJsonArray("blocks")) {
                        JsonObject b = be.getAsJsonObject();
                        blocks.add(new BlockEntry(
                                b.get("x").getAsInt(),
                                b.get("y").getAsInt(),
                                b.get("z").getAsInt(),
                                b.get("block").getAsString()
                        ));
                    }
                }
                steps.add(new StepPlan.Step(sTitle, sExpl, Collections.unmodifiableList(blocks)));
            }
        }
        return new CircuitPrimitive(id, title, category, description, footprint, aliases, steps);
    }

    /**
     * One-line summary for the AI context injection. Marks whether the entry is
     * {@code buildable} (has authored block data, can be {@code use}d/composed verbatim)
     * or a {@code blueprint} (description-only ground truth), and lists any aliases so the
     * model can match loose phrasing to the right id.
     */
    public String summaryLine() {
        String kind = isBuildable() ? "buildable" : "blueprint";
        StringBuilder sb = new StringBuilder(id)
                .append(" [").append(kind).append(", ").append(category)
                .append(", ").append(footprint).append("]");
        if (!aliases.isEmpty()) {
            sb.append(" aka ").append(String.join(", ", aliases));
        }
        sb.append(" — ").append(description);
        return sb.toString();
    }
}
