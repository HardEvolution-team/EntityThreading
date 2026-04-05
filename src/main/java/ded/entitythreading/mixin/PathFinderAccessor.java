package ded.entitythreading.mixin;

import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import javax.annotation.Nullable;

@Mixin(PathFinder.class)
public interface PathFinderAccessor {
    @Nullable
    @Invoker("findPath")
    Path invokeFindPath(IBlockAccess worldIn, EntityLiving entityIn, double x, double y, double z, float distance);
}
