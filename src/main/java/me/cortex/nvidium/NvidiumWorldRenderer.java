package me.cortex.nvidium;

import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.managers.AsyncOcclusionTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.UploadingBufferStream;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX;

public class NvidiumWorldRenderer {
    static {
        System.err.println("NWR-STATIC: class init start");
    }
    private static final RenderDevice device = new RenderDevice();

    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;
    private final RenderPipeline renderPipeline;

    private final AsyncOcclusionTracker asyncChunkTracker;

    //Max memory that the gpu can use to store geometry in mb
    private long max_geometry_memory;
    private long last_sample_time;

    //Note: the reason that asyncChunkTracker is passed in as an already constructed object is cause of the amount of argmuents it takes to construct it
    public NvidiumWorldRenderer(AsyncOcclusionTracker asyncChunkTracker) {
        System.err.println("NWR-CTOR: entered ctor");
        System.err.println("NWR-CTOR: before update_allowed_memory");
        int frames = SodiumClientMod.options().advanced.cpuRenderAheadLimit+1;
        //32 mb upload buffer
        this.uploadStream = new UploadingBufferStream(device, 32000000);
        //8 mb download buffer
        this.downloadStream = new DownloadTaskStream(device, frames, 8000000);

        update_allowed_memory();
        System.err.println("NWR-CTOR: after update_allowed_memory");
        //this.sectionManager = new SectionManager(device, max_geometry_memory*1024*1024, uploadStream, 150, 24, CompactChunkVertex.STRIDE);
        this.sectionManager = new SectionManager(device, max_geometry_memory*1024*1024, uploadStream, Nvidium.config.use_sodium_vertex_format ? ChunkMeshFormats.COMPACT.getVertexFormat().getStride() : NvidiumCompactChunkVertex.STRIDE, this);
        System.err.println("NWR: before RenderPipeline ctor");
        this.renderPipeline = new RenderPipeline(device, uploadStream, downloadStream, sectionManager);
        System.err.println("NWR: after RenderPipeline ctor");


        System.err.println("NWR: before asyncChunkTracker assign");
        System.err.println("NWR-CTOR: before asyncChunkTracker assign");
        this.asyncChunkTracker = asyncChunkTracker;
        System.err.println("NWR-CTOR: after asyncChunkTracker assign");
        System.err.println("NWR: ctor done");
    }

    public void enqueueRegionSort(int regionId) {
        this.renderPipeline.enqueueRegionSort(regionId);
    }

    public void delete() {
        uploadStream.delete();
        downloadStream.delete();
        renderPipeline.delete();
        if (asyncChunkTracker != null) {
            asyncChunkTracker.delete();
        }

        sectionManager.destroy();
    }

    public void reloadShaders() {
        renderPipeline.reloadShaders();
    }

    public void renderFrame(TerrainRenderPass pass, Viewport viewport, FogParameters fogParameters, ChunkRenderMatrices matrices, double x, double y, double z, GpuSampler terrainSampler) {
        System.err.println("NWR-RENDERFRAME: enter pass=" + pass);

        renderPipeline.renderFrame(pass, viewport, fogParameters, matrices, x, y, z, terrainSampler);

        System.err.println("NWR-RENDERFRAME: after renderPipeline.renderFrame");

        int nvidiumEvictGuard = 0;
        int nvidiumPrevUsedMb = sectionManager.terrainAreana.getUsedMB();
        while (sectionManager.terrainAreana.getUsedMB() > (max_geometry_memory - 100)) {
            System.err.println("NWR-RENDERFRAME: before removeARegion usedMB=" + sectionManager.terrainAreana.getUsedMB());
            renderPipeline.removeARegion();
            int nvidiumNowUsedMb = sectionManager.terrainAreana.getUsedMB();
            System.err.println("NWR-RENDERFRAME: after removeARegion usedMB=" + nvidiumNowUsedMb);

            if (nvidiumNowUsedMb >= nvidiumPrevUsedMb) {
                Nvidium.LOGGER.warn("Nvidium eviction loop made no progress: usedMB={} limitMB={}", nvidiumNowUsedMb, max_geometry_memory);
                break;
            }
            nvidiumPrevUsedMb = nvidiumNowUsedMb;
            if (++nvidiumEvictGuard > 10000) {
                Nvidium.LOGGER.warn("Nvidium eviction loop bailed out after too many iterations: usedMB={} limitMB={}", nvidiumNowUsedMb, max_geometry_memory);
                break;
            }
        }

        if (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER && (System.currentTimeMillis() - last_sample_time) > 60000) {
            System.err.println("NWR-RENDERFRAME: before update_allowed_memory periodic");
            last_sample_time = System.currentTimeMillis();
            update_allowed_memory();
            System.err.println("NWR-RENDERFRAME: after update_allowed_memory periodic");
        }

        System.err.println("NWR-RENDERFRAME: exit pass=" + pass);
    }

