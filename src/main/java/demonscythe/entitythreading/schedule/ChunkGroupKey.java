/*
 * Copyright (c) 2020  DemonScythe45
 *
 * This file is part of EntityThreading
 *
 *     EntityThreading is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation; version 3 only
 *
 *     EntityThreading is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with EntityThreading.  If not, see <https://www.gnu.org/licenses/>
 */

package demonscythe.entitythreading.schedule;

/**
 * Immutable key for O(1) HashMap lookup of entity groups by chunk coordinates and world dimension.
 * Uses a packed long for the chunk coords to avoid object allocation overhead.
 */
public final class ChunkGroupKey {
    private final int dimension;
    private final long packedChunkPos;

    public ChunkGroupKey(int dimension, int chunkX, int chunkZ) {
        this.dimension = dimension;
        this.packedChunkPos = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public int hashCode() {
        // Mix bits for better distribution in HashMap
        long h = packedChunkPos * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 30) ^ (dimension * 31);
        return (int) (h ^ (h >>> 16));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ChunkGroupKey)) return false;
        ChunkGroupKey other = (ChunkGroupKey) obj;
        return this.dimension == other.dimension && this.packedChunkPos == other.packedChunkPos;
    }

    @Override
    public String toString() {
        int chunkX = (int) (packedChunkPos >> 32);
        int chunkZ = (int) packedChunkPos;
        return "ChunkGroupKey{dim=" + dimension + ", cx=" + chunkX + ", cz=" + chunkZ + "}";
    }
}
