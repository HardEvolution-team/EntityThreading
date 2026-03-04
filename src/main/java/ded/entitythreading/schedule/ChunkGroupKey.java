

package ded.entitythreading.schedule;

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
