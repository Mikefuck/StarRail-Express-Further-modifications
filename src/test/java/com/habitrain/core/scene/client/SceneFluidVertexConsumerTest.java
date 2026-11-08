package com.habitrain.core.scene.client;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SceneFluidVertexConsumerTest {
    static class Sink implements VertexConsumer {
        final List<float[]> vertices=new ArrayList<>(); final List<Integer> light=new ArrayList<>(); final List<float[]> normals=new ArrayList<>();
        public VertexConsumer addVertex(float x,float y,float z){vertices.add(new float[]{x,y,z});return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        public VertexConsumer setUv(float u,float v){return this;}
        public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){light.add(u|v<<16);return this;}
        public VertexConsumer setNormal(float x,float y,float z){normals.add(new float[]{x,y,z});return this;}
    }
    void point(SceneFluidVertexConsumer c,float x,float y,float z){c.addVertex(x,y,z).setColor(255,255,255,255).setUv(0,0).setLight(0xf000f0).setNormal(0,1,0);}
    @Test void keepsFluidsInTheirActualSectionAndAssignsSideLighting() {
        Sink sink=new Sink(); long[] charged={0};
        var c=new SceneFluidVertexConsumer(sink,n->charged[0]+=n,17,18,19);
        point(c,1,2,3);point(c,1,3,3);point(c,1,3,4);point(c,1,2,4);c.finish();
        assertEquals(4,charged[0]); assertArrayEquals(new float[]{17,18,19},sink.vertices.getFirst());
        assertArrayEquals(new float[]{-1,0,0},sink.normals.getFirst());
        assertEquals(2,sink.light.getFirst()&15); assertEquals(0xf000f0,sink.light.getFirst()&~15);
    }
    @Test void negativeSectionAndTopSurfaceDoNotFoldIntoOrigin() {
        Sink sink=new Sink();var c=new SceneFluidVertexConsumer(sink,n->{},-1,-1,-1);
        point(c,15,15.9f,15);point(c,15,15.9f,16);point(c,16,15.9f,16);point(c,16,15.9f,15);c.finish();
        assertEquals(-1,sink.vertices.getFirst()[0]);assertTrue(sink.normals.getFirst()[1]>0.9f);
        assertEquals(4,sink.light.getFirst()&15);
    }
    @Test void quotaAndPartialQuadNeverPublishHalfAPrimitive() {
        Sink sink=new Sink();var c=new SceneFluidVertexConsumer(sink,n->{throw new IllegalStateException("quota");},0,0,0);
        for(int i=0;i<4;i++)point(c,i,1,0);
        assertThrows(IllegalStateException.class,c::finish);assertTrue(sink.vertices.isEmpty());
        c=new SceneFluidVertexConsumer(sink,n->{},0,0,0);point(c,0,1,0);
        assertThrows(IllegalStateException.class,c::finish);assertTrue(sink.vertices.isEmpty());
    }
}