    public void renderTranslucent(TerrainRenderPass pass, GpuSampler terrainSampler) {
        System.err.println("NWR-TRANSLUCENT: enter pass=" + pass);
        this.renderPipeline.renderTranslucent(pass, terrainSampler);
        System.err.println("NWR-TRANSLUCENT: exit pass=" + pass);
    }

    public void deleteSection(RenderSection section) {
        this.sectionManager.deleteSection(section);
    }

    public void uploadBuildResult(BuilderTaskOutput buildOutput) {
        if (buildOutput instanceof ChunkBuildOutput chunkBuildOutput) {
            this.sectionManager.uploadChunkBuildResult(chunkBuildOutput);
        }
        if (buildOutput instanceof ChunkSortOutput chunkSortOutput &&
                !chunkSortOutput.isReusingUploadedIndexData() &&
                Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.SODIUM) {
            this.sectionManager.uploadChunkSort(chunkSortOutput);
        }
    }

    public void addDebugInfo(ArrayList<String> debugInfo) {
        debugInfo.add("Using nvidium renderer: "+ Nvidium.MOD_VERSION);
        /*
        debugInfo.add("Memory limit: " + max_geometry_memory + " mb");
        debugInfo.add("Terrain Memory MB: " +);
        debugInfo.add(String.format("Fragmentation: %.2f", sectionManager.terrainAreana.getFragmentation()*100));
        debugInfo.add("Regions: " + sectionManager.getRegionManager().regionCount() + "/" + sectionManager.getRegionManager().maxRegions());
         */
        debugInfo.add("Mem" + (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER?"":" (fallback)") + ": " +
                (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER?
                        this.sectionManager.terrainAreana.getAllocatedMB() :
                        this.sectionManager.terrainAreana.getUsedMB())
                + "/"+ this.max_geometry_memory + String.format(", F: %.2f", sectionManager.terrainAreana.getFragmentation()*100));
        debugInfo.add("Regions: " + sectionManager.getRegionManager().regionCount() + "/" + sectionManager.getRegionManager().maxRegions());
        if (this.asyncChunkTracker != null) {
            debugInfo.add("A-BFS: " + asyncChunkTracker.getIterationTime() + " Q: " + Arrays.toString(this.asyncChunkTracker.getBuildQueueSizes()));//Async BFS iteration time:, Build queue sizes:
        }
        this.renderPipeline.addDebugInfo(debugInfo);
    }


    private void update_allowed_memory() {
        if (Nvidium.config.automatic_memory) {
            max_geometry_memory = (glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) / 1024) + (sectionManager==null?0:sectionManager.terrainAreana.getMemoryUsed()/(1024*1024));
            max_geometry_memory -= 1024;//Minus 1gb of vram
            max_geometry_memory = Math.max(2048, max_geometry_memory);//Minimum 2 gb of vram
        } else {
            max_geometry_memory = Nvidium.config.max_geometry_memory;
        }
    }

    public void update(Camera camera, Viewport viewport, boolean spectator) {
        if (asyncChunkTracker != null) {
            asyncChunkTracker.update(viewport, camera, spectator);
        }
    }

    public int getAsyncFrameId() {
        if (asyncChunkTracker != null) {
            return asyncChunkTracker.getFrame();
        } else {
            return -1;
        }
    }

    public List<RenderSection> getSectionsWithEntities() {
        if (asyncChunkTracker != null) {
            return asyncChunkTracker.getLatestSectionsWithEntities();
        } else {
            return List.of();
        }
    }

    public SectionManager getSectionManager() {
        return sectionManager;
    }

    @Nullable
    public TextureAtlasSprite[] getAnimatedSpriteSet() {
        if (asyncChunkTracker != null) {
            return asyncChunkTracker.getVisibleAnimatedSprites();
        } else {
            return new TextureAtlasSprite[0];
        }
    }

    public void setTransformation(int id, Matrix4fc transform) {
        this.renderPipeline.setTransformation(id, transform);
    }

    public void setOrigin(int id, int x, int y, int z) {
        this.renderPipeline.setOrigin(id, x, y, z);
    }

    public int getAsyncBfsVisibilityCount() {
        if (this.asyncChunkTracker != null) {
            return this.asyncChunkTracker.getLastVisibilityCount();
        } else {
            return -1;
        }
    }
    public int getMaxGeometryMemory() {
        return (int) max_geometry_memory;
    }
}
