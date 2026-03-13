package ded.entitythreading.world;

import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A server-safe equivalent of net.minecraft.world.chunk.EmptyChunk.
 * The vanilla EmptyChunk is annotated with @SideOnly(Side.CLIENT) and causes a ClassNotFoundException 
 * on dedicated servers when instantiated.
 */
public class SafeEmptyChunk extends Chunk {

    public SafeEmptyChunk(World worldIn, int x, int z) {
        super(worldIn, x, z);
    }

    @Override
    public boolean isAtLocation(int x, int z) {
        return x == this.x && z == this.z;
    }

    @Override
    public int getHeightValue(int x, int z) {
        return 0;
    }

    @Override
    public void generateSkylightMap() {}

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public IBlockState getBlockState(int x, int y, int z) {
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public int getLightFor(EnumSkyBlock skyBlock, BlockPos pos) {
        return skyBlock.defaultLightValue;
    }

    @Override
    public void setLightFor(EnumSkyBlock skyBlock, BlockPos pos, int value) {}

    @Override
    public int getLightSubtracted(BlockPos pos, int amount) {
        return 0;
    }

    @Override
    public void addEntity(Entity entityIn) {}

    @Override
    public void removeEntity(Entity entityIn) {}

    @Override
    public void removeEntityAtIndex(Entity entityIn, int index) {}

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return false;
    }

    @Nullable
    @Override
    public TileEntity getTileEntity(BlockPos pos, Chunk.EnumCreateEntityType type) {
        return null;
    }

    @Override
    public void addTileEntity(TileEntity tileEntityIn) {}

    @Override
    public void addTileEntity(BlockPos pos, TileEntity tileEntityIn) {}

    @Override
    public void removeTileEntity(BlockPos pos) {}

    @Override
    public void onLoad() {}

    @Override
    public void onUnload() {}

    @Override
    public void markDirty() {}

    @Override
    public void getEntitiesWithinAABBForEntity(@Nullable Entity entityIn, AxisAlignedBB aabb, List<Entity> listToFill, Predicate<? super Entity> filter) {}

    @Override
    public <T extends Entity> void getEntitiesOfTypeWithinAABB(Class<? extends T> entityClass, AxisAlignedBB aabb, List<T> listToFill, Predicate<? super T> filter) {}

    @Override
    public boolean needsSaving(boolean save) {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }
}
