package dev.dhanika.rouge.build;

/** A single block in a step plan: position + full block-state string. */
public record BlockEntry(int x, int y, int z, String block) {}
