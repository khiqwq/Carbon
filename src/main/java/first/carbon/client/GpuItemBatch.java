package first.carbon.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

final class GpuItemBatch implements AutoCloseable {
    final ItemStack stack;
    final BakedModel model;
    final int vao;
    final VertexFormat format;
    final int vertexBuffer;
    final int indexBuffer;
    final int indices;
    final long meshBytes;
    final List<TextureAtlasSprite> sprites = new ArrayList<>();
    int spriteFrame = -1;
    int lastFrame;
    boolean closed;

    GpuItemBatch(ItemStack stack, BakedModel model, ItemBatchRenderer renderer) {
        this.stack = stack.copy();
        this.model = model;
        RandomSource random = RandomSource.create(42);
        List<BakedQuad> quads = new ArrayList<>();
        for (int face = 0; face < 7; face++) {
            random.setSeed(42);
            for (BakedQuad quad : model.getQuads(null, face == 6 ? null : Direction.from3DDataValue(face), random)) {
                quads.add(quad);
                if (!sprites.contains(quad.getSprite())) sprites.add(quad.getSprite());
                if (!renderer.opaqueSprites.computeIfAbsent(quad.getSprite().contents(), sprite -> {
                    NativeImage image = sprite.originalImage;
                    boolean opaque = true;
                    if (image.format().hasAlpha()) {
                        for (int y = 0; y < image.getHeight() && opaque; y++) {
                            for (int x = 0; x < image.getWidth() && opaque; x++) {
                                int alpha = image.getPixelRGBA(x, y) >>> 24;
                                opaque = alpha == 0 || alpha == 255;
                            }
                        }
                    }
                    return opaque;
                })) throw new IllegalArgumentException("Translucent pixels require sorted rendering");
            }
        }
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        vao = glGenVertexArrays();
        vertexBuffer = glGenBuffers();
        indexBuffer = glGenBuffers();
        renderer.capturing = true;
        try (ByteBufferBuilder allocator = new ByteBufferBuilder(4096)) {
            BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            Minecraft.getInstance().getItemRenderer().renderModelLists(model, stack, 0, 0, new PoseStack(), builder);
            try (MeshData mesh = builder.build()) {
                if (mesh == null) throw new IllegalArgumentException("Empty model");
                ByteBuffer vertices = mesh.vertexBuffer();
                int count = mesh.drawState().vertexCount();
                format = mesh.drawState().format();
                int stride = format.getVertexSize();
                int captured = 0;
                for (BakedQuad quad : quads) {
                    int[] baked = quad.getVertices();
                    for (int offset = 7; offset < baked.length; offset += 8) {
                        vertices.put(captured++ * stride + 35, (byte) ((baked[offset] & 0xFFFFFF) == 0 ? 0 : 1));
                    }
                }
                if (captured != count) throw new IllegalArgumentException("Model geometry changed during capture");
                for (int i = 0; i < count; i++) {
                    int alpha = vertices.get(i * stride + 15) & 255;
                    if (alpha != 0 && alpha != 255) throw new IllegalArgumentException("Translucent tint requires sorted rendering");
                }
                indices = count / 4 * 6;
                meshBytes = vertices.remaining() + indices * 4L;
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
                glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
                glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
                glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, stride, 12);
                glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 16);
                glVertexAttribPointer(3, 1, GL_UNSIGNED_BYTE, false, stride, 35);
                glVertexAttribIPointer(4, 2, GL_SHORT, stride, 28);
                glVertexAttribPointer(5, 3, GL_BYTE, true, stride, 32);
                for (int attribute = 0; attribute < 15; attribute++) glEnableVertexAttribArray(attribute);
                for (int attribute = 6; attribute < 15; attribute++) glVertexAttribDivisor(attribute, 1);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
                IntBuffer elements = MemoryUtil.memAllocInt(indices);
                try {
                    for (int vertex = 0; vertex < count; vertex += 4) {
                        elements.put(vertex).put(vertex + 1).put(vertex + 2);
                        elements.put(vertex + 2).put(vertex + 3).put(vertex);
                    }
                    glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements.flip(), GL_STATIC_DRAW);
                } finally {
                    MemoryUtil.memFree(elements);
                }
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        } finally {
            renderer.capturing = false;
            glBindVertexArray(previousVao);
            glBindBuffer(GL_ARRAY_BUFFER, previousBuffer);
        }
    }

    @Override
    public void close() {
        closed = true;
        first.carbon.Carbon.IRIS.releaseMesh(vertexBuffer);
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vertexBuffer);
        glDeleteBuffers(indexBuffer);
    }
}
