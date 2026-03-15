package me.cortex.nvidium.util;

import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;

public class BufferArena {
    private static final int ARENA_COUNT = 4;
    private static final long ARENA_SIZE_BYTES = 1536L * 1024L * 1024L;

    private static final int ARENA_SHIFT = 30;
    private static final int LOCAL_HANDLE_MASK = 0x3fffffff;

    private final SegmentedManager[] segments = new SegmentedManager[ARENA_COUNT];
    private final IDeviceMappedBuffer[] buffers = new IDeviceMappedBuffer[ARENA_COUNT];
    private final RenderDevice device;
    private final int vertexFormatSize;
    private final long memory_size;

    private long totalQuads;

    public BufferArena(RenderDevice device, long memory, int vertexFormatSize) {
        System.err.println("BA-CTOR: entered ctor");
        System.err.println("BA-CTOR: before device assign");
        this.device = device;
        System.err.println("BA-CTOR: after device assign");
        System.err.println("BA-CTOR: before vertexFormatSize assign");
        this.vertexFormatSize = vertexFormatSize;
        System.err.println("BA-CTOR: after vertexFormatSize assign");
        System.err.println("BA-CTOR: before memory_size assign");
        this.memory_size = memory;
        System.err.println("BA-CTOR: after memory_size assign");

        System.err.println("BA-CTOR: before quadsPerArena calc");
        long quadsPerArena = ARENA_SIZE_BYTES / (4L * this.vertexFormatSize);
        System.err.println("BA-CTOR: after quadsPerArena calc");

        for (int i = 0; i < ARENA_COUNT; i++) {
            System.err.println("BA-CTOR: loop start i=" + i);
            System.err.println("BA-CTOR: before SegmentedManager ctor i=" + i);
            this.segments[i] = new SegmentedManager();
            System.err.println("BA-CTOR: after SegmentedManager ctor i=" + i);
            System.err.println("BA-CTOR: before setLimit i=" + i);
            this.segments[i].setLimit(quadsPerArena);
            System.err.println("BA-CTOR: after setLimit i=" + i);
            System.err.println("BA-CTOR: before createDeviceOnlyMappedBuffer i=" + i);
            this.buffers[i] = device.createDeviceOnlyMappedBuffer(ARENA_SIZE_BYTES);
            System.err.println("BA-CTOR: after createDeviceOnlyMappedBuffer i=" + i);
        }

        System.err.println("BA-CTOR: before allocQuads(1)");
        this.allocQuads(1);
        System.err.println("BA-CTOR: after allocQuads(1)");
        System.err.println("BA-CTOR: ctor done");
    }

    private static int packHandle(int arena, int localHandle) {
        return (arena << ARENA_SHIFT) | (localHandle & LOCAL_HANDLE_MASK);
    }

    public static int unpackArena(int packedHandle) {
        return (packedHandle >>> ARENA_SHIFT) & 0x3;
    }

    public static int unpackLocalHandle(int packedHandle) {
        return packedHandle & LOCAL_HANDLE_MASK;
    }

    private static boolean isFailure(int handle) {
        return handle == (int) SegmentedManager.SIZE_LIMIT;
    }

    public int allocQuads(int quadCount) {
        totalQuads += quadCount;

        for (int arena = 0; arena < ARENA_COUNT; arena++) {
            int local = (int) this.segments[arena].alloc(quadCount);
            if (!isFailure(local)) {
                return packHandle(arena, local);
            }
        }

        totalQuads -= quadCount;
        return (int) SegmentedManager.SIZE_LIMIT;
    }

    public void free(int addr) {
        if (addr == (int) SegmentedManager.SIZE_LIMIT) {
            return;
        }

        int arena = unpackArena(addr);
        int local = unpackLocalHandle(addr);

        int count = this.segments[arena].free(local);
        totalQuads -= count;
    }

    public long upload(UploadingBufferStream stream, int addr) {
        int arena = unpackArena(addr);
        int local = unpackLocalHandle(addr);

        long byteOffset = Integer.toUnsignedLong(local) * 4L * vertexFormatSize;
        long byteSize = this.segments[arena].getSize(local) * 4L * (long) vertexFormatSize;

        return stream.upload(this.buffers[arena], byteOffset, byteSize);
    }

    public long getArenaDeviceAddress(int packedHandle) {
        return this.buffers[unpackArena(packedHandle)].getDeviceAddress();
    }

    public long getArenaDeviceAddressByIndex(int arena) {
        return this.buffers[arena].getDeviceAddress();
    }

    public int getAllocatedMB() {
        return (int) (Math.min(this.memory_size, ARENA_COUNT * ARENA_SIZE_BYTES) / (1024L * 1024L));
    }

    public int getUsedMB() {
        return (int) ((totalQuads * vertexFormatSize * 4L) / (1024L * 1024L));
    }

    public long getMemoryUsed() {
        return Math.min(this.memory_size, ARENA_COUNT * ARENA_SIZE_BYTES);
    }

    public float getFragmentation() {
        long expected = totalQuads * vertexFormatSize * 4L;
        long backing = getMemoryUsed();

        if (backing <= 0) {
            return 1.0f;
        }

        return (float) ((double) expected / (double) backing);
    }

    public boolean canReuse(int addr, int quads) {
        if (addr == (int) SegmentedManager.SIZE_LIMIT) {
            return false;
        }

        int arena = unpackArena(addr);
        int local = unpackLocalHandle(addr);
        return this.segments[arena].getSize(local) == quads;
    }

    public void delete() {
        for (IDeviceMappedBuffer buffer : this.buffers) {
            if (buffer != null) {
                buffer.delete();
            }
        }
    }
}
